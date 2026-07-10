import type { ItemInstance } from "@headless-tree/core";
import { ChevronRight, Loader2 } from "lucide-react";
import { createElement, useEffect, useRef, useState } from "react";

import { cn } from "@/design-system/cn";
import { getNodeIcon } from "@/graph/nodeIcon";
import { NodeInfoTooltip } from "@/graph/NodeInfoTooltip";
import { formatNodeLabel, shortNameOf } from "@/graph/nodeLabel";

import type { TreeNodeData } from "./AsyncTree";
import type { TreeSettings } from "./useTreeSettings";

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

export function TreeRow({
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
  const shortName = shortNameOf(fullFqn);
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
      {createElement(getNodeIcon(nodeData.type), {
        className: cn("size-[15px] shrink-0", iconColorClass),
      })}
      <div className="flex min-w-0 flex-1 items-center gap-[7px]">
        <span className="min-w-0 truncate text-[13.5px]">{displayLabel}</span>
        {isFolder && !isExpanded && hiddenHighlightCount > 0 && (
          <span
            aria-label={`${hiddenHighlightCount} hidden highlighted nodes`}
            className="flex h-4 min-w-[17px] shrink-0 items-center justify-center rounded-[8px] border border-[var(--hl-badge-border)] bg-[var(--hl-badge-bg)] px-[5px] font-mono text-[10px] font-semibold text-[var(--hl-badge-fg)] tabular-nums"
          >
            {hiddenHighlightCount}
          </span>
        )}
      </div>
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
