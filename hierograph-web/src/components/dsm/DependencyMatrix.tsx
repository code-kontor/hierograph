import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import type { NodeAdjacencyMatrixQuery } from "@/generated/graphql/graphql";
import { dsmQueryOptions, nodesDsmQueryOptions } from "@/queries/dsm";

import { useSelection } from "../hierarchy/SelectionContext";
import { DsmCanvas, type DsmCellSelection } from "./DsmCanvas";

type MatrixData = NonNullable<
  NonNullable<NodeAdjacencyMatrixQuery["hierarchicalGraph"]>["node"]
>["children"]["orderedAdjacencyMatrix"];

export function DependencyMatrix() {
  const { selectedIds } = useSelection();

  if (selectedIds.length === 0) {
    return (
      <p className="text-muted-foreground text-sm">
        Select a node to view its dependency matrix.
      </p>
    );
  }

  if (selectedIds.length === 1) {
    return <SingleNodeMatrix id={selectedIds[0]} />;
  }

  return <MultiNodeMatrix ids={selectedIds} />;
}

type SingleNodeMatrixProps = { id: string };

type MultiNodeMatrixProps = { ids: string[] };

type MatrixViewProps = { matrix: MatrixData | undefined };

type DependencyStatusLineProps = { selection: DsmCellSelection | undefined };

function DependencyStatusLine({ selection }: DependencyStatusLineProps) {
  return (
    <p className="text-muted-foreground px-1 py-1 text-sm">
      {selection
        ? `${selection.sourceLabel.text} → ${selection.targetLabel.text} · weight ${selection.value}`
        : "Hover or select a cell to inspect a dependency."}
    </p>
  );
}

function SingleNodeMatrix({ id }: SingleNodeMatrixProps) {
  const { data, isPending, isError } = useQuery(dsmQueryOptions(id));

  if (isPending) {
    return (
      <p className="text-muted-foreground text-sm">
        Loading dependency matrix…
      </p>
    );
  }

  if (isError) {
    return (
      <p className="text-destructive text-sm">
        Could not load dependency matrix.
      </p>
    );
  }

  const matrix = data.hierarchicalGraph?.node?.children?.orderedAdjacencyMatrix;
  return <MatrixView matrix={matrix} />;
}

function MultiNodeMatrix({ ids }: MultiNodeMatrixProps) {
  const { data, isPending, isError } = useQuery(nodesDsmQueryOptions(ids));

  if (isPending) {
    return (
      <p className="text-muted-foreground text-sm">
        Loading dependency matrix…
      </p>
    );
  }

  if (isError) {
    return (
      <p className="text-destructive text-sm">
        Could not load dependency matrix.
      </p>
    );
  }

  const matrix = data.hierarchicalGraph?.nodes?.orderedAdjacencyMatrix;
  return <MatrixView matrix={matrix} />;
}

function MatrixView({ matrix }: MatrixViewProps) {
  const [hovered, setHovered] = useState<DsmCellSelection | undefined>(
    undefined,
  );
  const [selectedCell, setSelectedCell] = useState<
    DsmCellSelection | undefined
  >(undefined);

  const orderedNodes = matrix?.orderedNodes ?? [];

  if (orderedNodes.length === 0) {
    return (
      <p className="text-muted-foreground text-sm">
        No dependencies to display.
      </p>
    );
  }

  const cells = matrix?.cells ?? [];
  const sccs = matrix?.stronglyConnectedComponents ?? [];
  const display = hovered ?? selectedCell;

  return (
    <div className="flex h-full flex-col">
      <DependencyStatusLine selection={display} />
      <div className="overflow-auto">
        <DsmCanvas
          labels={orderedNodes}
          cells={cells}
          sccs={sccs}
          onHoverCell={setHovered}
          onSelectCell={setSelectedCell}
        />
      </div>
    </div>
  );
}
