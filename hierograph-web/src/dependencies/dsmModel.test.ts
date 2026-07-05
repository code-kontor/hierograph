import { describe, expect, it } from "vitest";

import nodeAdjacencyMatrixFixture from "@/testing/fixtures/NodeAdjacencyMatrix.json";

import {
  BOX_SIZE,
  buildCellSelection,
  buildMatrixElements,
  computeCellPosition,
  DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT,
  DEFAULT_VERTICAL_SIDE_MARKER_WIDTH,
  isCellInCycle,
} from "./dsmModel";

const DEFAULT_MARKER_SIZES = {
  verticalSideMarkerWidth: DEFAULT_VERTICAL_SIDE_MARKER_WIDTH,
  horizontalSideMarkerHeight: DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT,
};

const CELLS_3X3 = [
  { row: 0, column: 1, value: 5 },
  { row: 1, column: 2, value: 3 },
  { row: 2, column: 0, value: 1 },
];

const LABELS_3 = [
  { id: "a", text: "alpha" },
  { id: "b", text: "beta" },
  { id: "c", text: "gamma" },
];

describe("buildMatrixElements", () => {
  it("indexes cells by [column][row]", () => {
    const m = buildMatrixElements(CELLS_3X3);
    expect(m[1][0]).toEqual({ row: 0, column: 1, value: 5 });
    expect(m[2][1]).toEqual({ row: 1, column: 2, value: 3 });
    expect(m[0][2]).toEqual({ row: 2, column: 0, value: 1 });
  });

  it("returns empty structure for no cells", () => {
    expect(buildMatrixElements([])).toEqual([]);
  });
});

describe("computeCellPosition", () => {
  it("returns correct grid coords for a cell inside the matrix", () => {
    const x = DEFAULT_VERTICAL_SIDE_MARKER_WIDTH + BOX_SIZE * 1 + 5;
    const y = DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT + BOX_SIZE * 2 + 5;
    const result = computeCellPosition(x, y, DEFAULT_MARKER_SIZES, 3);
    expect(result).toEqual({ x: 1, y: 2 });
  });

  it("returns undefined x when offsetX is in the side marker area", () => {
    const x = DEFAULT_VERTICAL_SIDE_MARKER_WIDTH - 1;
    const y = DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT + BOX_SIZE * 0 + 5;
    const result = computeCellPosition(x, y, DEFAULT_MARKER_SIZES, 3);
    expect(result.x).toBeUndefined();
  });

  it("returns undefined y when offsetY is in the top marker area", () => {
    const x = DEFAULT_VERTICAL_SIDE_MARKER_WIDTH + BOX_SIZE * 0 + 5;
    const y = DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT - 1;
    const result = computeCellPosition(x, y, DEFAULT_MARKER_SIZES, 3);
    expect(result.y).toBeUndefined();
  });

  it("returns undefined x when offsetX is out of range (beyond last column)", () => {
    const x = DEFAULT_VERTICAL_SIDE_MARKER_WIDTH + BOX_SIZE * 3 + 5;
    const y = DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT + BOX_SIZE * 0 + 5;
    const result = computeCellPosition(x, y, DEFAULT_MARKER_SIZES, 3);
    expect(result.x).toBeUndefined();
  });
});

describe("buildCellSelection", () => {
  // In the DSM data model: cell.row is horizontal (X), cell.column is vertical (Y).
  // buildCellSelection(x, y) → source=labels[y]=labels[column], target=labels[x]=labels[row].
  // For { row: 0, column: 1, value: 5 }: stored at elements[1][0], accessed via x=0, y=1.
  it("returns correct selection for a valid cell", () => {
    const matrix = buildMatrixElements(CELLS_3X3);
    const sel = buildCellSelection(0, 1, LABELS_3, matrix);
    expect(sel).toEqual({
      sourceNodeId: "b",
      targetNodeId: "a",
      value: 5,
      sourceLabel: { id: "b", text: "beta" },
      targetLabel: { id: "a", text: "alpha" },
    });
  });

  it("returns value 0 for a cell not in the matrix", () => {
    const matrix = buildMatrixElements(CELLS_3X3);
    const sel = buildCellSelection(1, 1, LABELS_3, matrix);
    expect(sel?.value).toBe(0);
  });

  it("returns undefined when x is undefined", () => {
    const matrix = buildMatrixElements(CELLS_3X3);
    expect(buildCellSelection(undefined, 0, LABELS_3, matrix)).toBeUndefined();
  });

  it("returns undefined when y is undefined", () => {
    const matrix = buildMatrixElements(CELLS_3X3);
    expect(buildCellSelection(0, undefined, LABELS_3, matrix)).toBeUndefined();
  });
});

describe("isCellInCycle", () => {
  const sccs = [{ nodePositions: [0, 1, 2] }];

  it("returns true when both x and y are in the same SCC", () => {
    expect(isCellInCycle(0, 1, sccs)).toBe(true);
    expect(isCellInCycle(2, 0, sccs)).toBe(true);
  });

  it("returns false when no SCC contains both positions", () => {
    expect(isCellInCycle(0, 3, sccs)).toBe(false);
    expect(isCellInCycle(3, 0, sccs)).toBe(false);
    expect(isCellInCycle(0, 1, [])).toBe(false);
  });
});

// Fixture-based assertions — values from EXPECTED_VALUES.md

type AdjacencyMatrix = {
  orderedNodes: { id: string; text: string; type: string }[];
  cells: { row: number; column: number; value: number }[];
  stronglyConnectedComponents: { nodePositions: number[] }[];
};

function findMatrixByText(text: string): AdjacencyMatrix | undefined {
  for (const entry of nodeAdjacencyMatrixFixture.entries) {
    const matrix = (
      entry as {
        data: {
          hierarchicalGraph: {
            node: {
              children: { orderedAdjacencyMatrix: AdjacencyMatrix };
            } | null;
          } | null;
        };
      }
    ).data.hierarchicalGraph?.node?.children?.orderedAdjacencyMatrix;
    if (matrix?.orderedNodes.some((n) => n.text === text)) return matrix;
  }
  return undefined;
}

describe("fixture-based: rel.source → rel.target (value=9)", () => {
  it("buildMatrixElements returns value 9 for row=0 (source), col=1 (target)", () => {
    const matrix = findMatrixByText("org.hg.fixture.basic.rel.source");
    expect(matrix).toBeDefined();
    const m = buildMatrixElements(matrix!.cells);
    // cell stored at elements[column][row] = elements[1][0]
    expect(m[1]?.[0]?.value).toBe(9);
  });

  it("isCellInCycle returns false for rel pair (no SCCs)", () => {
    const matrix = findMatrixByText("org.hg.fixture.basic.rel.source");
    expect(isCellInCycle(0, 1, matrix!.stronglyConnectedComponents)).toBe(
      false,
    );
  });
});

describe("fixture-based: cycle.alpha/beta/gamma (SCC, all positions 0/1/2)", () => {
  it("isCellInCycle returns true for all cycle pairs", () => {
    const matrix = findMatrixByText("org.hg.fixture.basic.cycle.alpha");
    expect(matrix).toBeDefined();
    const sccs = matrix!.stronglyConnectedComponents;
    expect(isCellInCycle(0, 1, sccs)).toBe(true);
    expect(isCellInCycle(1, 2, sccs)).toBe(true);
    expect(isCellInCycle(2, 0, sccs)).toBe(true);
  });

  it("isCellInCycle returns false for a position outside the SCC", () => {
    const matrix = findMatrixByText("org.hg.fixture.basic.cycle.alpha");
    expect(isCellInCycle(0, 3, matrix!.stronglyConnectedComponents)).toBe(
      false,
    );
  });
});
