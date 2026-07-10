import {
  asyncDataLoaderFeature,
  hotkeysCoreFeature,
  type ItemInstance,
  selectionFeature,
  type TreeInstance,
} from "@headless-tree/core";
import { useTree } from "@headless-tree/react";
import { ChevronRight, Loader2 } from "lucide-react";
import {
  createElement,
  type Ref,
  useEffect,
  useEffectEvent,
  useImperativeHandle,
  useRef,
  useState,
} from "react";

import { cn } from "@/design-system/cn";
import { getNodeIcon } from "@/graph/nodeIcon";
import { NodeInfoTooltip } from "@/graph/NodeInfoTooltip";
import { formatNodeLabel } from "@/graph/nodeLabel";

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
  onHoveredIdChange?: (id: string | undefined) => void;
  onPromoteToSubject?: (nodeData: TreeNodeData) => void;
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
  ref?: Ref<AsyncTreeHandle>;
};

export type AsyncTreeHandle = {
  // Expand every marked ancestor folder so all marked rows become visible.
  revealMarked: () => void;
  // Expand exactly the ancestor folders of hidden highlighted hits so they
  // become visible; preserves scroll; never automatic.
  revealHighlighted: () => void;
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
  onHoveredIdChange,
  onPromoteToSubject,
  highlightedIds,
  highlightedAncestors,
  onHiddenHighlightCountChange,
  label,
  settings,
  autoExpandOnLoad,
  filterIds,
  selectionTone = "primary",
  ref,
}: AsyncTreeProps) {
  const highlightedSet = new Set(highlightedIds ?? []);

  // No consumer reads the identity of filterSet: loads are pull-based, the
  // mount effect below has `[]` deps + a ref guard, and useImperativeHandle
  // runs without deps. Consumers remount via `key` on a filter change anyway
  // (e.g. TracePanel's sourceKey/targetKey), so a fresh Set per render is fine.
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
      data?.text.split(".").pop() ?? null,
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

  // Expand the item. When autoExpandSingleChildren is on, chains through
  // single-child folders until a node with ≥2 children or a non-folder is
  // reached. Chaining crosses module boundaries (e.g. project -> jar module ->
  // root package) so a deeply nested single-child path opens in one action.
  const expandWithAutoExpand = async (item: ItemInstance<TreeNodeData>) => {
    const startId = item.getId();
    setExpandedItems((prev) => [...prev, startId]);

    if (!settings.autoExpandSingleChildren) return;

    let currentId = startId;
    const chained: string[] = [];
    for (;;) {
      const children = await effectiveLoadChildren(currentId);
      // Register loaded data so the tree can render the chained nodes without
      // waiting on its own separate child-loading pass.
      for (const child of children) {
        itemData.current.set(child.id, child);
      }
      if (children.length !== 1 || !children[0].hasChildren) {
        break;
      }
      const childId = children[0].id;
      chained.push(childId);
      currentId = childId;
    }
    if (chained.length > 0) {
      setExpandedItems((prev) => [...prev, ...chained]);
    }
  };

  // Drill from the (hidden) root through single-child folders on initial load,
  // opening the tree down to the first real branch. Mirrors the chaining loop
  // in expandWithAutoExpand, but starts at rootNode.id and never pushes the
  // root itself into expandedItems (it is the hidden container item, rootItemId),
  // and runs regardless of settings.autoExpandSingleChildren — this is the
  // "first moment" orientation, not the interactive expand.
  const autoExpandRootChain = async () => {
    let currentId = rootNode.id;
    const chained: string[] = [];
    for (;;) {
      const children = await effectiveLoadChildren(currentId);
      // Register loaded data so the tree can render the chained nodes without
      // waiting on its own separate child-loading pass.
      for (const child of children) {
        itemData.current.set(child.id, child);
      }
      if (children.length !== 1 || !children[0].hasChildren) {
        break;
      }
      const childId = children[0].id;
      chained.push(childId);
      currentId = childId;
    }
    if (chained.length > 0) {
      setExpandedItems((prev) => [...prev, ...chained]);
    }
  };

  // Expand every folder in the tree via unbounded BFS. Filter-agnostic: it
  // drills through effectiveLoadChildren, so in filter mode only the surviving
  // hit paths exist and it opens exactly those, while unfiltered it opens the
  // whole tree. Unbounded: loads each level's children; consistent with
  // revealMarked. A node/depth guardrail for very large trees is a follow-up.
  const expandAllFolders = async () => {
    const toExpand: string[] = [];
    const queue: string[] = [rootNode.id];
    while (queue.length > 0) {
      const parentId = queue.shift() as string;
      const children = await effectiveLoadChildren(parentId);
      for (const child of children) {
        itemData.current.set(child.id, child);
      }
      for (const child of children) {
        if (child.hasChildren) {
          toExpand.push(child.id);
          queue.push(child.id);
        }
      }
    }
    if (toExpand.length > 0) {
      setExpandedItems((prev) => [...new Set([...prev, ...toExpand])]);
    }
  };

  const revealMarked = async () => {
    if (highlightedSet.size === 0) return;
    const toExpand: string[] = [];
    // Root is the hidden container item (rootItemId) and is never pushed into
    // expandedItems — start by loading its children, then descend level by level
    // so each next level's node ids materialize before we expand into them.
    const queue: string[] = [rootNode.id];
    while (queue.length > 0) {
      const parentId = queue.shift() as string;
      const children = await effectiveLoadChildren(parentId);
      for (const child of children) {
        itemData.current.set(child.id, child);
      }
      for (const child of children) {
        // Only highlighted *folders* go into expandedItems; a highlighted leaf just needs
        // its ancestors open to become visible (it has no children to expand).
        if (highlightedSet.has(child.id) && child.hasChildren) {
          toExpand.push(child.id);
          queue.push(child.id);
        }
      }
    }
    if (toExpand.length > 0) {
      setExpandedItems((prev) => [...new Set([...prev, ...toExpand])]);
    }
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
    const toExpand: string[] = [];
    const queue: string[] = [rootNode.id];
    while (queue.length > 0) {
      const parentId = queue.shift() as string;
      const children = await effectiveLoadChildren(parentId);
      for (const child of children) {
        itemData.current.set(child.id, child);
      }
      for (const child of children) {
        if (ancestorSet.has(child.id)) {
          toExpand.push(child.id);
          queue.push(child.id);
        }
      }
    }
    if (toExpand.length > 0) {
      setExpandedItems((prev) => [...new Set([...prev, ...toExpand])]);
    }
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
        for (const n of nodes) {
          itemData.current.set(n.id, n);
        }
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

  // The three selection branches run through the library (`item.selectUpTo`/
  // `item.toggleSelect`/`tree.setSelectedItems`), which routes through the
  // config's `setSelectedItems` above — never call applySelectionChange here
  // too, that would double-notify.
  const handleRowClick = (
    item: ItemInstance<TreeNodeData>,
    e: React.MouseEvent,
  ) => {
    const id = item.getId();
    if (e.shiftKey) {
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
      return tree
        .getItems()
        .filter((item) => item.getId() !== rootNode.id)
        .map((item) => {
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
          onHoveredIdChange={onHoveredIdChange}
          onPromoteToSubject={onPromoteToSubject}
        />
      ))}
    </div>
  );
}

type TreeRowProps = {
  // `item` is a mutable headless-tree instance with a *stable* identity across
  // rebuilds (its `itemInstancesMap` reuses instances by id). Reading its
  // reactive state through method calls (`item.isExpanded()` etc.) at render
  // time is invisible to the React Compiler: it keys those derivations on the
  // stable `item` reference and caches them forever, so the row never reflects
  // expand/collapse/selection changes. All render-time state is therefore
  // computed in the (uncompiled) parent — which recomputes every render — and
  // passed in as plain props below. `item` is kept only for event handlers,
  // which read live state at event time (correct) rather than at render time.
  item: ItemInstance<TreeNodeData>;
  rowProps: React.HTMLAttributes<HTMLDivElement>;
  level: number;
  isFolder: boolean;
  isLoading: boolean;
  isExpanded: boolean;
  isSelected: boolean;
  nodeData: TreeNodeData;
  isHighlighted: boolean;
  hiddenHighlightCount: number;
  selectionTone: "primary" | "secondary";
  settings: TreeSettings;
  onRowClick: (item: ItemInstance<TreeNodeData>, e: React.MouseEvent) => void;
  onChevronClick: (
    item: ItemInstance<TreeNodeData>,
    e: React.MouseEvent,
  ) => void;
  onHoveredIdChange?: (id: string | undefined) => void;
  onPromoteToSubject?: (nodeData: TreeNodeData) => void;
};

type TooltipPos = { x: number; y: number };

function TreeRow({
  item,
  rowProps,
  level,
  isFolder,
  isLoading,
  isExpanded,
  isSelected,
  nodeData,
  isHighlighted,
  hiddenHighlightCount,
  selectionTone,
  settings,
  onRowClick,
  onChevronClick,
  onHoveredIdChange,
  onPromoteToSubject,
}: TreeRowProps) {
  const isPrimarySelected = isSelected && selectionTone === "primary";
  const isSecondarySelected = isSelected && selectionTone === "secondary";

  const [isHovered, setIsHovered] = useState(false);
  const [tooltipPos, setTooltipPos] = useState<TooltipPos | null>(null);
  const timerRef = useRef<number | null>(null);
  const pointerRef = useRef<{ x: number; y: number }>({ x: 0, y: 0 });

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  const iconColorClass = isPrimarySelected
    ? "text-state-selected-fg"
    : isSecondarySelected
      ? "text-fg"
      : isHighlighted
        ? "text-fg-muted"
        : "text-fg-subtle";

  const fullFqn = nodeData.text;
  const shortName = fullFqn.split(".").pop() ?? fullFqn;
  const displayLabel = formatNodeLabel(
    fullFqn,
    settings.labelFormat,
    nodeData.type,
  );

  return (
    <div
      {...rowProps}
      className={cn(
        "relative my-px flex h-7 min-w-0 cursor-pointer items-center gap-[7px] rounded-[6px] px-2 select-none",
        isPrimarySelected &&
          "bg-state-selected-bg text-state-selected-fg font-semibold",
        isSecondarySelected &&
          "bg-state-selected-secondary-bg text-fg font-semibold",
        !isSelected && isHighlighted && "bg-state-highlighted-bg font-semibold",
        !isSelected && !isHighlighted && isHovered && "bg-state-hover",
      )}
      onClick={(e) => onRowClick(item, e)}
      // Any press within the row (row body, chevron, or icon) hides the tooltip.
      onMouseDown={() => {
        if (timerRef.current) clearTimeout(timerRef.current);
        setTooltipPos(null);
      }}
      onMouseEnter={(e) => {
        setIsHovered(true);
        pointerRef.current = { x: e.clientX, y: e.clientY };
        timerRef.current = window.setTimeout(() => {
          setTooltipPos({
            x: pointerRef.current.x + 18,
            y: pointerRef.current.y + 20,
          });
        }, 480);
        onHoveredIdChange?.(item.getId());
      }}
      onMouseMove={(e) => {
        if (tooltipPos === null) {
          pointerRef.current = { x: e.clientX, y: e.clientY };
        }
      }}
      onMouseLeave={() => {
        setIsHovered(false);
        if (timerRef.current) clearTimeout(timerRef.current);
        setTooltipPos(null);
        onHoveredIdChange?.(undefined);
      }}
    >
      {isPrimarySelected && (
        <span className="bg-state-selected-bar absolute top-0 bottom-0 left-0 w-[3px] rounded-l-[6px]" />
      )}
      {isSecondarySelected && (
        <span className="bg-state-selected-secondary-bar absolute top-0 bottom-0 left-0 w-[3px] rounded-l-[6px]" />
      )}
      <span
        className="h-full shrink-0"
        style={{
          width: level * 16,
          backgroundImage: settings.showIndentGuides
            ? "repeating-linear-gradient(90deg, var(--hg-guide) 0 1px, transparent 1px 16px)"
            : undefined,
        }}
      />
      <span
        className="flex size-4 shrink-0 cursor-pointer items-center justify-center"
        onClick={(e) => isFolder && onChevronClick(item, e)}
      >
        {isFolder ? (
          isLoading ? (
            <Loader2
              className={cn("size-[11px] animate-spin", iconColorClass)}
            />
          ) : (
            <ChevronRight
              className={cn(
                "size-[13px] transition-transform duration-[120ms]",
                iconColorClass,
                isExpanded && "rotate-90",
              )}
            />
          )
        ) : null}
      </span>
      {isFolder && !isExpanded && hiddenHighlightCount > 0 && (
        <span
          aria-label={`${hiddenHighlightCount} hidden highlighted nodes`}
          className="flex h-4 min-w-[17px] shrink-0 items-center justify-center rounded-[8px] border border-[var(--hl-badge-border)] bg-[var(--hl-badge-bg)] px-[5px] font-mono text-[10px] font-semibold text-[var(--hl-badge-fg)] tabular-nums"
        >
          {hiddenHighlightCount}
        </span>
      )}
      {createElement(getNodeIcon(nodeData.type), {
        className: cn("size-[15px] shrink-0", iconColorClass),
      })}
      <span className="min-w-0 flex-1 truncate text-[13.5px]">
        {displayLabel}
      </span>
      {nodeData.weight != null && (
        <span
          className={cn(
            "shrink-0 rounded px-1 font-mono text-[10px] tabular-nums",
            isPrimarySelected ? "text-state-selected-fg" : "text-fg-subtle",
          )}
        >
          {nodeData.weight}
        </span>
      )}
      {onPromoteToSubject != null && (
        <button
          type="button"
          aria-label="Set as subject"
          className={cn(
            "shrink-0 rounded px-1 text-[11px]",
            isPrimarySelected ? "text-state-selected-fg" : "text-fg-subtle",
          )}
          onClick={(e) => {
            e.stopPropagation();
            onPromoteToSubject(nodeData);
          }}
        >
          →
        </button>
      )}
      {tooltipPos !== null && (
        <NodeInfoTooltip
          x={tooltipPos.x}
          y={tooltipPos.y}
          shortName={shortName}
          type={nodeData.type}
          fullName={fullFqn}
        />
      )}
    </div>
  );
}
