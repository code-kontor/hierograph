import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { dsmQueryOptions } from "@/queries/dsm";

import { useSelection } from "../hierarchy/SelectionContext";
import { DsmCanvas, type DsmCellSelection } from "./DsmCanvas";

export function DependencyMatrix() {
  const { focusedId } = useSelection();

  if (focusedId == null) {
    return (
      <p className="text-muted-foreground text-sm">
        Select a node to view its dependency matrix.
      </p>
    );
  }

  return <DependencyMatrixInner id={focusedId} />;
}

type DependencyMatrixInnerProps = { id: string };

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

function DependencyMatrixInner({ id }: DependencyMatrixInnerProps) {
  const { data, isPending, isError } = useQuery(dsmQueryOptions(id));
  const [hovered, setHovered] = useState<DsmCellSelection | undefined>(
    undefined,
  );
  const [selectedCell, setSelectedCell] = useState<
    DsmCellSelection | undefined
  >(undefined);

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
