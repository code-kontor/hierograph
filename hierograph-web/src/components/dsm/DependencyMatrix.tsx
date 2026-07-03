import { useQuery } from "@tanstack/react-query";

import { dsmQueryOptions } from "@/queries/dsm";

import { useSelection } from "../hierarchy/SelectionContext";
import { DsmCanvas } from "./DsmCanvas";

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

function DependencyMatrixInner({ id }: DependencyMatrixInnerProps) {
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

  return (
    <div className="overflow-auto">
      <DsmCanvas labels={orderedNodes} cells={cells} sccs={sccs} />
    </div>
  );
}
