import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import type { ElkNode } from "elkjs/lib/elk.bundled.js";
import type { ReactNode } from "react";
import { useEffect, useState } from "react";

import { Pane } from "@/design-system/layout/Pane";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGhostTrigger,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
} from "@/design-system/ui/dropdown-menu";
import { Message } from "@/design-system/ui/message";
import { useLocalStorage } from "@/design-system/useLocalStorage";
import type { NodeLabelFormat } from "@/graph/nodeLabel";
import { useSelection } from "@/selection/SelectionContext";

import { buildCompoundElkGraph } from "./compoundModel";
import { DependencyDiagramCanvas } from "./DependencyDiagramCanvas";
import {
  LABEL_FORMAT_OPTIONS,
  LABEL_FORMAT_STORAGE_KEY,
  normalizeLabelFormat,
} from "./dependencyDiagramLabelSettings";
import { DiagramBreadcrumb, type DrillCrumb } from "./DiagramBreadcrumb";
import { layoutCompoundGraph } from "./elkLayout";
import { buildDependencyGraph, type DependencyGraph } from "./graphModel";
import {
  diagramNodeAdjacencyMatrixQueryOptions,
  diagramNodesAdjacencyMatrixQueryOptions,
} from "./queries";

type MatrixData = {
  orderedNodes: { id: string; text: string; type?: string }[];
  cells: { row: number; column: number; value: number }[];
};

type MatrixViewProps = {
  matrix: MatrixData | undefined;
  onNodeActivate: (id: string) => void;
  breadcrumb?: ReactNode;
};

// A stale/unresolved drill id (deep link to an id the store no longer has, or
// still in flight) falls back to a short id-based label so the breadcrumb
// never crashes or blanks out.
function fallbackDrillLabel(id: string): string {
  return `#${id}`;
}

export function DependencyDiagram() {
  const { selectedIds } = useSelection();
  const search = useSearch({ from: "/dependency-diagram" });
  const navigate = useNavigate({ from: "/dependency-diagram" });
  const drillIds = search.drill_ids ?? [];

  // Scope interplay (AC5): the tree selection sets the *root* scope of the
  // diagram (level 0). Clicking a node in the diagram pushes a deeper scope
  // onto `drill_ids` (level 1..n) — this is a one-way relationship: the drill
  // never writes back into the tree selection. `drill_ids` lives in the URL
  // (single source of truth); resetting it when the tree selection changes is
  // handled by `setSelectedIds` in `DependencyDiagramSelectionProvider`.

  // The URL only carries drill ids (E4) — breadcrumb labels are resolved from
  // node data so there is no duplicated, drifting label state.
  const { data: drillNodesData } = useQuery({
    ...diagramNodesAdjacencyMatrixQueryOptions(drillIds),
    enabled: drillIds.length > 0,
  });
  const drillLabelById = new Map(
    (drillNodesData?.hierarchicalGraph?.nodes?.nodes ?? []).map((node) => [
      node.id,
      node.text,
    ]),
  );
  const drillPath: DrillCrumb[] = drillIds.map((id) => ({
    id,
    label: drillLabelById.get(id) ?? fallbackDrillLabel(id),
  }));

  function pushDrill(id: string) {
    navigate({
      search: (prev) => ({
        ...prev,
        drill_ids: [...(prev.drill_ids ?? []), id],
        expanded_ids: undefined,
      }),
    });
  }
  function navigateDrill(index: number) {
    navigate({
      search: (prev) => ({
        ...prev,
        drill_ids: index < 0 ? undefined : prev.drill_ids?.slice(0, index + 1),
        expanded_ids: undefined,
      }),
    });
  }

  const rootLabel =
    selectedIds.length > 1 ? `Selection (${selectedIds.length})` : "Selection";
  const breadcrumb =
    drillPath.length > 0 ? (
      <DiagramBreadcrumb
        rootLabel={rootLabel}
        path={drillPath}
        onNavigate={navigateDrill}
      />
    ) : undefined;

  if (drillPath.length > 0) {
    return (
      <DrilledNodeDiagram
        id={drillPath[drillPath.length - 1].id}
        onNodeActivate={pushDrill}
        breadcrumb={breadcrumb}
      />
    );
  }

  if (selectedIds.length === 0) {
    return (
      <Pane title="Dependency Diagram">
        <Message variant="empty">
          Select a package node to view its dependency diagram.
        </Message>
      </Pane>
    );
  }

  if (selectedIds.length === 1) {
    return <SingleNodeDiagram id={selectedIds[0]} onNodeActivate={pushDrill} />;
  }

  return <MultiNodeDiagram ids={selectedIds} onNodeActivate={pushDrill} />;
}

type SingleNodeDiagramProps = {
  id: string;
  onNodeActivate: (id: string) => void;
};
type MultiNodeDiagramProps = {
  ids: string[];
  onNodeActivate: (id: string) => void;
};
type DrilledNodeDiagramProps = {
  id: string;
  onNodeActivate: (id: string) => void;
  breadcrumb?: ReactNode;
};

function SingleNodeDiagram({ id, onNodeActivate }: SingleNodeDiagramProps) {
  return (
    <DrilledNodeDiagram
      id={id}
      onNodeActivate={onNodeActivate}
      breadcrumb={undefined}
    />
  );
}

function DrilledNodeDiagram({
  id,
  onNodeActivate,
  breadcrumb,
}: DrilledNodeDiagramProps) {
  const { data, isPending, isError } = useQuery(
    diagramNodeAdjacencyMatrixQueryOptions(id),
  );

  if (isPending) {
    return (
      <Pane title="Dependency Diagram" subHeader={breadcrumb}>
        <Message variant="loading">Loading dependency diagram…</Message>
      </Pane>
    );
  }

  if (isError) {
    return (
      <Pane title="Dependency Diagram" subHeader={breadcrumb}>
        <Message variant="error">Could not load dependency diagram.</Message>
      </Pane>
    );
  }

  const matrix = data.hierarchicalGraph?.node?.children?.orderedAdjacencyMatrix;
  return (
    <MatrixView
      matrix={matrix}
      onNodeActivate={onNodeActivate}
      breadcrumb={breadcrumb}
    />
  );
}

function MultiNodeDiagram({ ids, onNodeActivate }: MultiNodeDiagramProps) {
  const { data, isPending, isError } = useQuery(
    diagramNodesAdjacencyMatrixQueryOptions(ids),
  );

  if (isPending) {
    return (
      <Pane title="Dependency Diagram">
        <Message variant="loading">Loading dependency diagram…</Message>
      </Pane>
    );
  }

  if (isError) {
    return (
      <Pane title="Dependency Diagram">
        <Message variant="error">Could not load dependency diagram.</Message>
      </Pane>
    );
  }

  const matrix = data.hierarchicalGraph?.nodes?.orderedAdjacencyMatrix;
  return <MatrixView matrix={matrix} onNodeActivate={onNodeActivate} />;
}

type DependencyDiagramOptionsMenuProps = {
  labelFormat: NodeLabelFormat;
  onLabelFormatChange: (value: NodeLabelFormat) => void;
};

export function DependencyDiagramOptionsMenu({
  labelFormat,
  onLabelFormatChange,
}: DependencyDiagramOptionsMenuProps) {
  return (
    <DropdownMenu>
      <DropdownMenuGhostTrigger title="Diagram options" />
      <DropdownMenuContent>
        <DropdownMenuLabel>Label format</DropdownMenuLabel>
        <DropdownMenuRadioGroup
          value={labelFormat}
          onValueChange={(v) => onLabelFormatChange(v as NodeLabelFormat)}
        >
          <DropdownMenuRadioItem
            value={LABEL_FORMAT_OPTIONS[0].value}
            onSelect={(e) => e.preventDefault()}
          >
            {LABEL_FORMAT_OPTIONS[0].label}
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem
            value={LABEL_FORMAT_OPTIONS[1].value}
            onSelect={(e) => e.preventDefault()}
          >
            {LABEL_FORMAT_OPTIONS[1].label}
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem
            value={LABEL_FORMAT_OPTIONS[2].value}
            onSelect={(e) => e.preventDefault()}
          >
            {LABEL_FORMAT_OPTIONS[2].label}
          </DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function MatrixView({ matrix, onNodeActivate, breadcrumb }: MatrixViewProps) {
  const { setCellSelection } = useSelection();
  const queryClient = useQueryClient();
  const search = useSearch({ from: "/dependency-diagram" });
  const navigate = useNavigate({ from: "/dependency-diagram" });
  const orderedNodes = matrix?.orderedNodes ?? [];
  const cells = matrix?.cells ?? [];

  const [storedFormat, setLabelFormat] = useLocalStorage<string>(
    LABEL_FORMAT_STORAGE_KEY,
    "last-segment",
  );
  const labelFormat = normalizeLabelFormat(storedFormat);

  const nodeKey = [...orderedNodes.map((n) => n.id)].sort().join(",");

  // Compound state (AC2/AC3): which nodes are expanded (URL-backed via
  // `expanded_ids` — restores on reload/deep-link), and the incrementally
  // merged cache of already-loaded child graphs (a pure fetch cache, not
  // user-visible state, so it stays local). Expanding never rebuilds the
  // whole diagram — the root matrix is never refetched and loaded subtrees are
  // never discarded; new children are merged into loadedChildren and ELK
  // re-lays out the (small) compound tree. react-query caches each child
  // matrix by id, so re-expanding an already-loaded node is instant.
  const expandedIds = search.expanded_ids ?? [];
  const expanded = new Set(expandedIds);
  const [loadedChildren, setLoadedChildren] = useState<
    Map<string, DependencyGraph>
  >(() => new Map());

  // The loaded-children cache is keyed per root node set (AC2): a
  // tree-selection or drill change swaps the root matrix (nodeKey changes),
  // and previously loaded children are stale under it — reset during render
  // (the "adjust state when a prop changes" pattern), not in an effect.
  // `expanded_ids` needs no reset here: a root change already clears it via
  // navigation (`setSelectedIds`/`pushDrill`/`navigateDrill`), so this cache
  // reset never collides with an active URL expand state.
  const [expandResetKey, setExpandResetKey] = useState(nodeKey);
  if (nodeKey !== expandResetKey) {
    setExpandResetKey(nodeKey);
    setLoadedChildren(new Map());
  }

  const expandedKey = [...expanded].sort().join(",");
  const loadedKey = [...loadedChildren.keys()].sort().join(",");
  // The layout must recompute whenever the root set, the expand set, or which
  // children are loaded changes.
  const layoutKey = `${nodeKey}|${expandedKey}|${loadedKey}`;

  const [layout, setLayout] = useState<{
    key: string;
    rootNode: ElkNode;
  } | null>(null);

  useEffect(() => {
    let cancelled = false;

    const rootGraph = buildDependencyGraph(orderedNodes, cells);
    const compound = buildCompoundElkGraph(rootGraph, loadedChildren, expanded);
    layoutCompoundGraph(compound).then((rootNode) => {
      if (!cancelled) setLayout({ key: layoutKey, rootNode });
    });

    return () => {
      cancelled = true;
    };
    // layoutKey folds in nodeKey (proxy for orderedNodes/cells) plus the expand
    // set and loaded-children ids — the only inputs to the compound layout.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [layoutKey]);

  // Backfill for cold-reload/deep-link: `expanded_ids` restored from the URL
  // may name nodes whose child graph was never fetched in this session (the
  // toggle-driven fetch in `handleToggleExpand` only runs on a live click).
  // Load every still-missing one so `buildCompoundElkGraph` can render it
  // expanded; until it arrives that node renders unexpanded, then gets pulled
  // in once loaded (the layout effect above reruns as `loadedKey` changes).
  useEffect(() => {
    let cancelled = false;
    const missingIds = expandedIds.filter((id) => !loadedChildren.has(id));
    if (missingIds.length === 0) return;

    Promise.all(
      missingIds.map(async (id) => {
        const data = await queryClient.fetchQuery(
          diagramNodeAdjacencyMatrixQueryOptions(id),
        );
        const childMatrix =
          data.hierarchicalGraph?.node?.children?.orderedAdjacencyMatrix;
        return [
          id,
          buildDependencyGraph(
            childMatrix?.orderedNodes ?? [],
            childMatrix?.cells ?? [],
          ),
        ] as const;
      }),
    ).then((entries) => {
      if (cancelled) return;
      setLoadedChildren((prev) => {
        const next = new Map(prev);
        for (const [id, graph] of entries) next.set(id, graph);
        return next;
      });
    });

    return () => {
      cancelled = true;
    };
    // expandedKey is the stable proxy for expandedIds' contents; loadedChildren
    // is intentionally excluded — it changes as a *result* of this effect, and
    // including it would just rerun the (harmless) missing-ids check.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expandedKey]);

  async function handleToggleExpand(id: string) {
    if (expanded.has(id)) {
      navigate({
        search: (prev) => {
          const nextIds = (prev.expanded_ids ?? []).filter(
            (existingId) => existingId !== id,
          );
          return {
            ...prev,
            expanded_ids: nextIds.length > 0 ? nextIds : undefined,
          };
        },
      });
      return;
    }

    if (!loadedChildren.has(id)) {
      const data = await queryClient.fetchQuery(
        diagramNodeAdjacencyMatrixQueryOptions(id),
      );
      const childMatrix =
        data.hierarchicalGraph?.node?.children?.orderedAdjacencyMatrix;
      const childGraph = buildDependencyGraph(
        childMatrix?.orderedNodes ?? [],
        childMatrix?.cells ?? [],
      );
      setLoadedChildren((prev) => {
        const next = new Map(prev);
        next.set(id, childGraph);
        return next;
      });
    }

    navigate({
      search: (prev) => ({
        ...prev,
        expanded_ids: [...(prev.expanded_ids ?? []), id],
      }),
    });
  }

  if (orderedNodes.length === 0) {
    return (
      <Pane title="Dependency Diagram" subHeader={breadcrumb}>
        <Message variant="empty">No dependencies to display.</Message>
      </Pane>
    );
  }

  const rootNode = layout?.key === layoutKey ? layout.rootNode : null;

  if (!rootNode) {
    return (
      <Pane title="Dependency Diagram" subHeader={breadcrumb}>
        <Message variant="loading">Computing layout…</Message>
      </Pane>
    );
  }

  return (
    <Pane
      title="Dependency Diagram"
      subHeader={breadcrumb}
      toolbar={
        <DependencyDiagramOptionsMenu
          labelFormat={labelFormat}
          onLabelFormatChange={setLabelFormat}
        />
      }
      bodyClassName="p-0 overflow-hidden"
    >
      <div className="h-full w-full overflow-hidden">
        {/* A new layout yields a new rootNode identity, so the canvas re-fits
            (E8). Preserving the viewport across an incremental expand is a
            possible follow-up, not part of this task. Single click toggles
            expand; double click drills (onNodeActivate = pushDrill), which
            unmounts this compound view. */}
        <DependencyDiagramCanvas
          rootNode={rootNode}
          labelFormat={labelFormat}
          onNodeActivate={onNodeActivate}
          onNodeToggleExpand={handleToggleExpand}
          onEdgeActivate={(sourceNodeId, targetNodeId) =>
            setCellSelection({ sourceNodeId, targetNodeId })
          }
        />
      </div>
    </Pane>
  );
}
