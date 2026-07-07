import {
  asyncDataLoaderFeature,
  hotkeysCoreFeature,
  type ItemInstance,
  selectionFeature,
  type TreeState,
} from "@headless-tree/core";
import { useTree } from "@headless-tree/react";
import { ChevronRight, Loader2 } from "lucide-react";
import {
  createElement,
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
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
  markedIds?: string[];
  label: string;
  settings: TreeSettings;
  autoExpandOnLoad?: "root-chain" | "all";
  filterIds?: string[];
};

export type AsyncTreeHandle = {
  // Expand every marked ancestor folder so all marked rows become visible.
  revealMarked: () => void;
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

export const AsyncTree = forwardRef<AsyncTreeHandle, AsyncTreeProps>(
  function AsyncTree(
    {
      rootNode,
      loadChildren,
      onSelectedIdsChange,
      onFocusedIdChange,
      onHoveredIdChange,
      onPromoteToSubject,
      markedIds,
      label,
      settings,
      autoExpandOnLoad,
      filterIds,
    },
    ref,
  ) {
    const markedSet = useMemo(() => new Set(markedIds ?? []), [markedIds]);

    // Content-keyed so a new-but-equal filterIds array does not churn the
    // loader identity (and thus the tree's memoized callbacks) on every render.
    const filterKey = filterIds ? filterIds.join(",") : null;
    const filterSet = useMemo(
      () => (filterIds ? new Set(filterIds) : null),
      // eslint-disable-next-line react-hooks/exhaustive-deps
      [filterKey],
    );

    // Filter mode: marks are full paths (leaf + all ancestors), so keeping only
    // children present in filterSet retains exactly the hit paths and drops
    // non-hit siblings. Used everywhere loadChildren would otherwise be called.
    const effectiveLoadChildren = useCallback(
      async (parentId: string) => {
        const children = await loadChildren(parentId);
        return filterSet
          ? children.filter((c) => filterSet.has(c.id))
          : children;
      },
      [loadChildren, filterSet],
    );

    const itemData = useRef<Map<string, TreeNodeData>>(
      new Map([[rootNode.id, rootNode]]),
    );

    const [state, setState] = useState<Partial<TreeState<TreeNodeData>>>({});

    const setFocusedItem = useCallback((id: string | null) => {
      setState((prev) => ({ ...prev, focusedItem: id ?? undefined }));
    }, []);

    // Collapse the item and prune expanded descendants. Optionally removes
    // descendant selections when preserveSelectionOnCollapse is off.
    const collapseWithPrune = useCallback(
      (item: ItemInstance<TreeNodeData>) => {
        const nodeId = item.getId();
        const nodeLevel = item.getItemMeta().level;
        const allItems = tree.getItems();
        const nodeIndex = allItems.findIndex((i) => i.getId() === nodeId);

        const descendantIds = new Set<string>();
        for (let i = nodeIndex + 1; i < allItems.length; i++) {
          if (allItems[i].getItemMeta().level <= nodeLevel) break;
          descendantIds.add(allItems[i].getId());
        }

        setState((prev) => {
          const nextExpanded = (prev.expandedItems ?? []).filter(
            (id) => id !== nodeId && !descendantIds.has(id),
          );

          if (settings.preserveSelectionOnCollapse) {
            return { ...prev, expandedItems: nextExpanded };
          }

          const nextSelected = (prev.selectedItems ?? []).filter(
            (id) => !descendantIds.has(id),
          );
          const nextFocused =
            prev.focusedItem != null && descendantIds.has(prev.focusedItem)
              ? nodeId
              : prev.focusedItem;

          return {
            ...prev,
            expandedItems: nextExpanded,
            selectedItems: nextSelected,
            focusedItem: nextFocused,
          };
        });
      },
      // eslint-disable-next-line react-hooks/exhaustive-deps
      [settings.preserveSelectionOnCollapse],
    );

    // Expand the item. When autoExpandSingleChildren is on, chains through
    // single-child folders until a node with ≥2 children or a non-folder is
    // reached. Chaining crosses module boundaries (e.g. project -> jar module ->
    // root package) so a deeply nested single-child path opens in one action.
    const expandWithAutoExpand = useCallback(
      async (item: ItemInstance<TreeNodeData>) => {
        const startId = item.getId();
        setState((prev) => ({
          ...prev,
          expandedItems: [...(prev.expandedItems ?? []), startId],
        }));

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
          setState((prev) => ({
            ...prev,
            expandedItems: [...(prev.expandedItems ?? []), ...chained],
          }));
        }
      },
      [settings.autoExpandSingleChildren, effectiveLoadChildren],
    );

    // Drill from the (hidden) root through single-child folders on initial load,
    // opening the tree down to the first real branch. Mirrors the chaining loop
    // in expandWithAutoExpand, but starts at rootNode.id and never pushes the
    // root itself into expandedItems (it is the hidden container item, rootItemId),
    // and runs regardless of settings.autoExpandSingleChildren — this is the
    // "first moment" orientation, not the interactive expand.
    const autoExpandRootChain = useCallback(async () => {
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
        setState((prev) => ({
          ...prev,
          expandedItems: [...(prev.expandedItems ?? []), ...chained],
        }));
      }
    }, [effectiveLoadChildren, rootNode.id]);

    // Expand every folder in the tree via unbounded BFS. Filter-agnostic: it
    // drills through effectiveLoadChildren, so in filter mode only the surviving
    // hit paths exist and it opens exactly those, while unfiltered it opens the
    // whole tree. Unbounded: loads each level's children; consistent with
    // revealMarked. A node/depth guardrail for very large trees is a follow-up.
    const expandAllFolders = useCallback(async () => {
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
        setState((prev) => ({
          ...prev,
          expandedItems: [
            ...new Set([...(prev.expandedItems ?? []), ...toExpand]),
          ],
        }));
      }
    }, [effectiveLoadChildren, rootNode.id]);

    const revealMarked = useCallback(async () => {
      if (markedSet.size === 0) return;
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
          // Only marked *folders* go into expandedItems; a marked leaf just needs
          // its ancestors open to become visible (it has no children to expand).
          if (markedSet.has(child.id) && child.hasChildren) {
            toExpand.push(child.id);
            queue.push(child.id);
          }
        }
      }
      if (toExpand.length > 0) {
        setState((prev) => ({
          ...prev,
          expandedItems: [
            ...new Set([...(prev.expandedItems ?? []), ...toExpand]),
          ],
        }));
      }
    }, [markedSet, effectiveLoadChildren, rootNode.id]);

    const handleChevronClick = useCallback(
      (item: ItemInstance<TreeNodeData>, e: React.MouseEvent) => {
        e.stopPropagation();
        if (item.isExpanded()) {
          collapseWithPrune(item);
        } else {
          expandWithAutoExpand(item).catch(console.error);
        }
      },
      [collapseWithPrune, expandWithAutoExpand],
    );

    const handleRowClick = useCallback(
      (item: ItemInstance<TreeNodeData>, e: React.MouseEvent) => {
        const id = item.getId();
        if (e.shiftKey) {
          item.selectUpTo(e.ctrlKey || e.metaKey);
          setFocusedItem(id);
        } else if (e.metaKey || e.ctrlKey) {
          item.toggleSelect();
          setFocusedItem(id);
        } else {
          tree.setSelectedItems([id]);
          setFocusedItem(id);
        }
      },
      // eslint-disable-next-line react-hooks/exhaustive-deps
      [setFocusedItem],
    );

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
      state,
      setState,
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

    useImperativeHandle(
      ref,
      () => ({
        revealMarked() {
          revealMarked().catch(console.error);
        },
        expandAll() {
          expandAllFolders().catch(console.error);
        },
        collapseAll() {
          setState((prev) => ({ ...prev, expandedItems: [] }));
        },
        clearSelection() {
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
      }),
      [revealMarked, expandAllFolders, tree, rootNode.id],
    );

    const didAutoExpandRootRef = useRef(false);
    useEffect(() => {
      if (didAutoExpandRootRef.current) return;
      // Filter mode expands every surviving folder; otherwise, when requested,
      // drill the root single-child chain. Toggling filter on/off is driven by a
      // consumer-side key remount, so the ref resets with the fresh mount and the
      // correct branch runs once.
      const drill = filterSet
        ? expandAllFolders
        : autoExpandOnLoad === "root-chain"
          ? autoExpandRootChain
          : autoExpandOnLoad === "all"
            ? expandAllFolders
            : null;
      if (!drill) return;
      // Guard against React StrictMode's double effect invocation in dev: the ref
      // survives the mount→unmount→mount cycle, so the drill starts exactly once.
      didAutoExpandRootRef.current = true;
      drill().catch(console.error);
    }, [filterSet, autoExpandOnLoad, expandAllFolders, autoExpandRootChain]);

    useEffect(() => {
      onSelectedIdsChange(state.selectedItems ?? []);
    }, [state.selectedItems, onSelectedIdsChange]);

    useEffect(() => {
      const id = state.focusedItem ?? null;
      const data = id != null ? itemData.current.get(id) : undefined;
      const name = data?.text.split(".").pop() ?? null;
      const type = data?.type ?? null;
      onFocusedIdChange?.(id, name, type);
    }, [state.focusedItem, onFocusedIdChange]);

    return (
      <div {...tree.getContainerProps(label)}>
        {tree.getItems().map((item) => (
          <TreeRow
            key={item.getId()}
            item={item}
            isMarked={markedSet.has(item.getId())}
            focusedItemId={state.focusedItem ?? null}
            settings={settings}
            onRowClick={handleRowClick}
            onChevronClick={handleChevronClick}
            onHoveredIdChange={onHoveredIdChange}
            onPromoteToSubject={onPromoteToSubject}
          />
        ))}
      </div>
    );
  },
);

type TreeRowProps = {
  item: ItemInstance<TreeNodeData>;
  isMarked: boolean;
  focusedItemId: string | null;
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
  isMarked,
  focusedItemId,
  settings,
  onRowClick,
  onChevronClick,
  onHoveredIdChange,
  onPromoteToSubject,
}: TreeRowProps) {
  const level = item.getItemMeta().level;
  const isFolder = item.isFolder();
  const isLoading = item.isLoading();
  const isExpanded = item.isExpanded();
  const isSelected = item.isSelected();
  const isFocused = focusedItemId != null && focusedItemId === item.getId();

  const [isHovered, setIsHovered] = useState(false);
  const [tooltipPos, setTooltipPos] = useState<TooltipPos | null>(null);
  const timerRef = useRef<number | null>(null);
  const pointerRef = useRef<{ x: number; y: number }>({ x: 0, y: 0 });

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  const iconColorClass = isSelected
    ? "text-state-selected-fg"
    : "text-fg-subtle";

  const nodeData = item.getItemData();
  const fullFqn = nodeData.text;
  const shortName = fullFqn.split(".").pop() ?? fullFqn;
  const displayLabel = formatNodeLabel(
    fullFqn,
    settings.labelFormat,
    nodeData.type,
  );

  return (
    <div
      {...item.getProps()}
      className={cn(
        "relative flex h-6 min-w-0 cursor-pointer items-center gap-1.5 rounded-[4px] px-2 select-none",
        isSelected && "bg-state-selected-bg text-state-selected-fg font-medium",
        !isSelected && isMarked && "bg-state-marked-bg font-medium",
        !isSelected && !isMarked && isHovered && "bg-state-hover",
        isFocused && "ring-state-focus-ring ring-2 ring-inset",
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
      {isSelected && (
        <span className="bg-state-selected-bar absolute top-0 bottom-0 left-0 w-[3px] rounded-l-[4px]" />
      )}
      {!isSelected && isMarked && (
        <span className="bg-state-marked-dot absolute top-1/2 left-1 size-1.5 -translate-y-1/2 rounded-full" />
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
      {createElement(getNodeIcon(nodeData.type), {
        className: cn("size-[15px] shrink-0", iconColorClass),
      })}
      <span className="min-w-0 flex-1 truncate text-[13px]">
        {displayLabel}
      </span>
      {nodeData.weight != null && (
        <span
          className={cn(
            "shrink-0 rounded px-1 font-mono text-[10px] tabular-nums",
            isSelected ? "text-state-selected-fg" : "text-fg-subtle",
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
            isSelected ? "text-state-selected-fg" : "text-fg-subtle",
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
