import { useQuery } from "@tanstack/react-query";
import type { ElkNode } from "elkjs/lib/elk.bundled.js";
import { useEffect, useState } from "react";

import { Pane } from "@/design-system/layout/Pane";
import { Message } from "@/design-system/ui/message";
import { useSelection } from "@/selection/SelectionContext";

import { DependencyDiagramCanvas } from "./DependencyDiagramCanvas";
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

type MatrixViewProps = { matrix: MatrixData | undefined };

export function DependencyDiagram() {
  const { selectedIds } = useSelection();

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
    return <SingleNodeDiagram id={selectedIds[0]} />;
  }

  return <MultiNodeDiagram ids={selectedIds} />;
}

type SingleNodeDiagramProps = { id: string };
type MultiNodeDiagramProps = { ids: string[] };

function SingleNodeDiagram({ id }: SingleNodeDiagramProps) {
  const { data, isPending, isError } = useQuery(
    diagramNodeAdjacencyMatrixQueryOptions(id),
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

  const matrix = data.hierarchicalGraph?.node?.children?.orderedAdjacencyMatrix;
  return <MatrixView matrix={matrix} />;
}

function MultiNodeDiagram({ ids }: MultiNodeDiagramProps) {
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
  return <MatrixView matrix={matrix} />;
}

function MatrixView({ matrix }: MatrixViewProps) {
  const orderedNodes = matrix?.orderedNodes ?? [];
  const cells = matrix?.cells ?? [];

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
      <Pane title="Dependency Diagram">
        <Message variant="empty">No dependencies to display.</Message>
      </Pane>
    );
  }

  const rootNode = layout?.key === nodeKey ? layout.rootNode : null;

  if (!rootNode) {
    return (
      <Pane title="Dependency Diagram">
        <Message variant="loading">Computing layout…</Message>
      </Pane>
    );
  }

  return (
    <Pane title="Dependency Diagram" bodyClassName="p-0 overflow-hidden">
      <div className="h-full w-full overflow-hidden">
        <DependencyDiagramCanvas rootNode={rootNode} />
      </div>
    </Pane>
  );
}
