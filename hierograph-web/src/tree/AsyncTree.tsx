import {
  asyncDataLoaderFeature,
  hotkeysCoreFeature,
  type ItemInstance,
  selectionFeature,
  type TreeInstance,
} from "@headless-tree/core";
import { useTree } from "@headless-tree/react";
import {
  type Ref,
  useEffect,
  useEffectEvent,
  useImperativeHandle,
  useRef,
  useState,
} from "react";

import { shortNameOf } from "@/graph/nodeLabel";

import { TreeRow } from "./TreeRow";
import type { TreeSettings } from "./useTreeSettings";

export type TreeNodeData = {
  id: string;
  text: string;
  type: string;
  hasChildren: boolean;
  weight?: number;
};

export type AsyncTreeProps = {
  rootNode: TreeNodeData;
  loadChildren: (parentId: string) => Promise<TreeNodeData[]>;
  onSelectedIdsChange: (ids: string[]) => void;
  onFocusedIdChange?: (
    id: string | null,
    name: string | null,
    type: string | null,
  ) => void;
  highlightedIds?: string[];
  // Ancestor chain per highlighted hit (hit id → ancestor ids, nearest first).
  // Used only to count hidden hits per collapsed ancestor and to power
  // revealHighlighted — never for styling.
  highlightedAncestors?: Record<string, string[]>;
  // Reports the total number of highlighted hits that currently sit inside
  // collapsed branches (i.e. are not rendered). Recomputed on expand/collapse.
  onHiddenHighlightCountChange?: (count: number) => void;
  label: string;
  settings: TreeSettings;
  autoExpandOnLoad?: "root-chain" | "all";
  filterIds?: string[];
  // Tone of this tree's selection. "primary" renders the selected row in the
  // primary (blue) style; "secondary" renders in the secondary (gray) style.
  // Used to distinguish the anchor tree (center, primary) from partner trees
  // (Used-by/Uses, secondary) in the cross-reference explorer. Defaults to "primary".
  selectionTone?: "primary" | "secondary";
  // Selection behavior for row clicks. "single" forces exactly one selected
  // item at a time and ignores Shift/Ctrl/Cmd modifiers; used by the partner
  // trees of the cross-reference explorer. Defaults to "multi".
  selectionMode?: "single" | "multi";
  ref?: Ref<AsyncTreeHandle>;
};

export type AsyncTreeHandle = {
  // Expand every marked ancestor folder so all marked rows become visible.
  revealMarked: () => void;
  // Expand exactly the ancestor folders of hidden highlighted hits so they
  // become visible; preserves scroll; never automatic.
  revealHighlighted: () => void;
  // Reveal a specific node: expand exactly its ancestor folders (ids passed in
  // by the caller — the tree cannot derive them, it only loads children
  // top-down) and scroll the row into view. Never touches selection/focus/
  // highlight, so the caller's selection stays unchanged. No-op if the ancestor
  // chain never materializes the row within the frame budget.
  revealNode: (id: string, ancestorIds: string[]) => void;
  // Expand every folder in the tree (unbounded BFS). In filter mode only the
  // surviving hit paths exist, so this expands exactly those.
  expandAll: () => void;
  // Collapse the whole tree back to the root level.
  collapseAll: () => void;
  // Clear this tree's selection from the outside (e.g. an exclusive-driver
  // model on another tree taking over).
  clearSelection: () => void;
  // Currently rendered rows (in display order), for dev/debug serialization.
  getVisibleNodes: () => { id: string; text: string; type: string }[];
};

// `expandedItems`/`loadingItemChildrens` are what the visible set actually
// depends on: every expand, collapse, and async child load produces a new
// value for one of these two controlled slices. Reading them here — rather
// than reading `tree.getItems()` directly at the call site — ties this scope
// to genuine, compiler-visible dependencies instead of the mutable `tree`
// reference, which never changes on its own.
function getVisibleItems<T>(
  tree: TreeInstance<T>,
  expandedItems: string[],
  loadingItemChildrens: string[],
): ItemInstance<T>[] {
  void expandedItems;
  void loadingItemChildrens;
  return tree.getItems();
}

export function AsyncTree({
  rootNode,
  loadChildren,
  onSelectedIdsChange,
  onFocusedIdChange,
  highlightedIds,
  highlightedAncestors,
  onHiddenHighlightCountChange,
  label,
  settings,
  autoExpandOnLoad,
  filterIds,
  selectionTone = "primary",
  selectionMode = "multi",
  ref,
}: AsyncTreeProps) {
  const highlightedSet = new Set(highlightedIds ?? []);

  // No consumer reads the identity of filterSet: loads are pull-based, the
  // mount effect below has `[]` deps + a ref guard, and useImperativeHandle
  // runs without deps. Consumers remount via `key` on a filter change anyway
  // (e.g. PathsPanel's sourceKey/targetKey), so a fresh Set per render is fine.
  const filterSet = filterIds ? new Set(filterIds) : null;

  // Filter mode: marks are full paths (leaf + all ancestors), so keeping only
  // children present in filterSet retains exactly the hit paths and drops
  // non-hit siblings. Used everywhere loadChildren would otherwise be called.
  const effectiveLoadChildren = async (parentId: string) => {
    const children = await loadChildren(parentId);
    return filterSet ? children.filter((c) => filterSet.has(c.id)) : children;
  };

  const itemData = useRef<Map<string, TreeNodeData>>(
    new Map([[rootNode.id, rootNode]]),
  );

  const [expandedItems, setExpandedItems] = useState<string[]>([]);
  const [selectedItems, setSelectedItems] = useState<string[]>([]);
  const [focusedItem, setFocusedItem] = useState<string | null>(null);
  const [loadingItemChildrens, setLoadingItemChildrens] = useState<string[]>(
    [],
  );

  // Single choke point for selection notifications: sets the controlled
  // slice and notifies the parent at event time (see "You Might Not Need an
  // Effect" — Notifying parent components about state changes). Called only
  // from the useTree config's setSelectedItems and from collapseWithPrune;
  // handleRowClick and clearSelection mutate through the library (which
  // routes through the config setter), so calling this there too would
  // double-notify.
  const applySelectionChange = (ids: string[]) => {
    setSelectedItems(ids);
    onSelectedIdsChange(ids);
  };

  // Single choke point for focus notifications, analogous to
  // applySelectionChange.
  const applyFocusChange = (id: string | null) => {
    setFocusedItem(id);
    const data = id != null ? itemData.current.get(id) : undefined;
    onFocusedIdChange?.(
      id,
      data != null ? shortNameOf(data.text) : null,
      data?.type ?? null,
    );
  };

  // Collapse the item and prune expanded descendants. Optionally removes
  // descendant selections when preserveSelectionOnCollapse is off. Reads
  // `tree` and the current selection/focus slices from the render scope —
  // this is only safe because the function is not memoized (see below).
  // Declared after this because `useTree`'s hotkeys config references this
  // function — structural ordering, not effect-coupled.
  const collapseWithPrune = (item: ItemInstance<TreeNodeData>) => {
    const nodeId = item.getId();
    const nodeLevel = item.getItemMeta().level;
    const allItems = tree.getItems();
    const nodeIndex = allItems.findIndex((i) => i.getId() === nodeId);

    const descendantIds = new Set<string>();
    for (let i = nodeIndex + 1; i < allItems.length; i++) {
      if (allItems[i].getItemMeta().level <= nodeLevel) break;
      descendantIds.add(allItems[i].getId());
    }

    setExpandedItems((prev) =>
      prev.filter((id) => id !== nodeId && !descendantIds.has(id)),
    );

    if (settings.preserveSelectionOnCollapse) return;

    const nextSelected = selectedItems.filter((id) => !descendantIds.has(id));
    if (nextSelected.length !== selectedItems.length) {
      applySelectionChange(nextSelected);
    }
    if (focusedItem != null && descendantIds.has(focusedItem)) {
      applyFocusChange(nodeId);
    }
  };

  // Register child nodes into itemData so the tree renders them without
  // waiting on a separate child-loading pass.
  const registerChildren = (children: TreeNodeData[]) => {
    for (const child of children) {
      itemData.current.set(child.id, child);
    }
  };

  // Deduplicate and add multiple ids to expandedItems, using Set-dedup.
  const addExpandedItems = (ids: string[]) => {
    if (ids.length === 0) return;
    setExpandedItems((prev) => [...new Set([...prev, ...ids])]);
  };

  // Collect the chain of single-child folders starting from startId until we
  // hit a leaf or a node with multiple children. Registers children along the
  // way and returns the full chain of chained ids (not including startId).
  // Accepted race: a collapse during an in-flight chain load can re-expand ids
  // collected before the collapse; tolerated (see #104).
  const collectSingleChildChain = async (
    startId: string,
  ): Promise<string[]> => {
    let currentId = startId;
    const chained: string[] = [];
    for (;;) {
      const children = await effectiveLoadChildren(currentId);
      registerChildren(children);
      if (children.length !== 1 || !children[0].hasChildren) break;
      const childId = children[0].id;
      chained.push(childId);
      currentId = childId;
    }
    return chained;
  };

  // Expand folders matching a predicate via unbounded BFS, descending level by
  // level so node ids materialize before expansion. Root is the hidden container
  // item (rootItemId) and is never pushed into expandedItems — start by loading
  // its children, then descend level by level so each next level's node ids
  // materialize before we expand them. Accepted race: a collapse during an
  // in-flight BFS/chain load can re-expand ids collected before the collapse;
  // tolerated (see #104).
  const expandFoldersMatching = async (
    shouldOpen: (child: TreeNodeData) => boolean,
  ): Promise<void> => {
    const toExpand: string[] = [];
    const queue: string[] = [rootNode.id];
    while (queue.length > 0) {
      const parentId = queue.shift() as string;
      const children = await effectiveLoadChildren(parentId);
      registerChildren(children);
      for (const child of children) {
        if (shouldOpen(child)) {
          toExpand.push(child.id);
          queue.push(child.id);
        }
      }
    }
    addExpandedItems(toExpand);
  };

  // Expand the item. When autoExpandSingleChildren is on, chains through
  // single-child folders until a node with ≥2 children or a non-folder is
  // reached. Chaining crosses module boundaries (e.g. project -> jar module ->
  // root package) so a deeply nested single-child path opens in one action.
  const expandWithAutoExpand = async (item: ItemInstance<TreeNodeData>) => {
    const startId = item.getId();
    addExpandedItems([startId]);
    if (!settings.autoExpandSingleChildren) return;
    addExpandedItems(await collectSingleChildChain(startId));
  };

  // Drill from the (hidden) root through single-child folders on initial load,
  // opening the tree down to the first real branch. Mirrors the chaining loop
  // in expandWithAutoExpand, but starts at rootNode.id and never pushes the
  // root itself into expandedItems (it is the hidden container item, rootItemId),
  // and runs regardless of settings.autoExpandSingleChildren — this is the
  // "first moment" orientation, not the interactive expand.
  const autoExpandRootChain = async () => {
    addExpandedItems(await collectSingleChildChain(rootNode.id));
  };

  // Expand every folder in the tree via unbounded BFS. Filter-agnostic: it
  // drills through effectiveLoadChildren, so in filter mode only the surviving
  // hit paths exist and it opens exactly those, while unfiltered it opens the
  // whole tree. Unbounded: loads each level's children; consistent with
  // revealMarked. A node/depth guardrail for very large trees is a follow-up.
  const expandAllFolders = async () => {
    await expandFoldersMatching((c) => c.hasChildren);
  };

  const revealMarked = async () => {
    if (highlightedSet.size === 0) return;
    await expandFoldersMatching(
      (c) => highlightedSet.has(c.id) && c.hasChildren,
    );
  };

  // Expand exactly the ancestor folders of the hidden highlighted hits, so
  // they become visible. Descends level by level from the (hidden) root,
  // expanding any loaded child whose id is an ancestor of some hit. Only
  // touches expandedItems, so the scroll position is preserved; never runs
  // automatically.
  const revealHighlighted = async () => {
    const ancestorSet = new Set(
      Object.values(highlightedAncestors ?? {}).flat(),
    );
    ancestorSet.delete(rootNode.id);
    if (ancestorSet.size === 0) return;
    await expandFoldersMatching((c) => ancestorSet.has(c.id));
  };

  // Bounded rAF retry: expandFoldersMatching's final addExpandedItems triggers a
  // setState, so the target row is only in the DOM after the next React commit.
  // Poll getElement() for up to ~10 frames, then give up silently (no crash) —
  // e.g. if the id is not actually in this tree.
  const scrollNodeIntoView = (id: string) => {
    let frames = 0;
    const tryScroll = () => {
      const element = tree.getItemInstance(id)?.getElement();
      if (element) {
        element.scrollIntoView({ block: "nearest" });
        return;
      }
      if (frames++ < 10) requestAnimationFrame(tryScroll);
    };
    requestAnimationFrame(tryScroll);
  };

  const revealNode = async (id: string, ancestorIds: string[]) => {
    const ancestorSet = new Set(ancestorIds);
    ancestorSet.delete(rootNode.id);
    // Empty chain = top-level node: nothing to expand, still scroll.
    if (ancestorSet.size > 0) {
      await expandFoldersMatching((c) => ancestorSet.has(c.id));
    }
    scrollNodeIntoView(id);
  };

  const tree = useTree<TreeNodeData>({
    rootItemId: rootNode.id,
    getItemName: (item) => item.getItemData().text,
    isItemFolder: (item) => item.getItemData().hasChildren,
    createLoadingItemData: () => ({
      id: "",
      text: "Loading…",
      type: "",
      hasChildren: false,
    }),
    state: { expandedItems, selectedItems, focusedItem, loadingItemChildrens },
    setExpandedItems,
    setLoadingItemChildrens,
    // applySubStateUpdate always passes the resolved value; the functional
    // branch only exists to satisfy the SetStateFn<T> signature.
    setSelectedItems: (updater) =>
      applySelectionChange(
        typeof updater === "function" ? updater(selectedItems) : updater,
      ),
    setFocusedItem: (updater) =>
      applyFocusChange(
        typeof updater === "function" ? updater(focusedItem) : updater,
      ),
    dataLoader: {
      async getItem(id: string) {
        return (
          itemData.current.get(id) ?? {
            id,
            text: id,
            type: "",
            hasChildren: false,
          }
        );
      },
      async getChildren(id: string) {
        const nodes = await effectiveLoadChildren(id);
        registerChildren(nodes);
        return nodes.map((n) => n.id);
      },
    },
    features: [asyncDataLoaderFeature, selectionFeature, hotkeysCoreFeature],
    hotkeys: {
      collapseOrUp: {
        hotkey: "ArrowLeft",
        canRepeat: true,
        handler: (_e, t) => {
          const focused = t.getFocusedItem();
          if (focused.isExpanded() && focused.isFolder()) {
            collapseWithPrune(focused);
          } else if (focused.getItemMeta().level !== 0) {
            focused.getParent()?.setFocused();
            t.updateDomFocus();
          }
        },
      },
      expandOrDown: {
        hotkey: "ArrowRight",
        canRepeat: true,
        handler: (_e, t) => {
          const focused = t.getFocusedItem();
          if (focused.isFolder() && !focused.isExpanded()) {
            expandWithAutoExpand(focused).catch(console.error);
          } else {
            t.focusNextItem();
            t.updateDomFocus();
          }
        },
      },
    },
  });

  // Two selection modes, both routed through the library
  // (`item.selectUpTo`/`item.toggleSelect`/`tree.setSelectedItems`), which
  // routes through the config's `setSelectedItems` above — never call
  // applySelectionChange here too, that would double-notify. In "single"
  // mode every click replaces the selection with just this item, ignoring
  // Shift/Ctrl/Cmd modifiers.
  const handleRowClick = (
    item: ItemInstance<TreeNodeData>,
    e: React.MouseEvent,
  ) => {
    const id = item.getId();
    if (selectionMode === "single") {
      tree.setSelectedItems([id]);
      applyFocusChange(id);
    } else if (e.shiftKey) {
      item.selectUpTo(e.ctrlKey || e.metaKey);
      applyFocusChange(id);
    } else if (e.metaKey || e.ctrlKey) {
      item.toggleSelect();
      applyFocusChange(id);
    } else {
      tree.setSelectedItems([id]);
      applyFocusChange(id);
    }
  };

  useImperativeHandle(ref, () => ({
    revealMarked() {
      revealMarked().catch(console.error);
    },
    revealHighlighted() {
      revealHighlighted().catch(console.error);
    },
    revealNode(id, ancestorIds) {
      revealNode(id, ancestorIds).catch(console.error);
    },
    expandAll() {
      expandAllFolders().catch(console.error);
    },
    collapseAll() {
      setExpandedItems([]);
    },
    clearSelection() {
      // Routes through the library and the config's setSelectedItems above
      // — exactly one notify via the choke point, no direct call here.
      tree.setSelectedItems([]);
    },
    getVisibleNodes() {
      return tree.getItems().map((item) => {
        const data = item.getItemData();
        return { id: data.id, text: data.text, type: data.type };
      });
    },
  }));

  const didAutoExpandRootRef = useRef(false);
  // Filter mode expands every surviving folder; otherwise, when requested,
  // drill the root single-child chain. Toggling filter on/off is driven by a
  // consumer-side key remount, so the ref resets with the fresh mount and the
  // correct branch runs once. The drill selection itself always reads the
  // latest props/closures (via useEffectEvent), so the effect can run with an
  // empty dependency array and fire exactly once per mount.
  const runInitialDrill = useEffectEvent(() => {
    const drill = filterSet
      ? expandAllFolders
      : autoExpandOnLoad === "root-chain"
        ? autoExpandRootChain
        : autoExpandOnLoad === "all"
          ? expandAllFolders
          : null;
    drill?.().catch(console.error);
  });
  useEffect(() => {
    // Guard against React StrictMode's double effect invocation in dev: the ref
    // survives the mount→unmount→mount cycle, so the drill starts exactly once.
    if (didAutoExpandRootRef.current) return;
    didAutoExpandRootRef.current = true;
    runInitialDrill();
  }, []);

  // Reading the visible items through `getVisibleItems(tree, expandedItems,
  // loadingItemChildrens)` instead of `tree.getItems()` directly ties this
  // scope to genuine, compiler-visible reactive inputs — instead of the
  // stable `tree` reference — so the compiler re-derives the visible set on
  // every expand/collapse/child-load.
  const items = getVisibleItems(tree, expandedItems, loadingItemChildrens);
  const renderedIds = new Set(items.map((i) => i.getId()));

  // Hidden highlighted hits: highlighted ids that are not currently rendered
  // because they sit inside a collapsed branch. Each is rolled up to its
  // nearest rendered ancestor (which is provably collapsed — otherwise its
  // child on the path would render as a nearer rendered ancestor) for a
  // per-row badge count, and summed into the total; the work is O(hits ×
  // chain) and purely local — no re-query.
  const hiddenCountByAncestor = new Map<string, number>();
  let totalHidden = 0;
  for (const id of highlightedSet) {
    if (renderedIds.has(id)) continue; // visible hit is highlighted itself, no badge
    totalHidden++;
    const chain = highlightedAncestors?.[id] ?? [];
    for (const ancestorId of chain) {
      if (ancestorId !== rootNode.id && renderedIds.has(ancestorId)) {
        hiddenCountByAncestor.set(
          ancestorId,
          (hiddenCountByAncestor.get(ancestorId) ?? 0) + 1,
        );
        break;
      }
    }
  }

  // Deliberate exception to the event-time notification rule above:
  // totalHidden is pure render output derived from three sources
  // (expandedItems, async child-load completion, highlight props) — there is
  // no single event handler where the value originates. Lifting the
  // expansion state into consumers or exposing a render slot for the banner
  // was considered and rejected (logic duplication / consumers own the
  // scroll layout). Do not copy this pattern for values that originate in
  // one event handler — use a choke point like applySelectionChange instead.
  const notifyHiddenHighlightCount = useEffectEvent((count: number) => {
    onHiddenHighlightCountChange?.(count);
  });
  useEffect(() => {
    notifyHiddenHighlightCount(totalHidden);
  }, [totalHidden]);

  return (
    <div {...tree.getContainerProps(label)}>
      {items.map((item) => (
        <TreeRow
          key={item.getId()}
          item={item}
          rowProps={item.getProps() as React.HTMLAttributes<HTMLDivElement>}
          level={item.getItemMeta().level}
          isFolder={item.isFolder()}
          isLoading={item.isLoading()}
          isExpanded={item.isExpanded()}
          isSelected={item.isSelected()}
          nodeData={item.getItemData()}
          isHighlighted={highlightedSet.has(item.getId())}
          hiddenHighlightCount={hiddenCountByAncestor.get(item.getId()) ?? 0}
          selectionTone={selectionTone}
          settings={settings}
          onRowClick={handleRowClick}
          onChevronClick={(item, e) => {
            e.stopPropagation();
            if (item.isExpanded()) {
              collapseWithPrune(item);
            } else {
              expandWithAutoExpand(item).catch(console.error);
            }
          }}
        />
      ))}
    </div>
  );
}
