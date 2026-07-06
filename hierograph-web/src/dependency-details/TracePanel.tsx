import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { twMerge } from "tailwind-merge";

import { Message } from "@/design-system/ui/message";
import { formatNodeLabel, type NodeLabelFormat } from "@/graph/nodeLabel";
import { nodeBasicsQueryOptions } from "@/graph/queries";
import { AsyncTree, type AsyncTreeHandle } from "@/tree/AsyncTree";
import { DEFAULT_TREE_SETTINGS } from "@/tree/useTreeSettings";

import {
  filteredChildrenQueryOptions,
  filteredDependenciesQueryOptions,
} from "./queries";
import type {
  SerializeTraceInput,
  TraceSide,
  TraceViewMode,
} from "./serializeTrace";
import { TraceCopyButton } from "./TraceCopyButton";

export type TracePanelProps = {
  sourceNodeId: string;
  targetNodeId: string;
  labelFormat: NodeLabelFormat;
  autoExpandSingleChildren: boolean;
};

type Driver = { side: TraceSide; ids: string[] } | null;

function headerText(
  isCounterpart: boolean,
  driverLabel: string | null,
  rootLabel: string,
): string {
  if (isCounterpart && driverLabel) {
    return `Counterparts of ${driverLabel}`;
  }
  return `${rootLabel} — click a type to trace its counterparts`;
}

function buildStatusText(params: {
  driver: Driver;
  driverLabel: string | null;
  counterpartLeafCount: number;
  sourceRootLabel: string;
  targetRootLabel: string;
  viewMode: TraceViewMode;
}): string {
  const {
    driver,
    driverLabel,
    counterpartLeafCount,
    sourceRootLabel,
    targetRootLabel,
    viewMode,
  } = params;

  if (!driver) {
    return "Select a type to trace its counterparts.";
  }

  const label = driverLabel ?? `${driver.ids.length} nodes`;
  const suffix = viewMode === "hits-only" ? " · hits only" : "";
  const n = counterpartLeafCount;
  const noun = n === 1 ? "type" : "types";

  return driver.side === "source"
    ? `${label} → ${n} ${noun} in ${targetRootLabel}${suffix}`
    : `${label} ← ${n} ${noun} in ${sourceRootLabel}${suffix}`;
}

export function TracePanel({
  sourceNodeId,
  targetNodeId,
  labelFormat,
  autoExpandSingleChildren,
}: TracePanelProps) {
  const queryClient = useQueryClient();
  const [driver, setDriver] = useState<Driver>(null);
  const [viewMode, setViewMode] = useState<TraceViewMode>("in-context");
  const [sourceFocus, setSourceFocus] = useState<{
    id: string;
    name: string;
  } | null>(null);
  const [targetFocus, setTargetFocus] = useState<{
    id: string;
    name: string;
  } | null>(null);
  const sourceTreeRef = useRef<AsyncTreeHandle>(null);
  const targetTreeRef = useRef<AsyncTreeHandle>(null);

  const { data: sourceRootData, isPending: sourceRootPending } = useQuery(
    nodeBasicsQueryOptions(sourceNodeId),
  );
  const { data: targetRootData, isPending: targetRootPending } = useQuery(
    nodeBasicsQueryOptions(targetNodeId),
  );

  const selectedSourceIds = driver?.side === "source" ? driver.ids : [];
  const selectedTargetIds = driver?.side === "target" ? driver.ids : [];

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
  const hasSelection = driver != null;
  const markedSourceIds = hasSelection
    ? (depSet?.filteredDependencies?.markedSourceIds ?? [])
    : [];
  const markedTargetIds = hasSelection
    ? (depSet?.filteredDependencies?.markedTargetIds ?? [])
    : [];
  const markedSourceLeafIds = hasSelection
    ? (depSet?.filteredDependencies?.markedSourceLeafIds ?? [])
    : [];
  const markedTargetLeafIds = hasSelection
    ? (depSet?.filteredDependencies?.markedTargetLeafIds ?? [])
    : [];

  // Only the counterpart side of a driver is ever marked/filtered/counted —
  // the driver's own side stays plain and navigable.
  const sourceIsCounterpart = driver?.side === "target";
  const targetIsCounterpart = driver?.side === "source";
  const counterpartMarks = sourceIsCounterpart
    ? markedSourceIds
    : targetIsCounterpart
      ? markedTargetIds
      : [];
  const counterpartLeafCount = sourceIsCounterpart
    ? markedSourceLeafIds.length
    : targetIsCounterpart
      ? markedTargetLeafIds.length
      : 0;
  const counterpartMarksKey = counterpartMarks.join(",");

  // Ignore empty ids: the exclusivity effect below programmatically clears the
  // passive side, which fires onSelectedIdsChange([]) — without this guard that
  // would immediately reset the driver back to null (loop). A plain click in
  // AsyncTree is always single-select and never clears, so this guard only
  // affects the programmatic clearing path. Edge case accepted for v1: a
  // ctrl/meta-toggle-off of the only selected row leaves a stale driver.
  const handleSourceSelect = useCallback((ids: string[]) => {
    if (ids.length > 0) setDriver({ side: "source", ids });
  }, []);
  const handleTargetSelect = useCallback((ids: string[]) => {
    if (ids.length > 0) setDriver({ side: "target", ids });
  }, []);

  // Exactly one driver at a time: switching sides clears the previously active
  // tree's selection so the two panes never show a selection simultaneously.
  const driverIdsKey = driver?.ids.join(",");
  useEffect(() => {
    if (driver?.side === "source") targetTreeRef.current?.clearSelection();
    else if (driver?.side === "target") sourceTreeRef.current?.clearSelection();
  }, [driver?.side, driverIdsKey]);

  // Stable identities: an inline arrow would re-fire the AsyncTree focus effect
  // (and any effect depending on this callback) on every render and loop.
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

  const driverLabel = driver
    ? ((driver.side === "source" ? sourceFocus?.name : targetFocus?.name) ??
      `${driver.ids.length} nodes`)
    : null;

  // "In context": counterparts marked in the full tree, and revealed. "Hits
  // only": tree pruned to just the hit paths. The driver's own side is always
  // shown in full, unfiltered.
  const sourceMarkedIds =
    sourceIsCounterpart && viewMode === "in-context" ? counterpartMarks : [];
  const sourceFilterIds =
    sourceIsCounterpart && viewMode === "hits-only"
      ? counterpartMarks
      : undefined;
  const sourceKey =
    sourceIsCounterpart && viewMode === "hits-only"
      ? `filter:${counterpartMarksKey}`
      : "full";

  const targetMarkedIds =
    targetIsCounterpart && viewMode === "in-context" ? counterpartMarks : [];
  const targetFilterIds =
    targetIsCounterpart && viewMode === "hits-only"
      ? counterpartMarks
      : undefined;
  const targetKey =
    targetIsCounterpart && viewMode === "hits-only"
      ? `filter:${counterpartMarksKey}`
      : "full";

  useEffect(() => {
    if (viewMode !== "in-context" || counterpartMarks.length === 0) return;
    if (sourceIsCounterpart) sourceTreeRef.current?.revealMarked();
    else if (targetIsCounterpart) targetTreeRef.current?.revealMarked();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewMode, counterpartMarksKey, sourceIsCounterpart, targetIsCounterpart]);

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

  const sourceRootLabel = formatNodeLabel(
    sourceRoot.text,
    labelFormat,
    sourceRoot.type,
  );
  const targetRootLabel = formatNodeLabel(
    targetRoot.text,
    labelFormat,
    targetRoot.type,
  );

  const statusText = buildStatusText({
    driver,
    driverLabel,
    counterpartLeafCount,
    sourceRootLabel,
    targetRootLabel,
    viewMode,
  });

  // Reads the AsyncTree refs, so this must only run on click, never during
  // render (react-hooks/refs forbids reading ref.current while rendering).
  const buildSerializeInput = (): SerializeTraceInput => ({
    from: { id: sourceNodeId, label: sourceRootLabel },
    to: { id: targetNodeId, label: targetRootLabel },
    sourceRows: sourceTreeRef.current?.getVisibleNodes() ?? [],
    targetRows: targetTreeRef.current?.getVisibleNodes() ?? [],
    driver: driver && {
      side: driver.side,
      label: driverLabel ?? "",
      ids: driver.ids,
    },
    markedCounterpartIds: counterpartMarks,
    viewMode,
    statusText,
  });

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
      <div className="border-border flex shrink-0 items-center gap-2 border-b px-3 py-1.5">
        <button
          type="button"
          aria-pressed={viewMode === "hits-only"}
          title="Toggle between full context and hits only"
          onClick={() =>
            setViewMode(viewMode === "in-context" ? "hits-only" : "in-context")
          }
          className={twMerge(
            "rounded-[4px] px-2 py-1 font-mono text-[11px]",
            viewMode === "hits-only"
              ? "bg-state-hover text-fg"
              : "text-fg-subtle",
          )}
        >
          {viewMode === "in-context" ? "In context" : "Hits only"}
        </button>
        {import.meta.env.DEV && (
          <TraceCopyButton buildInput={buildSerializeInput} />
        )}
      </div>
      <div className="grid min-h-0 flex-1 grid-cols-2 overflow-hidden">
        <div className="border-border relative min-w-0 overflow-auto border-r">
          <div className="border-border text-fg-subtle flex items-center border-b px-[14px] py-2 font-mono text-[11px]">
            <span className="min-w-0 truncate">
              {headerText(sourceIsCounterpart, driverLabel, sourceRootLabel)}
            </span>
          </div>
          {driver?.side === "source" && (
            <div className="border-border bg-panel pointer-events-none absolute top-1.5 -right-2.5 z-10 flex size-5 items-center justify-center rounded-full border">
              <ArrowRight className="text-fg-subtle size-3" />
            </div>
          )}
          {driver?.side === "target" && (
            <div className="border-border bg-panel pointer-events-none absolute top-1.5 -right-2.5 z-10 flex size-5 items-center justify-center rounded-full border">
              <ArrowLeft className="text-fg-subtle size-3" />
            </div>
          )}
          <div className="p-1.5">
            <AsyncTree
              key={sourceKey}
              ref={sourceTreeRef}
              rootNode={sourceRoot}
              loadChildren={loadSourceChildren}
              onSelectedIdsChange={handleSourceSelect}
              onFocusedIdChange={handleSourceFocus}
              markedIds={sourceMarkedIds}
              label="TraceSourceTree"
              autoExpandRootChainOnLoad
              filterIds={sourceFilterIds}
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
              {headerText(targetIsCounterpart, driverLabel, targetRootLabel)}
            </span>
          </div>
          <div className="p-1.5">
            <AsyncTree
              key={targetKey}
              ref={targetTreeRef}
              rootNode={targetRoot}
              loadChildren={loadTargetChildren}
              onSelectedIdsChange={handleTargetSelect}
              onFocusedIdChange={handleTargetFocus}
              markedIds={targetMarkedIds}
              label="TraceTargetTree"
              autoExpandRootChainOnLoad
              filterIds={targetFilterIds}
              settings={{
                ...DEFAULT_TREE_SETTINGS,
                labelFormat,
                autoExpandSingleChildren,
              }}
            />
          </div>
        </div>
      </div>
      <TraceStatusLine statusText={statusText} />
    </div>
  );
}

type TraceStatusLineProps = { statusText: string };

function TraceStatusLine({ statusText }: TraceStatusLineProps) {
  return (
    <div
      data-testid="trace-status"
      className="border-border text-fg-subtle flex shrink-0 items-center border-t px-3 py-1 font-mono text-[11px]"
    >
      <span className="min-w-0 truncate" title={statusText}>
        {statusText}
      </span>
    </div>
  );
}
