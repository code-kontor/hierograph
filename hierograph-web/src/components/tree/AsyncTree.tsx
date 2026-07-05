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
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import { getNodeIcon } from "@/graph/nodeIcon";
import { NodeInfoTooltip } from "@/graph/NodeInfoTooltip";
import { formatTreeLabel } from "@/graph/treeLabelFormat";
import { cn } from "@/lib/utils";

import type { TreeSettings } from "./useTreeSettings";

export type TreeNodeData = {
  id: string;
  text: string;
  type: string;
  hasChildren: boolean;
};

export type AsyncTreeProps = {
  rootNode: TreeNodeData;
  loadChildren: (parentId: string) => Promise<TreeNodeData[]>;
  onSelectedIdsChange: (ids: string[]) => void;
  onFocusedIdChange?: (id: string | null, name: string | null) => void;
  markedIds?: string[];
  markedBadge?: boolean;
  label: string;
  settings: TreeSettings;
};

export function AsyncTree({
  rootNode,
  loadChildren,
  onSelectedIdsChange,
  onFocusedIdChange,
  markedIds,
  markedBadge = false,
  label,
  settings,
}: AsyncTreeProps) {
  const markedSet = useMemo(() => new Set(markedIds ?? []), [markedIds]);

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
        const children = await loadChildren(currentId);
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
    [settings.autoExpandSingleChildren, loadChildren],
  );

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
        const nodes = await loadChildren(id);
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

  useEffect(() => {
    onSelectedIdsChange(state.selectedItems ?? []);
  }, [state.selectedItems, onSelectedIdsChange]);

  useEffect(() => {
    const id = state.focusedItem ?? null;
    const name =
      id != null
        ? (itemData.current.get(id)?.text.split(".").pop() ?? null)
        : null;
    onFocusedIdChange?.(id, name);
  }, [state.focusedItem, onFocusedIdChange]);

  return (
    <div {...tree.getContainerProps(label)}>
      {tree.getItems().map((item) => (
        <TreeRow
          key={item.getId()}
          item={item}
          isMarked={markedSet.has(item.getId())}
          markedBadge={markedBadge}
          focusedItemId={state.focusedItem ?? null}
          settings={settings}
          onRowClick={handleRowClick}
          onChevronClick={handleChevronClick}
        />
      ))}
    </div>
  );
}

type TreeRowProps = {
  item: ItemInstance<TreeNodeData>;
  isMarked: boolean;
  markedBadge: boolean;
  focusedItemId: string | null;
  settings: TreeSettings;
  onRowClick: (item: ItemInstance<TreeNodeData>, e: React.MouseEvent) => void;
  onChevronClick: (
    item: ItemInstance<TreeNodeData>,
    e: React.MouseEvent,
  ) => void;
};

type TooltipPos = { x: number; y: number };

function TreeRow({
  item,
  isMarked,
  markedBadge,
  focusedItemId,
  settings,
  onRowClick,
  onChevronClick,
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
    : !isSelected && isMarked
      ? "text-state-marked-fg"
      : "text-fg-subtle";

  const nodeData = item.getItemData();
  const fullFqn = nodeData.text;
  const shortName = fullFqn.split(".").pop() ?? fullFqn;
  const displayLabel = formatTreeLabel(
    fullFqn,
    nodeData.type,
    settings.labelFormat,
  );

  return (
    <div
      {...item.getProps()}
      className={cn(
        "relative flex h-6 min-w-0 cursor-pointer items-center gap-1.5 rounded-[4px] px-2 select-none",
        isSelected && "bg-state-selected-bg text-state-selected-fg font-medium",
        !isSelected &&
          isMarked &&
          "bg-state-marked-bg text-state-marked-fg font-medium",
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
      }}
    >
      {(isSelected || (!isSelected && isMarked)) && (
        <span
          className={cn(
            "absolute top-0 bottom-0 left-0 w-[3px] rounded-l-[4px]",
            isSelected ? "bg-state-selected-bar" : "bg-state-marked-bar",
          )}
        />
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
      {markedBadge && isMarked && !isSelected && (
        <span className="text-state-marked-fg ml-auto pr-2 font-mono text-[10px] opacity-85">
          ◆ marked
        </span>
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
