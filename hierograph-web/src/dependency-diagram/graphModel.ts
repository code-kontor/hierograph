type OrderedNode = { id: string; text: string; type?: string };
type Cell = { row: number; column: number; value: number };

export type DependencyGraphNode = { id: string; text: string; type?: string };
export type DependencyGraphEdge = {
  id: string;
  sourceId: string;
  targetId: string;
  weight: number;
};

export type DependencyGraph = {
  nodes: DependencyGraphNode[];
  edges: DependencyGraphEdge[];
};

// Edge direction mirrors `buildCellSelection` (dsm/dsmModel.ts): a cell's
// row is the source (the dependent node), its column the target (the used
// node). The diagonal (row === column) is not a real dependency and is skipped.
export function buildDependencyGraph(
  orderedNodes: OrderedNode[],
  cells: Cell[],
): DependencyGraph {
  const nodes = orderedNodes.map(({ id, text, type }) => ({ id, text, type }));

  const edges: DependencyGraphEdge[] = [];
  for (const cell of cells) {
    if (cell.value <= 0 || cell.row === cell.column) continue;
    const source = orderedNodes[cell.row];
    const target = orderedNodes[cell.column];
    edges.push({
      id: `${cell.row}-${cell.column}`,
      sourceId: source.id,
      targetId: target.id,
      weight: cell.value,
    });
  }

  return { nodes, edges };
}
