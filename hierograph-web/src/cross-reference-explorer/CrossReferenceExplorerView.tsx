import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useState } from "react";

import { Pane } from "@/design-system/layout/Pane";
import { Button } from "@/design-system/ui/button";
import { Message } from "@/design-system/ui/message";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/design-system/ui/tooltip";
import {
  nodeChildrenQueryOptions,
  rootNodeQueryOptions,
} from "@/graph/queries";
import { useSelection } from "@/selection/SelectionContext";
import { AsyncTree } from "@/tree/AsyncTree";
import { TreeSettingsMenu } from "@/tree/TreeSettingsMenu";
import type {
  TreeSettings,
  TreeSettingsControls,
} from "@/tree/useTreeSettings";

import {
  crossReferenceExplorerCenterMarkedByLeftQueryOptions,
  crossReferenceExplorerCenterMarkedByRightQueryOptions,
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
    ...crossReferenceExplorerCenterMarkedByLeftQueryOptions(
      centerLoadedIds,
      leftSelectedIds,
    ),
    enabled: leftMarkingEnabled,
  });

  const { data: rightMarkingData } = useQuery({
    ...crossReferenceExplorerCenterMarkedByRightQueryOptions(
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
                <Message variant="info" title="Highlighted nodes not visible">
                  The nodes that use or are used by this selection are in a
                  collapsed part of the center tree. Expand the center to reveal
                  them.
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
                label="XrefCenter"
                settings={settings}
              />
            </div>
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
