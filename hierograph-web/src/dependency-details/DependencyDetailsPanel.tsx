import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";

import { Message } from "@/design-system/ui/message";
import { formatNodeLabel, type NodeLabelFormat } from "@/graph/nodeLabel";
import { nodeBasicsQueryOptions } from "@/graph/queries";
import { AsyncTree, type AsyncTreeHandle } from "@/tree/AsyncTree";
import { DEFAULT_TREE_SETTINGS } from "@/tree/useTreeSettings";

import {
  filteredChildrenQueryOptions,
  filteredDependenciesQueryOptions,
} from "./queries";
import { useDebouncedValue } from "./useDebouncedValue";

export type DependencyDetailsPanelProps = {
  sourceNodeId: string;
  targetNodeId: string;
  labelFormat: NodeLabelFormat;
  autoExpandSingleChildren: boolean;
  autoRevealCounterparts: boolean;
  highlightOnHover: boolean;
  filterCounterparts: boolean;
};

export function DependencyDetailsPanel({
  sourceNodeId,
  targetNodeId,
  labelFormat,
  autoExpandSingleChildren,
  autoRevealCounterparts,
  highlightOnHover,
  filterCounterparts,
}: DependencyDetailsPanelProps) {
  const queryClient = useQueryClient();
  const [selectedSourceIds, setSelectedSourceIds] = useState<string[]>([]);
  const [selectedTargetIds, setSelectedTargetIds] = useState<string[]>([]);
  const [sourceFocus, setSourceFocus] = useState<{
    id: string;
    name: string;
  } | null>(null);
  const [targetFocus, setTargetFocus] = useState<{
    id: string;
    name: string;
  } | null>(null);
  const [hoverTarget, setHoverTarget] = useState<{
    side: "source" | "target";
    id: string;
  } | null>(null);
  const debouncedHover = useDebouncedValue(hoverTarget, 200);
  const sourceTreeRef = useRef<AsyncTreeHandle>(null);
  const targetTreeRef = useRef<AsyncTreeHandle>(null);

  const { data: sourceRootData, isPending: sourceRootPending } = useQuery(
    nodeBasicsQueryOptions(sourceNodeId),
  );
  const { data: targetRootData, isPending: targetRootPending } = useQuery(
    nodeBasicsQueryOptions(targetNodeId),
  );

  const { data: filteredData } = useQuery(
    filteredDependenciesQueryOptions(
      sourceNodeId,
      targetNodeId,
      selectedSourceIds,
      selectedTargetIds,
    ),
  );

  const depSet =
    filteredData?.hierarchicalGraph?.dependencySetForAggregatedDependency;
  const size = depSet?.size;
  const hasSelection =
    selectedSourceIds.length > 0 || selectedTargetIds.length > 0;
  const markedSourceIds = hasSelection
    ? (depSet?.filteredDependencies?.markedSourceIds ?? [])
    : [];
  const markedTargetIds = hasSelection
    ? (depSet?.filteredDependencies?.markedTargetIds ?? [])
    : [];
  // Leaf endpoints only (referenced/referencing types), for the status-line
  // count — excludes the ancestor containers carried in markedSource/TargetIds.
  const markedSourceLeafIds = hasSelection
    ? (depSet?.filteredDependencies?.markedSourceLeafIds ?? [])
    : [];
  const markedTargetLeafIds = hasSelection
    ? (depSet?.filteredDependencies?.markedTargetLeafIds ?? [])
    : [];

  const markedSourceKey = markedSourceIds.join(",");
  const markedTargetKey = markedTargetIds.join(",");

  // Filter only the counterpart of a one-sided selection; the selecting side
  // stays navigable. Guarding on !ownSelection avoids remounting a side that
  // holds its own selection (which would clear it and loop). Reads committed
  // marks, never the hover preview.
  const sourceHasSelection = selectedSourceIds.length > 0;
  const targetHasSelection = selectedTargetIds.length > 0;
  const targetFilterActive =
    filterCounterparts && sourceHasSelection && !targetHasSelection;
  const sourceFilterActive =
    filterCounterparts && targetHasSelection && !sourceHasSelection;

  // With auto-reveal on, a selection on one side expands the *other* tree so its
  // marked counterparts become visible without an extra click. Keyed on the
  // marked-id sets (not the hover preview) so it reacts to deliberate selection
  // changes only — the tree never jumps while hovering. revealMarked just opens
  // the ancestor folders of already-marked rows, so re-running it is idempotent.
  // Skip the side that is currently filtered — it already auto-expands all hit
  // paths, and it remounts on selection changes anyway.
  useEffect(() => {
    if (
      autoRevealCounterparts &&
      !targetFilterActive &&
      markedTargetIds.length > 0
    ) {
      targetTreeRef.current?.revealMarked();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoRevealCounterparts, targetFilterActive, markedTargetKey]);
  useEffect(() => {
    if (
      autoRevealCounterparts &&
      !sourceFilterActive &&
      markedSourceIds.length > 0
    ) {
      sourceTreeRef.current?.revealMarked();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoRevealCounterparts, sourceFilterActive, markedSourceKey]);

  const hoverSourceIds =
    debouncedHover?.side === "source" ? [debouncedHover.id] : [];
  const hoverTargetIds =
    debouncedHover?.side === "target" ? [debouncedHover.id] : [];

  const { data: hoverData } = useQuery({
    ...filteredDependenciesQueryOptions(
      sourceNodeId,
      targetNodeId,
      hoverSourceIds,
      hoverTargetIds,
    ),
    enabled: highlightOnHover && debouncedHover != null,
  });

  const hoverDepSet =
    hoverData?.hierarchicalGraph?.dependencySetForAggregatedDependency;
  const hoverActive =
    highlightOnHover &&
    debouncedHover != null &&
    !!hoverDepSet?.filteredDependencies;
  const displaySourceIds = hoverActive
    ? (hoverDepSet?.filteredDependencies?.markedSourceIds ?? [])
    : markedSourceIds;
  const displayTargetIds = hoverActive
    ? (hoverDepSet?.filteredDependencies?.markedTargetIds ?? [])
    : markedTargetIds;

  // Stable identities: the AsyncTree focus effect depends on this callback and
  // writes a fresh focus object, so an inline arrow would re-fire the effect on
  // every render and loop.
  const handleSourceFocus = useCallback(
    (id: string | null, name: string | null) =>
      setSourceFocus(id ? { id, name: name ?? "" } : null),
    [],
  );
  const handleTargetFocus = useCallback(
    (id: string | null, name: string | null) =>
      setTargetFocus(id ? { id, name: name ?? "" } : null),
    [],
  );

  const loadSourceChildren = useCallback(
    async (parentId: string) => {
      const result = await queryClient.ensureQueryData(
        filteredChildrenQueryOptions(
          sourceNodeId,
          targetNodeId,
          parentId,
          "SOURCE",
        ),
      );
      return (
        result.hierarchicalGraph?.dependencySetForAggregatedDependency
          ?.filteredChildren ?? []
      );
    },
    [queryClient, sourceNodeId, targetNodeId],
  );

  const loadTargetChildren = useCallback(
    async (parentId: string) => {
      const result = await queryClient.ensureQueryData(
        filteredChildrenQueryOptions(
          sourceNodeId,
          targetNodeId,
          parentId,
          "TARGET",
        ),
      );
      return (
        result.hierarchicalGraph?.dependencySetForAggregatedDependency
          ?.filteredChildren ?? []
      );
    },
    [queryClient, sourceNodeId, targetNodeId],
  );

  if (sourceRootPending || targetRootPending) {
    return (
      <div className="p-4">
        <Message variant="loading" title="Loading dependency details" />
      </div>
    );
  }

  const sourceRoot = sourceRootData?.hierarchicalGraph?.node ?? null;
  const targetRoot = targetRootData?.hierarchicalGraph?.node ?? null;

  if (!sourceRoot || !targetRoot) {
    return (
      <div className="p-4">
        <Message variant="error" title="Could not load dependency nodes" />
      </div>
    );
  }

  if (filteredData && (!depSet || size === 0)) {
    return (
      <div className="p-4">
        <Message variant="empty" title="No dependencies">
          No dependencies for this cell.
        </Message>
      </div>
    );
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
      <div className="grid min-h-0 flex-1 grid-cols-2 overflow-hidden">
        <div className="border-border min-w-0 overflow-auto border-r">
          <div className="border-border text-fg-subtle flex items-center border-b px-[14px] py-2 font-mono text-[11px]">
            <span className="min-w-0 truncate">
              Source ·{" "}
              <span className="text-fg-muted">
                {formatNodeLabel(sourceRoot.text, labelFormat, sourceRoot.type)}
              </span>{" "}
              — click a type to mark its counterparts
            </span>
          </div>
          <div className="p-1.5">
            <AsyncTree
              key={sourceFilterActive ? `filter:${markedSourceKey}` : "full"}
              ref={sourceTreeRef}
              rootNode={sourceRoot}
              loadChildren={loadSourceChildren}
              onSelectedIdsChange={setSelectedSourceIds}
              onFocusedIdChange={handleSourceFocus}
              onHoveredIdChange={
                highlightOnHover
                  ? (id) => setHoverTarget(id ? { side: "source", id } : null)
                  : undefined
              }
              // On the filtered side only matches are shown, so the amber row
              // highlight is redundant — suppress it there.
              markedIds={sourceFilterActive ? [] : displaySourceIds}
              label="DependencySourceTree"
              autoExpandRootChainOnLoad
              filterIds={sourceFilterActive ? markedSourceIds : undefined}
              settings={{
                ...DEFAULT_TREE_SETTINGS,
                labelFormat,
                autoExpandSingleChildren,
              }}
            />
          </div>
        </div>
        <div className="min-w-0 overflow-auto">
          <div className="border-border text-fg-subtle flex items-center border-b px-[14px] py-2 font-mono text-[11px]">
            <span className="min-w-0 truncate">
              Target ·{" "}
              <span className="text-fg-muted">
                {formatNodeLabel(targetRoot.text, labelFormat, targetRoot.type)}
              </span>{" "}
              — <span className="text-state-marked-fg">marked</span> =
              referenced by the source selection
            </span>
          </div>
          <div className="p-1.5">
            <AsyncTree
              key={targetFilterActive ? `filter:${markedTargetKey}` : "full"}
              ref={targetTreeRef}
              rootNode={targetRoot}
              loadChildren={loadTargetChildren}
              onSelectedIdsChange={setSelectedTargetIds}
              onFocusedIdChange={handleTargetFocus}
              onHoveredIdChange={
                highlightOnHover
                  ? (id) => setHoverTarget(id ? { side: "target", id } : null)
                  : undefined
              }
              markedIds={targetFilterActive ? [] : displayTargetIds}
              label="DependencyTargetTree"
              autoExpandRootChainOnLoad
              filterIds={targetFilterActive ? markedTargetIds : undefined}
              settings={{
                ...DEFAULT_TREE_SETTINGS,
                labelFormat,
                autoExpandSingleChildren,
              }}
            />
          </div>
        </div>
      </div>
      <StatusLine
        size={size ?? 0}
        selectedSourceIds={selectedSourceIds}
        selectedTargetIds={selectedTargetIds}
        markedSourceLeafCount={markedSourceLeafIds.length}
        markedTargetLeafCount={markedTargetLeafIds.length}
        sourceRootLabel={formatNodeLabel(
          sourceRoot.text,
          labelFormat,
          sourceRoot.type,
        )}
        targetRootLabel={formatNodeLabel(
          targetRoot.text,
          labelFormat,
          targetRoot.type,
        )}
        sourceName={sourceFocus?.name ?? null}
        targetName={targetFocus?.name ?? null}
        sourceFilterActive={sourceFilterActive}
        targetFilterActive={targetFilterActive}
      />
    </div>
  );
}

type StatusLineProps = {
  size: number;
  selectedSourceIds: string[];
  selectedTargetIds: string[];
  markedSourceLeafCount: number;
  markedTargetLeafCount: number;
  sourceRootLabel: string;
  targetRootLabel: string;
  sourceName: string | null;
  targetName: string | null;
  sourceFilterActive: boolean;
  targetFilterActive: boolean;
};

// Subtle one-line footer describing the committed selection (never hover):
// a bold short summary followed by a muted explanation.
function StatusLine({
  size,
  selectedSourceIds,
  selectedTargetIds,
  markedSourceLeafCount,
  markedTargetLeafCount,
  sourceRootLabel,
  targetRootLabel,
  sourceName,
  targetName,
  sourceFilterActive,
  targetFilterActive,
}: StatusLineProps) {
  const sourceSelected = selectedSourceIds.length > 0;
  const targetSelected = selectedTargetIds.length > 0;

  let summary: string;
  let detail: string;

  if (!sourceSelected && !targetSelected) {
    summary = `Showing all ${size} dependencies.`;
    detail = " Select a node to see what it references.";
  } else if (sourceSelected && targetSelected) {
    summary = "Filtered to the selected source and target nodes.";
    detail = "";
  } else if (sourceSelected) {
    const n = markedTargetLeafCount;
    const sel =
      selectedSourceIds.length === 1 && sourceName
        ? sourceName
        : `${selectedSourceIds.length} nodes`;
    summary = `${sel} → ${n} ${n === 1 ? "type" : "types"} in ${targetRootLabel}`;
    detail = ` · marked on the right${
      targetFilterActive ? " · right side filtered to matches" : ""
    }`;
  } else {
    const n = markedSourceLeafCount;
    const sel =
      selectedTargetIds.length === 1 && targetName
        ? targetName
        : `${selectedTargetIds.length} nodes`;
    summary = `${sel} ← ${n} ${n === 1 ? "type" : "types"} in ${sourceRootLabel}`;
    detail = ` · marked on the left${
      sourceFilterActive ? " · left side filtered to matches" : ""
    }`;
  }

  return (
    <div
      data-testid="locations-status"
      className="border-border text-fg-subtle flex shrink-0 items-center border-t px-3 py-1 font-mono text-[11px]"
    >
      <span className="min-w-0 truncate" title={`${summary}${detail}`}>
        <span className="text-fg-muted font-medium">{summary}</span>
        <span className="text-fg-subtle">{detail}</span>
      </span>
    </div>
  );
}
