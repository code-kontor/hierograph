export const BOX_SIZE = 35;
export const SEP_SIZE = 4;
export const DEFAULT_VERTICAL_SIDE_MARKER_WIDTH = 150;
export const DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT = 150;

export type DsmMarkerSizes = {
  verticalSideMarkerWidth: number;
  horizontalSideMarkerHeight: number;
};

export type DsmCellSelection = {
  sourceNodeId: string;
  targetNodeId: string;
  value: number;
  sourceLabel: { id: string; text: string };
  targetLabel: { id: string; text: string };
};

type Cell = { row: number; column: number; value: number };
type Label = { id: string; text: string };
type Scc = { nodePositions: number[] };

export function buildMatrixElements(cells: Cell[]): Cell[][] {
  const elements: Cell[][] = [];
  for (const cell of cells) {
    if (!elements[cell.column]) {
      elements[cell.column] = [];
    }
    elements[cell.column][cell.row] = cell;
  }
  return elements;
}

export function computeCellPosition(
  offsetX: number,
  offsetY: number,
  markerSizes: DsmMarkerSizes,
  labelCount: number,
): { x: number | undefined; y: number | undefined } {
  let x: number | undefined = Math.floor(
    (offsetX - markerSizes.verticalSideMarkerWidth) / BOX_SIZE,
  );
  let y: number | undefined = Math.floor(
    (offsetY - markerSizes.horizontalSideMarkerHeight) / BOX_SIZE,
  );
  if (x < 0 || x >= labelCount) x = undefined;
  if (y < 0 || y >= labelCount) y = undefined;
  return { x, y };
}

export function buildCellSelection(
  x: number | undefined,
  y: number | undefined,
  labels: Label[],
  matrixElements: Cell[][],
): DsmCellSelection | undefined {
  if (x === undefined || y === undefined) return undefined;
  return {
    sourceNodeId: labels[y].id,
    targetNodeId: labels[x].id,
    value: matrixElements[y]?.[x]?.value ?? 0,
    sourceLabel: { id: labels[y].id, text: labels[y].text },
    targetLabel: { id: labels[x].id, text: labels[x].text },
  };
}

export function isLabelInCycle(
  index: number,
  sccNodePositions: number[],
): boolean {
  return sccNodePositions.includes(index);
}

export function isCellInCycle(x: number, y: number, sccs: Scc[]): boolean {
  return sccs.some(
    (scc) => scc.nodePositions.includes(x) && scc.nodePositions.includes(y),
  );
}
