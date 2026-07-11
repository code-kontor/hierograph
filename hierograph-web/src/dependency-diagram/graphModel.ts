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
// column is the source, its row the target. The diagonal (row === column) is
// not a real dependency and is skipped.
export function buildDependencyGraph(
  orderedNodes: OrderedNode[],
  cells: Cell[],
): DependencyGraph {
  const nodes = orderedNodes.map(({ id, text, type }) => ({ id, text, type }));

  const edges: DependencyGraphEdge[] = [];
  for (const cell of cells) {
    if (cell.value <= 0 || cell.row === cell.column) continue;
    const source = orderedNodes[cell.column];
    const target = orderedNodes[cell.row];
    edges.push({
      id: `${cell.column}-${cell.row}`,
      sourceId: source.id,
      targetId: target.id,
      weight: cell.value,
    });
  }

  return { nodes, edges };
}
