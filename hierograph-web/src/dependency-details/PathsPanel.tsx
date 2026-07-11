import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight } from "lucide-react";
import {
  type Ref,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from "react";
import { twMerge } from "tailwind-merge";

import { Message } from "@/design-system/ui/message";
import {
  formatNodeLabel,
  type NodeLabelFormat,
  nodeTypePrefix,
} from "@/graph/nodeLabel";
import { nodeBasicsQueryOptions } from "@/graph/queries";
import { AsyncTree, type AsyncTreeHandle } from "@/tree/AsyncTree";
import { DEFAULT_TREE_SETTINGS } from "@/tree/useTreeSettings";

import {
  filteredChildrenQueryOptions,
  filteredDependenciesQueryOptions,
} from "./queries";
import type {
  PathsSide,
  PathsViewMode,
  SerializePathsInput,
} from "./serializePaths";

export type PathsPanelProps = {
  sourceNodeId: string;
  targetNodeId: string;
  labelFormat: NodeLabelFormat;
  autoExpandSingleChildren: boolean;
  viewMode: PathsViewMode;
  // Reports whether a driver selection currently exists, so the pane can
  // enable/disable the Clear Selection control. Refs are not reactive, hence a
  // callback prop (consistent with onSelectedIdsChange).
  onSelectionChange?: (hasSelection: boolean) => void;
  ref?: Ref<PathsPanelHandle>;
};

export type PathsPanelHandle = {
  buildSerializeInput: () => SerializePathsInput;
  // Drop the driver and clear both trees' selections.
  clearSelection: () => void;
  // Expand/collapse both trees (source + target).
  expandAll: () => void;
  collapseAll: () => void;
};

type Driver = { side: PathsSide; ids: string[] } | null;

function headerText(
  isCounterpart: boolean,
  driverSide: PathsSide | null,
  driverLabel: string | null,
  rootLabel: string,
): string {
  if (isCounterpart && driverLabel) {
    return driverSide === "source"
      ? `Dependencies of ${driverLabel}`
      : `Dependents of ${driverLabel}`;
  }
  return `${rootLabel} — click a type to see its dependency paths`;
}

function headerTitle(
  isCounterpart: boolean,
  driverLabel: string | null,
): string | undefined {
  return isCounterpart && driverLabel
    ? `Types on this side connected to ${driverLabel} through the dependency path.`
    : undefined;
}

type PathsStatus = { summary: string; detail: string; title: string };

function buildStatusText(params: {
  driver: Driver;
  driverLabel: string | null;
  driverType: string | null;
  counterpartLeafCount: number;
  sourceRootLabel: string;
  targetRootLabel: string;
  viewMode: PathsViewMode;
}): PathsStatus {
  const {
    driver,
    driverLabel,
    driverType,
    counterpartLeafCount,
    sourceRootLabel,
    targetRootLabel,
    viewMode,
  } = params;

  if (!driver) {
    return {
      summary: "No type selected.",
      detail: " Select a type to see its dependency paths.",
      title: "Select a type on either side to see the types it connects to.",
    };
  }

  const multi = driver.ids.length > 1;
  const detail = viewMode === "hits-only" ? " · hits only" : "";
  const n = counterpartLeafCount;
  const noun = n === 1 ? "type" : "types";
  const hitsOnlyNote =
    viewMode === "hits-only"
      ? " Showing only matching types (tree pruned to hits)."
      : "";

  // Single select: introduce the driver with its type family; multi select:
  // "N nodes" (driverLabel) with no prefix.
  const prefix = multi ? "" : nodeTypePrefix(driverType ?? undefined);
  const subject = multi
    ? (driverLabel ?? `${driver.ids.length} nodes`)
    : `${prefix ? `${prefix} ` : ""}${driverLabel ?? `${driver.ids.length} nodes`}`;

  if (driver.side === "source") {
    const verb = multi ? "referencing" : "references";
    return {
      summary: `${subject} ${verb} ${n} ${noun} in ${targetRootLabel}`,
      detail,
      title: `${subject} ${verb} ${n} ${noun} in ${targetRootLabel}.${hitsOnlyNote}`,
    };
  }

  return {
    summary: `${subject} referenced by ${n} ${noun} in ${sourceRootLabel}`,
    detail,
    title: `${subject} referenced by ${n} ${noun} in ${sourceRootLabel}.${hitsOnlyNote}`,
  };
}

export function PathsPanel({
  sourceNodeId,
  targetNodeId,
  labelFormat,
  autoExpandSingleChildren,
  viewMode,
  onSelectionChange,
  ref,
}: PathsPanelProps) {
  const queryClient = useQueryClient();
  const [driver, setDriver] = useState<Driver>(null);
  const [sourceFocus, setSourceFocus] = useState<{
    id: string;
    name: string;
    type: string | null;
  } | null>(null);
  const [targetFocus, setTargetFocus] = useState<{
    id: string;
    name: string;
    type: string | null;
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

  // Single choke point for driver changes: sets the driver, clears the
  // opposite tree so the two panes never show a selection simultaneously, and
  // reports driver presence upward so the pane can enable/disable the Clear
  // Selection control (analogous to applySelectionChange in AsyncTree).
  const applyDriver = (next: Driver) => {
    setDriver(next);
    if (next?.side === "source") targetTreeRef.current?.clearSelection();
    else if (next?.side === "target") sourceTreeRef.current?.clearSelection();
    onSelectionChange?.(next != null);
  };

  // Ignore empty ids: applyDriver above programmatically clears the passive
  // side, which fires onSelectedIdsChange([]) — without this guard that would
  // immediately reset the driver back to null (loop). A plain click in
  // AsyncTree is always single-select and never clears, so this guard only
  // affects the programmatic clearing path. Edge case accepted for v1: a
  // ctrl/meta-toggle-off of the only selected row leaves a stale driver.
  const handleSourceSelect = (ids: string[]) => {
    if (ids.length > 0) applyDriver({ side: "source", ids });
  };
  const handleTargetSelect = (ids: string[]) => {
    if (ids.length > 0) applyDriver({ side: "target", ids });
  };

  const handleSourceFocus = (
    id: string | null,
    name: string | null,
    type: string | null,
  ) => setSourceFocus(id ? { id, name: name ?? "", type } : null);
  const handleTargetFocus = (
    id: string | null,
    name: string | null,
    type: string | null,
  ) => setTargetFocus(id ? { id, name: name ?? "", type } : null);

  const driverLabel = driver
    ? driver.ids.length > 1
      ? `${driver.ids.length} nodes`
      : ((driver.side === "source" ? sourceFocus?.name : targetFocus?.name) ??
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

  // Reveal the counterpart hits after the (possibly key-remounted) tree is in
  // place. In-context reveals the marked ancestors; hits-only prunes to just
  // the survivors, so expandAll opens exactly the hit paths. Gated on
  // counterpartMarksKey so it re-fires when the marks populate and the filter
  // side remounts — the internal mount-effect expand is redundant but this
  // imperative call is the reliable trigger (both are idempotent via Set dedupe).
  useEffect(() => {
    if (counterpartMarks.length === 0) return;
    if (viewMode === "in-context") {
      if (sourceIsCounterpart) sourceTreeRef.current?.revealMarked();
      else if (targetIsCounterpart) targetTreeRef.current?.revealMarked();
    } else {
      if (sourceIsCounterpart) sourceTreeRef.current?.expandAll();
      else if (targetIsCounterpart) targetTreeRef.current?.expandAll();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewMode, counterpartMarksKey, sourceIsCounterpart, targetIsCounterpart]);

  const loadSourceChildren = async (parentId: string) => {
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
  };

  const loadTargetChildren = async (parentId: string) => {
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
  };

  const sourceRoot = sourceRootData?.hierarchicalGraph?.node ?? null;
  const targetRoot = targetRootData?.hierarchicalGraph?.node ?? null;

  const sourceRootLabel = sourceRoot
    ? formatNodeLabel(sourceRoot.text, labelFormat, sourceRoot.type)
    : "";
  const targetRootLabel = targetRoot
    ? formatNodeLabel(targetRoot.text, labelFormat, targetRoot.type)
    : "";

  const driverType =
    driver?.side === "source"
      ? (sourceFocus?.type ?? null)
      : driver?.side === "target"
        ? (targetFocus?.type ?? null)
        : null;

  const pathsStatus = buildStatusText({
    driver,
    driverLabel,
    driverType,
    counterpartLeafCount,
    sourceRootLabel,
    targetRootLabel,
    viewMode,
  });

  useImperativeHandle(ref, () => ({
    // Reads the AsyncTree refs, so this must only run on click, never during
    // render (react-hooks/refs forbids reading ref.current while rendering).
    buildSerializeInput(): SerializePathsInput {
      return {
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
        statusText: `${pathsStatus.summary}${pathsStatus.detail}`,
      };
    },
    clearSelection() {
      // Drop the driver AND actively clear both trees — applyDriver(null)
      // has no tree-clear branch of its own, so both are cleared here.
      applyDriver(null);
      sourceTreeRef.current?.clearSelection();
      targetTreeRef.current?.clearSelection();
    },
    expandAll() {
      sourceTreeRef.current?.expandAll();
      targetTreeRef.current?.expandAll();
    },
    collapseAll() {
      sourceTreeRef.current?.collapseAll();
      targetTreeRef.current?.collapseAll();
    },
  }));

  if (sourceRootPending || targetRootPending) {
    return (
      <div className="p-4">
        <Message variant="loading" title="Loading dependency details" />
      </div>
    );
  }

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
    <div className="relative flex min-h-0 flex-1 flex-col overflow-hidden">
      <div className="grid min-h-0 flex-1 grid-cols-2 overflow-hidden">
        <div className="border-border relative min-w-0 overflow-auto border-r">
          <div className="border-border text-fg-subtle flex items-center border-b px-[14px] py-2 font-mono text-[11px]">
            <span
              className="min-w-0 truncate"
              title={headerTitle(sourceIsCounterpart, driverLabel)}
            >
              {headerText(
                sourceIsCounterpart,
                driver?.side ?? null,
                driverLabel,
                sourceRootLabel,
              )}
            </span>
          </div>
          <div className="p-1.5">
            <AsyncTree
              key={sourceKey}
              ref={sourceTreeRef}
              rootNode={sourceRoot}
              loadChildren={loadSourceChildren}
              onSelectedIdsChange={handleSourceSelect}
              onFocusedIdChange={handleSourceFocus}
              highlightedIds={sourceMarkedIds}
              label="PathsSourceTree"
              autoExpandOnLoad="root-chain"
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
            <span
              className="min-w-0 truncate"
              title={headerTitle(targetIsCounterpart, driverLabel)}
            >
              {headerText(
                targetIsCounterpart,
                driver?.side ?? null,
                driverLabel,
                targetRootLabel,
              )}
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
              highlightedIds={targetMarkedIds}
              label="PathsTargetTree"
              autoExpandOnLoad="root-chain"
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
      {driver && <DirectionBadge key={driver.side} side={driver.side} />}
      <PathsStatusLine status={pathsStatus} />
    </div>
  );
}

type DirectionBadgeProps = { side: PathsSide };

function DirectionBadge({ side }: DirectionBadgeProps) {
  const Icon = side === "source" ? ArrowRight : ArrowLeft;
  return (
    <div
      className={twMerge(
        "border-border bg-panel pointer-events-none absolute top-1.5 left-1/2 z-10 flex size-5 -translate-x-1/2 items-center justify-center rounded-full border",
        "motion-safe:animate-in motion-safe:zoom-in-50 motion-safe:spin-in-180 motion-safe:duration-200",
      )}
    >
      <Icon className="text-fg-subtle size-3" />
    </div>
  );
}

type PathsStatusLineProps = { status: PathsStatus };

function PathsStatusLine({ status }: PathsStatusLineProps) {
  return (
    <div
      data-testid="paths-status"
      className="border-border text-fg-subtle flex shrink-0 items-center border-t px-3 py-1 font-mono text-[11px]"
    >
      <span className="min-w-0 truncate" title={status.title}>
        <span className="text-fg-muted font-medium">{status.summary}</span>
        <span className="text-fg-subtle">{status.detail}</span>
      </span>
    </div>
  );
}
