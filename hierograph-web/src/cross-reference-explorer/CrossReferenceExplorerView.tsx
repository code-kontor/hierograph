import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowRightFromLine,
  ArrowRightToLine,
  ChevronsDown,
  Search,
} from "lucide-react";
import {
  type RefObject,
  useEffect,
  useEffectEvent,
  useMemo,
  useRef,
  useState,
} from "react";
import { twMerge } from "tailwind-merge";

import { Pane } from "@/design-system/layout/Pane";
import { HelpPopoverButton } from "@/design-system/ui/help-popover";
import { Message } from "@/design-system/ui/message";
import {
  nodeChildrenQueryOptions,
  rootNodeQueryOptions,
} from "@/graph/queries";
import { useSelection } from "@/selection/SelectionContext";
import { AsyncTree, type AsyncTreeHandle } from "@/tree/AsyncTree";
import { TreeSettingsMenu } from "@/tree/TreeSettingsMenu";
import type {
  TreeSettings,
  TreeSettingsControls,
} from "@/tree/useTreeSettings";

import {
  crossReferenceExplorerCenterPredecessorsQueryOptions,
  crossReferenceExplorerCenterRelatedByLeftQueryOptions,
  crossReferenceExplorerCenterRelatedByRightQueryOptions,
  crossReferenceExplorerLeftChildrenQueryOptions,
  crossReferenceExplorerRightChildrenQueryOptions,
} from "./queries";
import { useNodeLabel } from "./useNodeLabel";

export type CrossReferenceExplorerViewProps = {
  settings: TreeSettings;
  centerTreeRef: RefObject<AsyncTreeHandle | null>;
} & TreeSettingsControls;

type ColumnInspectButtonProps = {
  label: string;
  active: boolean;
  onClick: () => void;
};

// Explicit affordance to send the aggregated Center↔column relationship to
// the Dependencies Details pane (see dependencies-details-anbindung.md,
// Regel 2) — only rendered once a center node is selected.
function ColumnInspectButton({
  label,
  active,
  onClick,
}: ColumnInspectButtonProps) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      aria-pressed={active}
      onClick={onClick}
      className={twMerge(
        "border-border-strong bg-panel flex size-[20px] shrink-0 items-center justify-center rounded-[4px] border",
        active && "bg-[var(--hg-accent)]",
      )}
    >
      <Search
        className={twMerge(
          "size-[12px] text-[var(--hg-accent)]",
          active && "text-[var(--hg-accent-fg)]",
        )}
      />
    </button>
  );
}

type ColumnInspectHintProps = {
  message: string;
};

// Inline hint shown in a column header when Inspect is clicked while the
// center selection has more than one node — the aggregate needs a single
// anchor. Rendered as a plain text line (like the column sub-labels) rather
// than the full Message component, which doesn't fit the narrow header.
function ColumnInspectHint({ message }: ColumnInspectHintProps) {
  return (
    <div
      className={twMerge(
        "mt-0.5 truncate text-[10.5px] text-[var(--hg-accent)]",
      )}
    >
      {message}
    </div>
  );
}

const CROSS_REFERENCE_HELP_LABEL = "About the Cross-Reference Explorer";

function CrossReferenceHelpContent() {
  return (
    <>
      <p>
        The Cross-Reference Explorer answers <em>who depends on what</em>: pick
        any type or package and see, side by side, what uses it and what it uses
        — a fast way to gauge the ripple of a planned refactoring before you
        make it.
      </p>
      <p>
        Center is the anchor. <em>Used by</em> (left, incoming) lists everything
        that uses the center; <em>Uses</em> (right, outgoing) lists everything
        the center uses. Click a node in either partner column to pivot: it
        becomes the subject and the center highlights its own dependencies. The
        inspect buttons (🔍) open the aggregated Center↔column relationship in
        Dependencies Details.
      </p>
    </>
  );
}

export function CrossReferenceExplorerView({
  settings,
  centerTreeRef,
  setShowIndentGuides,
  setAutoExpandSingleChildren,
  setPreserveSelectionOnCollapse,
  setLabelFormat,
}: CrossReferenceExplorerViewProps) {
  const { setCellSelection, setFocusedId, setFocusedName } = useSelection();
  const queryClient = useQueryClient();

  const {
    data: rootData,
    isPending: rootPending,
    isError: rootError,
  } = useQuery(rootNodeQueryOptions());
  const rootNode = rootData?.hierarchicalGraph?.rootNode ?? null;

  const [centerSelectedIds, setCenterSelectedIds] = useState<string[]>([]);
  const [leftSelectedIds, setLeftSelectedIds] = useState<string[]>([]);
  const [rightSelectedIds, setRightSelectedIds] = useState<string[]>([]);
  const [lastActiveSide, setLastActiveSide] = useState<"left" | "right" | null>(
    null,
  );
  // Which column's "Everything Center uses/is used by" aggregate is pinned to
  // the Dependencies Details pane (Regel 2). Reset whenever the center
  // selection changes or a partner takes over (Regel 6, Precedence).
  const [aggregateSide, setAggregateSide] = useState<"left" | "right" | null>(
    null,
  );
  // Which column shows the "select exactly one center node" inline hint
  // after an Inspect click while the center selection has more than one
  // node. Reset whenever the center selection changes.
  const [inspectHintSide, setInspectHintSide] = useState<
    "left" | "right" | null
  >(null);
  const [hiddenHighlightTotal, setHiddenHighlightTotal] = useState(0);
  const leftTreeRef = useRef<AsyncTreeHandle>(null);
  const rightTreeRef = useRef<AsyncTreeHandle>(null);

  const centerSelectionKey = [...centerSelectedIds].sort().join(",");

  const loadCenterChildren = async (parentId: string) => {
    const result = await queryClient.ensureQueryData(
      nodeChildrenQueryOptions(parentId),
    );
    return result.hierarchicalGraph?.node?.children.nodes ?? [];
  };

  const loadLeftChildren = async (parentId: string) => {
    const result = await queryClient.ensureQueryData(
      crossReferenceExplorerLeftChildrenQueryOptions(
        parentId,
        centerSelectedIds,
      ),
    );
    return (
      result.hierarchicalGraph?.node?.childrenFilteredByReferencedNodes.nodes ??
      []
    );
  };

  const loadRightChildren = async (parentId: string) => {
    const result = await queryClient.ensureQueryData(
      crossReferenceExplorerRightChildrenQueryOptions(
        parentId,
        centerSelectedIds,
      ),
    );
    return (
      result.hierarchicalGraph?.node?.childrenFilteredByReferencingNodes
        .nodes ?? []
    );
  };

  const handleCenterSelectedIdsChange = (ids: string[]) => {
    setCenterSelectedIds(ids);
    setAggregateSide(null);
    // The Left/Right trees are remounted via key on a center change and no
    // longer emit an initial onSelectedIdsChange([]), so their selections and
    // the active side are reset explicitly here.
    setLeftSelectedIds([]);
    setRightSelectedIds([]);
    setLastActiveSide(null);
    setInspectHintSide(null);
  };

  const handleInspect = (side: "left" | "right") => {
    if (centerSelectedIds.length === 1) {
      setInspectHintSide(null);
      // Inspect always wins: clear any active partner selection on both sides so
      // aggregateSide becomes the single source driving the details pane.
      leftTreeRef.current?.clearSelection();
      rightTreeRef.current?.clearSelection();
      setLeftSelectedIds([]);
      setRightSelectedIds([]);
      setLastActiveSide(null);
      setAggregateSide(side);
    } else {
      setInspectHintSide(side);
    }
  };

  const handleLeftSelectedIdsChange = (ids: string[]) => {
    setLeftSelectedIds(ids);
    if (ids.length > 0) {
      setLastActiveSide("left");
      setAggregateSide(null);
      rightTreeRef.current?.clearSelection();
    } else {
      setLastActiveSide((prev) => (prev === "left" ? null : prev));
    }
  };

  const handleRightSelectedIdsChange = (ids: string[]) => {
    setRightSelectedIds(ids);
    if (ids.length > 0) {
      setLastActiveSide("right");
      setAggregateSide(null);
      leftTreeRef.current?.clearSelection();
    } else {
      setLastActiveSide((prev) => (prev === "right" ? null : prev));
    }
  };

  const handleCenterFocusedIdChange = (
    id: string | null,
    name: string | null,
  ) => {
    setFocusedId(id);
    setFocusedName(name);
  };

  // Related queries — always called, gated by enabled. The unbounded fields
  // return the full related set over the graph, so no candidate set is needed.
  const leftMarkingEnabled =
    lastActiveSide === "left" && leftSelectedIds.length > 0;
  const rightMarkingEnabled =
    lastActiveSide === "right" && rightSelectedIds.length > 0;

  const { data: leftMarkingData } = useQuery({
    ...crossReferenceExplorerCenterRelatedByLeftQueryOptions(leftSelectedIds),
    enabled: leftMarkingEnabled,
  });

  const { data: rightMarkingData } = useQuery({
    ...crossReferenceExplorerCenterRelatedByRightQueryOptions(rightSelectedIds),
    enabled: rightMarkingEnabled,
  });

  const relatedCenterIds: string[] = leftMarkingEnabled
    ? (leftMarkingData?.hierarchicalGraph?.nodes.referencedNodes.nodeIds ?? [])
    : rightMarkingEnabled
      ? (rightMarkingData?.hierarchicalGraph?.nodes.referencingNodes.nodeIds ??
        [])
      : [];

  // Ancestor chains for the related hits, so hidden hits inside collapsed
  // branches can be counted per collapsed ancestor and revealed on demand.
  const { data: predecessorsData } = useQuery({
    ...crossReferenceExplorerCenterPredecessorsQueryOptions(relatedCenterIds),
    enabled: relatedCenterIds.length > 0,
  });

  // hit id → ancestor ids (nearest first). Content-stable so AsyncTree does not
  // churn on every render; the mapping is by id only, never by fqn.
  const relatedCenterKey = relatedCenterIds.join(",");
  const highlightedAncestors = useMemo(() => {
    const nodes = predecessorsData?.hierarchicalGraph?.nodes.nodes ?? [];
    const result: Record<string, string[]> = {};
    for (const node of nodes) {
      result[node.id] = node.predecessors.map((p) => p.id);
    }
    return result;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [predecessorsData, relatedCenterKey]);

  // The cell shown in the Dependencies Details pane, derived from the current
  // selection (dependencies-details-anbindung.md). Only first-selected ids
  // are used; DependencyDetailsPane takes a single directed pair.
  //
  // Exactly one source is ever set — an active partner pivot OR an aggregate
  // (Inspect) button; each action clears the other (a partner click resets
  // aggregateSide, an Inspect click clears both partner selections), so the
  // branch order below is not load-bearing: at most one condition is ever true.
  // - Active Used-by partner P (left) → pivot (P, root), "Everything P uses".
  // - Active Uses partner Q (right) → pivot (root, Q), "Everything that uses Q".
  // - aggregateSide "left" → (root, C), "Everything that uses C".
  // - aggregateSide "right" → (C, root), "Everything C uses".
  // - Otherwise (center-only, nothing, or no aggregate chosen) → empty state.
  const rootId = rootNode?.id;
  const center = centerSelectedIds[0];

  // Names for the dynamic labels/tooltips below. Both hooks are called
  // unconditionally — gating happens via `enabled` inside useNodeLabel.
  const centerLabel = useNodeLabel(center, settings.labelFormat);
  const centerDisplayLabel =
    centerSelectedIds.length === 1
      ? centerLabel
      : `${centerSelectedIds.length} nodes`;
  // Center as the subject of the "Uses" direction (analogous to the DSM's
  // references/referencing verb pair).
  const centerUsesVerb = centerSelectedIds.length === 1 ? "uses" : "use";
  const partnerId =
    lastActiveSide === "left"
      ? leftSelectedIds[0]
      : lastActiveSide === "right"
        ? rightSelectedIds[0]
        : undefined;
  const partnerLabel = useNodeLabel(partnerId, settings.labelFormat);

  let cellSource: string | undefined;
  let cellTarget: string | undefined;
  if (lastActiveSide === "left" && leftSelectedIds[0] !== undefined) {
    cellSource = leftSelectedIds[0];
    cellTarget = rootId;
  } else if (lastActiveSide === "right" && rightSelectedIds[0] !== undefined) {
    cellSource = rootId;
    cellTarget = rightSelectedIds[0];
  } else if (aggregateSide === "left" && center !== undefined) {
    cellSource = rootId;
    cellTarget = center;
  } else if (aggregateSide === "right" && center !== undefined) {
    cellSource = center;
    cellTarget = rootId;
  }

  const notifyCellSelection = useEffectEvent(
    (source: string | undefined, target: string | undefined) => {
      if (source !== undefined && target !== undefined) {
        setCellSelection({ sourceNodeId: source, targetNodeId: target });
      } else {
        setCellSelection(null);
      }
    },
  );
  useEffect(() => {
    notifyCellSelection(cellSource, cellTarget);
  }, [cellSource, cellTarget]);

  if (rootPending) {
    return (
      <Pane title="Cross-Reference View" bodyClassName="overflow-hidden p-0">
        <div className="p-4">
          <Message variant="loading" title="Loading hierarchy" />
        </div>
      </Pane>
    );
  }

  if (rootError || !rootNode) {
    return (
      <Pane title="Cross-Reference View" bodyClassName="overflow-hidden p-0">
        <div className="p-4">
          <Message variant="error" title="Could not load hierarchy root" />
        </div>
      </Pane>
    );
  }

  // Center sub-label: one line per state — no center, center-only (anchor),
  // or an active partner pivot (Welt A — the partner becomes the subject).
  function centerSubLabel(): string {
    if (centerSelectedIds.length === 0) {
      return "select a node to explore";
    }
    if (lastActiveSide === "left" && leftSelectedIds[0] !== undefined) {
      return `Highlighting what ${partnerLabel} uses`;
    }
    if (lastActiveSide === "right" && rightSelectedIds[0] !== undefined) {
      return `Highlighting what uses ${partnerLabel}`;
    }
    if (aggregateSide === "left") {
      return `Inspecting everything that uses ${centerDisplayLabel}`;
    }
    if (aggregateSide === "right") {
      return `Inspecting everything ${centerDisplayLabel} ${centerUsesVerb}`;
    }
    return `Anchor · ${centerDisplayLabel}`;
  }

  const isSingleCenter = centerSelectedIds.length === 1;
  const leftInspectLabel = isSingleCenter
    ? `Inspect everything that uses ${centerDisplayLabel}`
    : `Inspect works on a single anchor — select exactly one center node (${centerDisplayLabel})`;
  const rightInspectLabel = isSingleCenter
    ? `Inspect everything ${centerDisplayLabel} ${centerUsesVerb}`
    : `Inspect works on a single anchor — select exactly one center node (${centerDisplayLabel})`;
  const inspectHintMessage =
    "Inspect works on a single anchor — select exactly one center node.";

  return (
    <Pane
      title="Cross-Reference View"
      bodyClassName="overflow-hidden p-0"
      toolbar={
        <div className="flex items-center gap-1">
          <HelpPopoverButton label={CROSS_REFERENCE_HELP_LABEL}>
            <CrossReferenceHelpContent />
          </HelpPopoverButton>
          <TreeSettingsMenu
            {...settings}
            setShowIndentGuides={setShowIndentGuides}
            setAutoExpandSingleChildren={setAutoExpandSingleChildren}
            setPreserveSelectionOnCollapse={setPreserveSelectionOnCollapse}
            setLabelFormat={setLabelFormat}
          />
        </div>
      }
    >
      <div className="grid h-full min-h-0 flex-1 grid-cols-3 overflow-hidden">
        {/* Left column */}
        <div className="border-border flex min-w-0 flex-col overflow-auto border-r">
          <div className="border-border text-fg-subtle shrink-0 border-b px-[14px] py-2 font-mono text-[11px]">
            <div className="flex items-center justify-between gap-2">
              <span
                className="flex min-w-0 items-center gap-1.5 truncate"
                title={
                  centerSelectedIds.length > 0
                    ? `Incoming dependencies — everything that uses ${centerDisplayLabel}. Click a node to pivot to it: the center highlights what that node uses.`
                    : undefined
                }
              >
                <ArrowRightToLine className="size-[13px] shrink-0" />
                Used by
              </span>
              {centerSelectedIds.length > 0 && (
                <ColumnInspectButton
                  label={leftInspectLabel}
                  active={aggregateSide === "left"}
                  onClick={() => handleInspect("left")}
                />
              )}
            </div>
            {centerSelectedIds.length > 0 && (
              <div
                className="text-fg-muted mt-0.5 truncate text-[10.5px]"
                title={`what uses ${centerDisplayLabel}`}
              >
                what uses {centerDisplayLabel}
              </div>
            )}
            {inspectHintSide === "left" && centerSelectedIds.length > 1 && (
              <ColumnInspectHint message={inspectHintMessage} />
            )}
          </div>
          <div className="min-h-0 flex-1 overflow-auto p-1.5">
            {centerSelectedIds.length === 0 ? (
              <Message variant="empty" title="No node selected">
                Pick a node in the center tree to see what uses it.
              </Message>
            ) : (
              <AsyncTree
                key={`left-${centerSelectionKey}`}
                ref={leftTreeRef}
                rootNode={rootNode}
                loadChildren={loadLeftChildren}
                onSelectedIdsChange={handleLeftSelectedIdsChange}
                autoExpandOnLoad="all"
                selectionTone="secondary"
                selectionMode="single"
                label="XrefLeft"
                settings={settings}
              />
            )}
          </div>
        </div>
        {/* Center column */}
        <div className="border-border @container flex min-w-0 flex-col overflow-auto border-r">
          <div className="border-border text-fg-subtle shrink-0 border-b px-[14px] py-2 font-mono text-[11px]">
            <div>Center</div>
            <div
              className="text-fg-muted mt-0.5 truncate text-[10.5px]"
              title={centerSubLabel()}
            >
              {centerSubLabel()}
            </div>
          </div>
          <div className="min-h-0 flex-1 overflow-auto p-1.5">
            <AsyncTree
              ref={centerTreeRef}
              rootNode={rootNode}
              loadChildren={loadCenterChildren}
              onSelectedIdsChange={handleCenterSelectedIdsChange}
              onFocusedIdChange={handleCenterFocusedIdChange}
              highlightedIds={relatedCenterIds}
              highlightedAncestors={highlightedAncestors}
              onHiddenHighlightCountChange={setHiddenHighlightTotal}
              label="XrefCenter"
              settings={settings}
            />
          </div>
          {hiddenHighlightTotal > 0 && (
            <div className="bg-state-highlighted-bg m-[9px_4px_2px] flex shrink-0 items-center gap-2 rounded-[6px] border border-dashed border-[var(--hl-badge-border)] px-2 py-1">
              <span className="flex h-4 min-w-[17px] shrink-0 items-center justify-center rounded-[8px] border border-[var(--hl-badge-border)] bg-[var(--hl-badge-bg)] px-[5px] font-mono text-[10px] font-semibold text-[var(--hl-badge-fg)] tabular-nums">
                {hiddenHighlightTotal}
              </span>
              <span className="text-fg-muted min-w-0 flex-1 truncate font-mono text-[11.5px] font-medium">
                highlighted nodes in collapsed branches
              </span>
              <button
                type="button"
                title="Expand all hits"
                onClick={() => centerTreeRef.current?.revealHighlighted()}
                className="border-border-strong bg-panel flex h-[26px] min-w-[22px] shrink-0 items-center justify-center gap-1 rounded-[6px] border px-[9px] py-[3px] text-[var(--hg-accent)]"
              >
                <ChevronsDown className="size-[14px]" />
                <span className="hidden text-[11.5px] font-medium @[20rem]:inline">
                  Expand
                </span>
              </button>
            </div>
          )}
        </div>
        {/* Right column */}
        <div className="flex min-w-0 flex-col overflow-auto">
          <div className="border-border text-fg-subtle shrink-0 border-b px-[14px] py-2 font-mono text-[11px]">
            <div className="flex items-center justify-between gap-2">
              <span
                className="flex min-w-0 items-center gap-1.5 truncate"
                title={
                  centerSelectedIds.length > 0
                    ? `Outgoing dependencies — everything ${centerDisplayLabel} ${centerUsesVerb}. Click a node to pivot to it: the center highlights what uses that node.`
                    : undefined
                }
              >
                <ArrowRightFromLine className="size-[13px] shrink-0" />
                Uses
              </span>
              {centerSelectedIds.length > 0 && (
                <ColumnInspectButton
                  label={rightInspectLabel}
                  active={aggregateSide === "right"}
                  onClick={() => handleInspect("right")}
                />
              )}
            </div>
            {centerSelectedIds.length > 0 && (
              <div
                className="text-fg-muted mt-0.5 truncate text-[10.5px]"
                title={`what ${centerDisplayLabel} ${centerUsesVerb}`}
              >
                what {centerDisplayLabel} {centerUsesVerb}
              </div>
            )}
            {inspectHintSide === "right" && centerSelectedIds.length > 1 && (
              <ColumnInspectHint message={inspectHintMessage} />
            )}
          </div>
          <div className="min-h-0 flex-1 overflow-auto p-1.5">
            {centerSelectedIds.length === 0 ? (
              <Message variant="empty" title="No node selected">
                Pick a node in the center tree to see what it uses.
              </Message>
            ) : (
              <AsyncTree
                key={`right-${centerSelectionKey}`}
                ref={rightTreeRef}
                rootNode={rootNode}
                loadChildren={loadRightChildren}
                onSelectedIdsChange={handleRightSelectedIdsChange}
                autoExpandOnLoad="all"
                selectionTone="secondary"
                selectionMode="single"
                label="XrefRight"
                settings={settings}
              />
            )}
          </div>
        </div>
      </div>
    </Pane>
  );
}
