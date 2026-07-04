import {
  asyncDataLoaderFeature,
  hotkeysCoreFeature,
  type ItemInstance,
  selectionFeature,
  type TreeState,
} from "@headless-tree/core";
import { useTree } from "@headless-tree/react";
import { ChevronRight, Loader2 } from "lucide-react";
import { createElement, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";

import { getNodeIcon } from "@/components/hierarchy/nodeIcon";
import { cn } from "@/lib/utils";

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
  onFocusedIdChange?: (id: string | null) => void;
  markedIds?: string[];
  label: string;
  showIndentGuides?: boolean;
};

export function AsyncTree({
  rootNode,
  loadChildren,
  onSelectedIdsChange,
  onFocusedIdChange,
  markedIds,
  label,
  showIndentGuides = true,
}: AsyncTreeProps) {
  const markedSet = useMemo(() => new Set(markedIds ?? []), [markedIds]);

  const itemData = useRef<Map<string, TreeNodeData>>(
    new Map([[rootNode.id, rootNode]]),
  );

  const [state, setState] = useState<Partial<TreeState<TreeNodeData>>>({});

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
  });

  useEffect(() => {
    onSelectedIdsChange(state.selectedItems ?? []);
  }, [state.selectedItems, onSelectedIdsChange]);

  useEffect(() => {
    onFocusedIdChange?.(state.focusedItem ?? null);
  }, [state.focusedItem, onFocusedIdChange]);

  return (
    <div {...tree.getContainerProps(label)}>
      {tree.getItems().map((item) => (
        <TreeRow
          key={item.getId()}
          item={item}
          isMarked={markedSet.has(item.getId())}
          focusedItemId={state.focusedItem ?? null}
          showIndentGuides={showIndentGuides}
        />
      ))}
    </div>
  );
}

type TreeRowProps = {
  item: ItemInstance<TreeNodeData>;
  isMarked: boolean;
  focusedItemId: string | null;
  showIndentGuides: boolean;
};

type TooltipPos = { x: number; y: number };

type RowTooltipProps = {
  pos: TooltipPos;
  shortName: string;
  type: string;
  fullFqn: string;
};

function RowTooltip({ pos, shortName, type, fullFqn }: RowTooltipProps) {
  const left = Math.min(pos.x, window.innerWidth - 320 - 8);
  const top = Math.min(pos.y, window.innerHeight - 8 - 96);

  return createPortal(
    <div
      className="border-border-strong bg-popover pointer-events-none z-50 max-w-[320px] rounded-lg border px-[11px] py-2 shadow-[var(--hg-shadow)]"
      style={{ position: "fixed", left, top }}
    >
      <p className="text-fg font-mono text-[12.5px] font-semibold">
        {shortName}
      </p>
      <p className="text-fg-subtle font-mono text-[11px]">{type}</p>
      <p className="text-fg-muted font-mono text-[11px] break-all">{fullFqn}</p>
    </div>,
    document.body,
  );
}

function TreeRow({
  item,
  isMarked,
  focusedItemId,
  showIndentGuides,
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
          backgroundImage: showIndentGuides
            ? "repeating-linear-gradient(90deg, var(--hg-guide) 0 1px, transparent 1px 16px)"
            : undefined,
        }}
      />
      <span className="flex size-4 shrink-0 items-center justify-center">
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
        {item.getItemName()}
      </span>
      {tooltipPos !== null && (
        <RowTooltip
          pos={tooltipPos}
          shortName={shortName}
          type={nodeData.type}
          fullFqn={fullFqn}
        />
      )}
    </div>
  );
}
