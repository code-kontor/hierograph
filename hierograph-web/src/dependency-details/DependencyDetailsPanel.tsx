import { useQuery } from "@tanstack/react-query";

import { Pane } from "@/design-system/layout/Pane";
import { Message } from "@/design-system/ui/message";
import { useLocalStorage } from "@/design-system/useLocalStorage";
import { formatNodeLabel, type NodeLabelFormat } from "@/graph/nodeLabel";
import { nodeBasicsQueryOptions, rootNodeQueryOptions } from "@/graph/queries";
import { useSelection } from "@/selection/SelectionContext";

import {
  LABEL_FORMAT_STORAGE_KEY,
  normalizeLabelFormat,
} from "./dependencyDetailsLabelSettings";
import { DependencyInspectorHeader } from "./DependencyInspectorHeader";
import { dependencyPartnersQueryOptions } from "./queries";

// New UI building blocks introduced in FT-1 (Ebene 1 of the two-tier
// Cross-Reference Details View), candidates for a later Claude Design
// refinement pass — FT-1 proves the interaction concept and content, not the
// final visual polish:
// 1. This panel container itself (a Pane without Tabs).
// 2. The aggregate metric bar (primary `size` count + "across M types" +
//    the container roll-up hint).
// 3. The partner row + its per-partner count badge (non-interactive,
//    same row metrics as the tree/table rows, --hl-badge-* tokens).

export type DependencyDetailsPanelProps = {
  emptyStateTitle?: string;
  emptyStateDescription?: string;
};

// One request with a generous cap covers all edges for fixture-sized
// anchors. If `pageInfo.maxPages > 1` the grouping below only covers this
// first page — full multi-page aggregation for very large anchors is
// deferred (FT-2 follow-up).
const PARTNER_PAGE_CAP = 1000;

type PartnerDirection = "used-by" | "uses";

type PartnerRowData = {
  id: string;
  text: string;
  type: string;
  count: number;
};

type PartnerRowProps = {
  row: PartnerRowData;
  labelFormat: NodeLabelFormat;
};

function PartnerRow({ row, labelFormat }: PartnerRowProps) {
  const label = formatNodeLabel(row.text, labelFormat, row.type);
  return (
    <div className="odd:bg-zebra flex min-w-0 items-center gap-2 px-[14px] py-2">
      <span className="min-w-0 flex-1 truncate font-mono text-[13.5px]">
        {label}
      </span>
      <span className="flex h-4 min-w-[17px] shrink-0 items-center justify-center rounded-[8px] border border-[var(--hl-badge-border)] bg-[var(--hl-badge-bg)] px-[5px] font-mono text-[10px] font-semibold text-[var(--hl-badge-fg)] tabular-nums">
        {row.count}
      </span>
    </div>
  );
}

export function DependencyDetailsPanel({
  emptyStateTitle = "No cell selected",
  emptyStateDescription = "Pick a dependency cell in the matrix to inspect its usages and paths.",
}: DependencyDetailsPanelProps = {}) {
  const { cellSelection } = useSelection();
  const [storedLabelFormat] = useLocalStorage<string>(
    LABEL_FORMAT_STORAGE_KEY,
    "full",
  );
  const labelFormat = normalizeLabelFormat(storedLabelFormat);

  // Header label mirrors the DSM Usages pane title styling (semibold mono
  // caps) so both "Dependencies Details" panes read identically. Uses a
  // titleBar rather than the Pane default `title` because the default renders
  // the label at normal weight.
  const headerTitleBar = (
    <div className="text-fg-muted flex items-center px-[14px] font-mono text-[11px] font-semibold tracking-[0.06em] uppercase">
      Dependencies Details
    </div>
  );

  const { data: rootData } = useQuery(rootNodeQueryOptions());
  const rootId = rootData?.hierarchicalGraph?.rootNode?.id;

  const selectionSourceId = cellSelection?.sourceNodeId;
  const selectionTargetId = cellSelection?.targetNodeId;

  // Anchor + direction, mirroring DependencyInspectorHeader: the root end of
  // the cell is the fixed anchor, the other end is the aggregated partner
  // set. Both-root, neither-root, or no selection at all leaves anchorId
  // undefined and falls through to Empty (a) below.
  let anchorId: string | undefined;
  let direction: PartnerDirection | undefined;
  if (
    rootId !== undefined &&
    selectionSourceId !== undefined &&
    selectionTargetId !== undefined
  ) {
    if (selectionSourceId === rootId && selectionTargetId !== rootId) {
      anchorId = selectionTargetId;
      direction = "used-by";
    } else if (selectionTargetId === rootId && selectionSourceId !== rootId) {
      anchorId = selectionSourceId;
      direction = "uses";
    }
  }

  // Hooks are always called (never behind an `if`); `enabled` gates the
  // actual fetch so hook order stays stable across selection changes.
  const { data: anchorData } = useQuery({
    ...nodeBasicsQueryOptions(anchorId ?? ""),
    enabled: anchorId !== undefined,
  });
  const anchorIsContainer =
    anchorData?.hierarchicalGraph?.node?.hasChildren === true;

  const {
    data: partnersData,
    isPending: partnersPending,
    isError: partnersError,
  } = useQuery({
    ...dependencyPartnersQueryOptions(
      selectionSourceId ?? "",
      selectionTargetId ?? "",
      1,
      PARTNER_PAGE_CAP,
    ),
    enabled: anchorId !== undefined,
  });

  if (
    anchorId === undefined ||
    direction === undefined ||
    !cellSelection ||
    selectionSourceId === undefined ||
    selectionTargetId === undefined
  ) {
    return (
      <Pane
        title="Dependencies Details"
        titleBar={headerTitleBar}
        bodyClassName="p-3"
      >
        <Message variant="empty" title={emptyStateTitle}>
          {emptyStateDescription}
        </Message>
      </Pane>
    );
  }

  const dependencySet =
    partnersData?.hierarchicalGraph?.dependencySetForAggregatedDependency;
  // metric = `size` = number of member pairs (not weight); equal to the
  // Ebene-2 row count. Weight intentionally omitted in FT-1 (candidate for a
  // subtle secondary annotation later).
  const size = dependencySet?.size ?? 0;
  const dependencies = dependencySet?.dependencyPage?.dependencies ?? [];

  // Granularity: type (leaf) — one row per distinct partner endpoint id.
  const partnerGroups = new Map<string, PartnerRowData>();
  for (const dep of dependencies) {
    const endpoint = direction === "used-by" ? dep.sourceNode : dep.targetNode;
    const existing = partnerGroups.get(endpoint.id);
    if (existing) {
      existing.count += 1;
    } else {
      partnerGroups.set(endpoint.id, {
        id: endpoint.id,
        text: endpoint.text,
        type: endpoint.type,
        count: 1,
      });
    }
  }
  const rows = [...partnerGroups.values()];

  return (
    <Pane
      title="Dependencies Details"
      titleBar={headerTitleBar}
      subHeader={
        <DependencyInspectorHeader
          sourceNodeId={selectionSourceId}
          targetNodeId={selectionTargetId}
          labelFormat={labelFormat}
        />
      }
      bodyClassName="flex flex-col p-0"
    >
      {partnersPending ? (
        <div className="p-4">
          <Message variant="loading" title="Loading dependencies" />
        </div>
      ) : partnersError ? (
        <div className="p-4">
          <Message variant="error" title="Failed to load dependencies" />
        </div>
      ) : size === 0 || rows.length === 0 ? (
        <div className="p-4">
          <Message variant="empty" title="No dependencies">
            No dependencies in this direction.
          </Message>
        </div>
      ) : (
        <div className="flex min-h-0 flex-1 flex-col">
          <div className="border-border flex shrink-0 items-center gap-2 border-b px-[14px] py-[9px]">
            <span className="text-fg font-mono text-[13.5px] font-semibold tabular-nums">
              {size}
            </span>
            <span className="text-fg-subtle font-mono text-[11px]">
              across {rows.length} {rows.length === 1 ? "type" : "types"}
            </span>
            {anchorIsContainer && (
              <span className="text-fg-subtle ml-auto truncate font-mono text-[11px]">
                aggregated
              </span>
            )}
          </div>
          <div
            data-testid="dependency-partners-list"
            className="flex min-h-0 flex-1 flex-col overflow-auto"
          >
            {rows.map((row) => (
              <PartnerRow key={row.id} row={row} labelFormat={labelFormat} />
            ))}
          </div>
        </div>
      )}
    </Pane>
  );
}
