import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useState } from "react";

import { useSelection } from "@/components/hierarchy/SelectionContext";
import { Pane } from "@/components/layout/Pane";
import { AsyncTree } from "@/components/tree/AsyncTree";
import { TreeSettingsMenu } from "@/components/tree/TreeSettingsMenu";
import type {
  TreeSettings,
  TreeSettingsControls,
} from "@/components/tree/useTreeSettings";
import { Button } from "@/components/ui/button";
import { Message } from "@/components/ui/message";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import {
  crossReferenceCenterMarkedByLeftQueryOptions,
  crossReferenceCenterMarkedByRightQueryOptions,
  crossReferenceLeftChildrenQueryOptions,
  crossReferenceRightChildrenQueryOptions,
} from "@/queries/cross-reference";
import {
  nodeChildrenQueryOptions,
  rootNodeQueryOptions,
} from "@/queries/hierarchical-graph";

export type CrossReferenceViewProps = {
  settings: TreeSettings;
} & TreeSettingsControls;

export function CrossReferenceView({
  settings,
  setShowIndentGuides,
  setAutoExpandSingleChildren,
  setPreserveSelectionOnCollapse,
  setLabelFormat,
}: CrossReferenceViewProps) {
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
  // Candidate set for marking; seeded lazily with rootNode.id after load.
  const [centerLoadedIds, setCenterLoadedIds] = useState<string[]>([]);

  // Seed root into centerLoadedIds once it's available.
  if (rootNode && centerLoadedIds.length === 0) {
    setCenterLoadedIds([rootNode.id]);
  }

  const centerSelectionKey = [...centerSelectedIds].sort().join(",");

  const loadCenterChildren = useCallback(
    async (parentId: string) => {
      const result = await queryClient.ensureQueryData(
        nodeChildrenQueryOptions(parentId),
      );
      const nodes = result.hierarchicalGraph?.node?.children.nodes ?? [];
      const newIds = nodes.map((n) => n.id);
      setCenterLoadedIds((prev) => {
        const prevSet = new Set(prev);
        const toAdd = newIds.filter((id) => !prevSet.has(id));
        return toAdd.length > 0 ? [...prev, ...toAdd] : prev;
      });
      return nodes;
    },
    [queryClient],
  );

  const loadLeftChildren = useCallback(
    async (parentId: string) => {
      const result = await queryClient.ensureQueryData(
        crossReferenceLeftChildrenQueryOptions(parentId, centerSelectedIds),
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
        crossReferenceRightChildrenQueryOptions(parentId, centerSelectedIds),
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

  // Marking queries — always called, gated by enabled.
  const leftMarkingEnabled =
    lastActiveSide === "left" &&
    leftSelectedIds.length > 0 &&
    centerLoadedIds.length > 0;
  const rightMarkingEnabled =
    lastActiveSide === "right" &&
    rightSelectedIds.length > 0 &&
    centerLoadedIds.length > 0;

  const { data: leftMarkingData } = useQuery({
    ...crossReferenceCenterMarkedByLeftQueryOptions(
      centerLoadedIds,
      leftSelectedIds,
    ),
    enabled: leftMarkingEnabled,
  });

  const { data: rightMarkingData } = useQuery({
    ...crossReferenceCenterMarkedByRightQueryOptions(
      centerLoadedIds,
      rightSelectedIds,
    ),
    enabled: rightMarkingEnabled,
  });

  const markedCenterIds: string[] = leftMarkingEnabled
    ? (leftMarkingData?.hierarchicalGraph?.nodes.filterReferencingNodes
        .nodeIds ?? [])
    : rightMarkingEnabled
      ? (rightMarkingData?.hierarchicalGraph?.nodes.filterReferencedNodes
          .nodeIds ?? [])
      : [];

  // Hidden-selection hint: active side has a selection, but no marked nodes are visible in the
  // currently-loaded center set (e.g. the matching node hasn't been expanded in center yet).
  const showHiddenSelectionHint =
    (lastActiveSide === "left" &&
      leftSelectedIds.length > 0 &&
      leftMarkingData !== undefined &&
      markedCenterIds.length === 0) ||
    (lastActiveSide === "right" &&
      rightSelectedIds.length > 0 &&
      rightMarkingData !== undefined &&
      markedCenterIds.length === 0);

  // Inspect: enabled when a valid (source, target) pair can be derived.
  // Left active → source = left[0], target = center[0]
  // Right active → source = center[0], target = right[0]
  const inspectSource =
    lastActiveSide === "left"
      ? leftSelectedIds[0]
      : lastActiveSide === "right"
        ? centerSelectedIds[0]
        : undefined;
  const inspectTarget =
    lastActiveSide === "left"
      ? centerSelectedIds[0]
      : lastActiveSide === "right"
        ? rightSelectedIds[0]
        : undefined;
  const inspectEnabled =
    inspectSource !== undefined && inspectTarget !== undefined;

  const handleInspect = useCallback(() => {
    if (inspectSource && inspectTarget) {
      // Inspect[0] — only first-selected ids are used; DependencyDetailsPane takes a single pair.
      setCellSelection({
        sourceNodeId: inspectSource,
        targetNodeId: inspectTarget,
      });
    }
  }, [inspectSource, inspectTarget, setCellSelection]);

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
    <TooltipProvider>
      <Pane
        title="Cross-Reference View"
        bodyClassName="overflow-hidden p-0"
        toolbar={
          <>
            <Tooltip>
              <TooltipTrigger asChild>
                <span>
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={!inspectEnabled}
                    onClick={handleInspect}
                  >
                    Inspect
                  </Button>
                </span>
              </TooltipTrigger>
              <TooltipContent>
                {inspectEnabled
                  ? "Show dependencies between selected nodes in the bottom panel"
                  : "Select a node in the left or right tree and a node in the center tree to enable Inspect"}
              </TooltipContent>
            </Tooltip>
            <TreeSettingsMenu
              {...settings}
              setShowIndentGuides={setShowIndentGuides}
              setAutoExpandSingleChildren={setAutoExpandSingleChildren}
              setPreserveSelectionOnCollapse={setPreserveSelectionOnCollapse}
              setLabelFormat={setLabelFormat}
            />
          </>
        }
      >
        <div className="grid h-full min-h-0 flex-1 grid-cols-3 overflow-hidden">
          {/* Left column */}
          <div className="border-border flex min-w-0 flex-col overflow-auto border-r">
            <div className="border-border text-fg-subtle shrink-0 border-b px-[14px] py-2 font-mono text-[11px]">
              Left · references <span className="text-fg-muted">→ center</span>
            </div>
            <div className="min-h-0 flex-1 overflow-auto p-1.5">
              {centerSelectedIds.length === 0 ? (
                <Message variant="empty" title="No center selection">
                  Select a node in the center tree to see references.
                </Message>
              ) : (
                <AsyncTree
                  key={`left-${centerSelectionKey}`}
                  rootNode={rootNode}
                  loadChildren={loadLeftChildren}
                  onSelectedIdsChange={handleLeftSelectedIdsChange}
                  label="XrefLeft"
                  settings={settings}
                />
              )}
            </div>
          </div>
          {/* Center column */}
          <div className="border-border flex min-w-0 flex-col overflow-auto border-r">
            <div className="border-border text-fg-subtle shrink-0 border-b px-[14px] py-2 font-mono text-[11px]">
              Center ·{" "}
              <span className="text-fg-muted">select to filter left/right</span>
            </div>
            {showHiddenSelectionHint && (
              <div className="shrink-0 px-2 pt-2">
                <Message variant="info" title="Selection not visible">
                  The current selection is filtered and therefore not visible.
                </Message>
              </div>
            )}
            <div className="min-h-0 flex-1 overflow-auto p-1.5">
              <AsyncTree
                rootNode={rootNode}
                loadChildren={loadCenterChildren}
                onSelectedIdsChange={handleCenterSelectedIdsChange}
                onFocusedIdChange={handleCenterFocusedIdChange}
                markedIds={markedCenterIds}
                markedBadge
                label="XrefCenter"
                settings={settings}
              />
            </div>
          </div>
          {/* Right column */}
          <div className="flex min-w-0 flex-col overflow-auto">
            <div className="border-border text-fg-subtle shrink-0 border-b px-[14px] py-2 font-mono text-[11px]">
              Right · referenced by{" "}
              <span className="text-fg-muted">← center</span>
            </div>
            <div className="min-h-0 flex-1 overflow-auto p-1.5">
              {centerSelectedIds.length === 0 ? (
                <Message variant="empty" title="No center selection">
                  Select a node in the center tree to see references.
                </Message>
              ) : (
                <AsyncTree
                  key={`right-${centerSelectionKey}`}
                  rootNode={rootNode}
                  loadChildren={loadRightChildren}
                  onSelectedIdsChange={handleRightSelectedIdsChange}
                  label="XrefRight"
                  settings={settings}
                />
              )}
            </div>
          </div>
        </div>
      </Pane>
    </TooltipProvider>
  );
}
