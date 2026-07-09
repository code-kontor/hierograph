import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronsDown } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

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
  const [hiddenHighlightTotal, setHiddenHighlightTotal] = useState(0);
  const centerTreeRef = useRef<AsyncTreeHandle>(null);

  const centerSelectionKey = [...centerSelectedIds].sort().join(",");

  const loadCenterChildren = useCallback(
    async (parentId: string) => {
      const result = await queryClient.ensureQueryData(
        nodeChildrenQueryOptions(parentId),
      );
      return result.hierarchicalGraph?.node?.children.nodes ?? [];
    },
    [queryClient],
  );

  const loadLeftChildren = useCallback(
    async (parentId: string) => {
      const result = await queryClient.ensureQueryData(
        crossReferenceExplorerLeftChildrenQueryOptions(
          parentId,
          centerSelectedIds,
        ),
      );
      return (
        result.hierarchicalGraph?.node?.childrenFilteredByReferencedNodes
          .nodes ?? []
      );
    },
    [queryClient, centerSelectedIds],
  );

  const loadRightChildren = useCallback(
    async (parentId: string) => {
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
    },
    [queryClient, centerSelectedIds],
  );

  const handleCenterSelectedIdsChange = useCallback((ids: string[]) => {
    setCenterSelectedIds(ids);
    // Remounting Left/Right resets their selections, which fires onSelectedIdsChange([]).
    // Those handlers will clear lastActiveSide correctly.
  }, []);

  const handleLeftSelectedIdsChange = useCallback((ids: string[]) => {
    setLeftSelectedIds(ids);
    setLastActiveSide(
      ids.length > 0 ? "left" : (prev) => (prev === "left" ? null : prev),
    );
  }, []);

  const handleRightSelectedIdsChange = useCallback((ids: string[]) => {
    setRightSelectedIds(ids);
    setLastActiveSide(
      ids.length > 0 ? "right" : (prev) => (prev === "right" ? null : prev),
    );
  }, []);

  const handleCenterFocusedIdChange = useCallback(
    (id: string | null, name: string | null) => {
      setFocusedId(id);
      setFocusedName(name);
    },
    [setFocusedId, setFocusedName],
  );

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
    ? (leftMarkingData?.hierarchicalGraph?.nodes.referencingNodes.nodeIds ?? [])
    : rightMarkingEnabled
      ? (rightMarkingData?.hierarchicalGraph?.nodes.referencedNodes.nodeIds ??
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
  // selection. Only first-selected ids are used; DependencyDetailsPane takes a
  // single directed pair.
  // Left active → source = left[0], target = center[0] ("Used by" partner)
  // Right active → source = center[0], target = right[0] ("Uses" partner)
  // Center only (no active partner) → default source = root, target = center[0]
  // ("everything that uses" the center selection).
  const center = centerSelectedIds[0];
  const cellSource =
    center === undefined
      ? undefined
      : lastActiveSide === "left" && leftSelectedIds[0] !== undefined
        ? leftSelectedIds[0]
        : lastActiveSide === "right"
          ? center
          : rootNode?.id;
  const cellTarget =
    center === undefined
      ? undefined
      : lastActiveSide === "right" && rightSelectedIds[0] !== undefined
        ? rightSelectedIds[0]
        : center;

  useEffect(() => {
    if (cellSource !== undefined && cellTarget !== undefined) {
      setCellSelection({ sourceNodeId: cellSource, targetNodeId: cellTarget });
    } else {
      setCellSelection(null);
    }
  }, [cellSource, cellTarget, setCellSelection]);

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
          <div className="border-border text-fg-subtle shrink-0 border-b px-[14px] py-2 font-mono text-[11px]">
            Used by
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
          <div className="border-border text-fg-subtle shrink-0 border-b px-[14px] py-2 font-mono text-[11px]">
            Uses
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
