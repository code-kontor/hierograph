import { useQuery } from "@tanstack/react-query";
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

import { DependencyDiagramCanvas } from "./DependencyDiagramCanvas";
import {
  LABEL_FORMAT_OPTIONS,
  LABEL_FORMAT_STORAGE_KEY,
  normalizeLabelFormat,
} from "./dependencyDiagramLabelSettings";
import { DiagramBreadcrumb, type DrillCrumb } from "./DiagramBreadcrumb";
import { layoutGraph } from "./elkLayout";
import { buildDependencyGraph } from "./graphModel";
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
  onNodeActivate: (id: string, label: string) => void;
  breadcrumb?: ReactNode;
};

export function DependencyDiagram() {
  const { selectedIds } = useSelection();

  // Scope interplay (AC5): the tree selection sets the *root* scope of the
  // diagram (level 0). Clicking a node in the diagram pushes a deeper scope
  // onto drillPath (level 1..n) — this is a one-way relationship: the drill
  // never writes back into the tree selection. Changing the tree selection
  // resets the drill, because the drilled node may not live under the new
  // selection.
  const [drillPath, setDrillPath] = useState<DrillCrumb[]>([]);

  const selectionKey = [...selectedIds].sort().join(",");
  // Reset the drill path during render when the tree selection changes,
  // instead of in an effect (React's "adjust state when a prop changes"
  // pattern) — avoids an extra render pass and the resulting setState call
  // is synchronous with this render, not a separate commit.
  const [resetKey, setResetKey] = useState(selectionKey);
  if (selectionKey !== resetKey) {
    setResetKey(selectionKey);
    setDrillPath([]);
  }

  function pushDrill(id: string, label: string) {
    setDrillPath((prev) => [...prev, { id, label }]);
  }
  function navigateDrill(index: number) {
    setDrillPath((prev) => (index < 0 ? [] : prev.slice(0, index + 1)));
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
  onNodeActivate: (id: string, label: string) => void;
};
type MultiNodeDiagramProps = {
  ids: string[];
  onNodeActivate: (id: string, label: string) => void;
};
type DrilledNodeDiagramProps = {
  id: string;
  onNodeActivate: (id: string, label: string) => void;
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
  const orderedNodes = matrix?.orderedNodes ?? [];
  const cells = matrix?.cells ?? [];

  const [storedFormat, setLabelFormat] = useLocalStorage<string>(
    LABEL_FORMAT_STORAGE_KEY,
    "last-segment",
  );
  const labelFormat = normalizeLabelFormat(storedFormat);

  const nodeKey = [...orderedNodes.map((n) => n.id)].sort().join(",");
  const [layout, setLayout] = useState<{
    key: string;
    rootNode: ElkNode;
  } | null>(null);

  useEffect(() => {
    let cancelled = false;

    const { nodes, edges } = buildDependencyGraph(orderedNodes, cells);
    layoutGraph(nodes, edges).then((rootNode) => {
      if (!cancelled) setLayout({ key: nodeKey, rootNode });
    });

    return () => {
      cancelled = true;
    };
    // nodeKey is the stable, memoization-friendly proxy for orderedNodes/cells.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodeKey]);

  if (orderedNodes.length === 0) {
    return (
      <Pane title="Dependency Diagram" subHeader={breadcrumb}>
        <Message variant="empty">No dependencies to display.</Message>
      </Pane>
    );
  }

  const rootNode = layout?.key === nodeKey ? layout.rootNode : null;

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
        <DependencyDiagramCanvas
          rootNode={rootNode}
          labelFormat={labelFormat}
          onNodeActivate={onNodeActivate}
          onEdgeActivate={(sourceNodeId, targetNodeId) =>
            setCellSelection({ sourceNodeId, targetNodeId })
          }
        />
      </div>
    </Pane>
  );
}
