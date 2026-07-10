import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronsDown, Search } from "lucide-react";
import { useEffect, useEffectEvent, useMemo, useRef, useState } from "react";

import { Pane } from "@/design-system/layout/Pane";
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

export type CrossReferenceExplorerViewProps = {
  settings: TreeSettings;
} & TreeSettingsControls;

type ColumnInspectButtonProps = {
  label: string;
  onClick: () => void;
};

// Explicit affordance to send the aggregated Center↔column relationship to
// the Dependencies Details pane (see dependencies-details-anbindung.md,
// Regel 2) — only rendered once a center node is selected.
function ColumnInspectButton({ label, onClick }: ColumnInspectButtonProps) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      onClick={onClick}
      className="border-border-strong bg-panel flex size-[20px] shrink-0 items-center justify-center rounded-[4px] border text-[var(--hg-accent)]"
    >
      <Search className="size-[12px]" />
    </button>
  );
}

export function CrossReferenceExplorerView({
  settings,
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
  const [hiddenHighlightTotal, setHiddenHighlightTotal] = useState(0);
  const centerTreeRef = useRef<AsyncTreeHandle>(null);

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
  };

  const handleLeftSelectedIdsChange = (ids: string[]) => {
    setLeftSelectedIds(ids);
    setLastActiveSide(
      ids.length > 0 ? "left" : (prev) => (prev === "left" ? null : prev),
    );
    if (ids.length > 0) {
      setAggregateSide(null);
    }
  };

  const handleRightSelectedIdsChange = (ids: string[]) => {
    setRightSelectedIds(ids);
    setLastActiveSide(
      ids.length > 0 ? "right" : (prev) => (prev === "right" ? null : prev),
    );
    if (ids.length > 0) {
      setAggregateSide(null);
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
  // Precedence: active partner (pivot) > aggregate button > empty.
  // - Active Used-by partner P (left) → pivot (P, root), "Everything P uses".
  // - Active Uses partner Q (right) → pivot (root, Q), "Everything that uses Q".
  // - aggregateSide "left" → (root, C), "Everything that uses C".
  // - aggregateSide "right" → (C, root), "Everything C uses".
  // - Otherwise (center-only, nothing, or no aggregate chosen) → empty state.
  const rootId = rootNode?.id;
  const center = centerSelectedIds[0];

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

  return (
    <Pane
      title="Cross-Reference View"
      bodyClassName="overflow-hidden p-0"
      toolbar={
        <TreeSettingsMenu
          {...settings}
          setShowIndentGuides={setShowIndentGuides}
          setAutoExpandSingleChildren={setAutoExpandSingleChildren}
          setPreserveSelectionOnCollapse={setPreserveSelectionOnCollapse}
          setLabelFormat={setLabelFormat}
        />
      }
    >
      <div className="grid h-full min-h-0 flex-1 grid-cols-3 overflow-hidden">
        {/* Left column */}
        <div className="border-border flex min-w-0 flex-col overflow-auto border-r">
          <div className="border-border text-fg-subtle flex shrink-0 items-center justify-between border-b px-[14px] py-2 font-mono text-[11px]">
            Used by
            {centerSelectedIds.length > 0 && (
              <ColumnInspectButton
                label="Inspect Used by"
                onClick={() => setAggregateSide("left")}
              />
            )}
          </div>
          <div className="min-h-0 flex-1 overflow-auto p-1.5">
            {centerSelectedIds.length === 0 ? (
              <Message variant="empty" title="No node selected">
                Select a node in the center tree to see what uses it.
              </Message>
            ) : (
              <AsyncTree
                key={`left-${centerSelectionKey}`}
                rootNode={rootNode}
                loadChildren={loadLeftChildren}
                onSelectedIdsChange={handleLeftSelectedIdsChange}
                autoExpandOnLoad="all"
                selectionTone="secondary"
                label="XrefLeft"
                settings={settings}
              />
            )}
          </div>
        </div>
        {/* Center column */}
        <div className="border-border @container flex min-w-0 flex-col overflow-auto border-r">
          <div className="border-border text-fg-subtle shrink-0 border-b px-[14px] py-2 font-mono text-[11px]">
            Center ·{" "}
            <span className="text-fg-muted">select to filter left/right</span>
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
          <div className="border-border text-fg-subtle flex shrink-0 items-center justify-between border-b px-[14px] py-2 font-mono text-[11px]">
            Uses
            {centerSelectedIds.length > 0 && (
              <ColumnInspectButton
                label="Inspect Uses"
                onClick={() => setAggregateSide("right")}
              />
            )}
          </div>
          <div className="min-h-0 flex-1 overflow-auto p-1.5">
            {centerSelectedIds.length === 0 ? (
              <Message variant="empty" title="No node selected">
                Select a node in the center tree to see what it uses.
              </Message>
            ) : (
              <AsyncTree
                key={`right-${centerSelectionKey}`}
                rootNode={rootNode}
                loadChildren={loadRightChildren}
                onSelectedIdsChange={handleRightSelectedIdsChange}
                autoExpandOnLoad="all"
                selectionTone="secondary"
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
