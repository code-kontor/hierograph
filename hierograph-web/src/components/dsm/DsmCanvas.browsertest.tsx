import { describe, expect, it, vi } from "vitest";
import { userEvent } from "vitest/browser";

import {
  BOX_SIZE,
  DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT,
  DEFAULT_VERTICAL_SIDE_MARKER_WIDTH,
} from "@/components/dsm/dsmModel";
import nodeAdjacencyMatrixFixture from "@/testing/fixtures/NodeAdjacencyMatrix.json";
import { renderWithQueryClient } from "@/testing/render";

import { DsmCanvas } from "./DsmCanvas";

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

// The rel matrix has: orderedNodes=[source(row=0), target(col=1)], cells=[{row:0,col:1,value:9}]
// Canvas click cell {row:0, col:1} means clicking at:
//   x = DEFAULT_VERTICAL_SIDE_MARKER_WIDTH + BOX_SIZE * cell.row + BOX_SIZE/2  (col in canvas = row in data)
//   y = DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT + BOX_SIZE * cell.column + BOX_SIZE/2  (row in canvas = col in data)

describe("DsmCanvas — canvas click delivers correct cell selection", () => {
  it("fires onSelectCell with value=9 and correct node ids for rel.source→rel.target cell", async () => {
    const matrix = findMatrixByText("org.hg.fixture.basic.rel.source");
    expect(matrix).toBeDefined();

    const cell = matrix!.cells[0]; // {row:0, column:1, value:9}
    const labels = matrix!.orderedNodes.map((n) => ({
      id: n.id,
      text: n.text,
    }));

    const onSelectCell = vi.fn();
    const screen = await renderWithQueryClient(
      <DsmCanvas
        labels={labels}
        cells={matrix!.cells}
        sccs={matrix!.stronglyConnectedComponents}
        labelFormat="full"
        onSelectCell={onSelectCell}
      />,
    );

    const canvas = screen.getByTestId("dsm-overlay-canvas");

    // Cell centre in CSS pixels (headless Chromium: devicePixelRatio=1, setupCanvas is no-op)
    const clickX =
      DEFAULT_VERTICAL_SIDE_MARKER_WIDTH + BOX_SIZE * cell.row + BOX_SIZE / 2;
    const clickY =
      DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT +
      BOX_SIZE * cell.column +
      BOX_SIZE / 2;

    await userEvent.click(canvas, { position: { x: clickX, y: clickY } });

    expect(onSelectCell).toHaveBeenCalledOnce();
    const sel = onSelectCell.mock.calls[0][0];
    expect(sel?.value).toBe(9);
    // buildCellSelection: source=labels[y=column], target=labels[x=row]
    expect(sel?.sourceNodeId).toBe(labels[cell.column].id);
    expect(sel?.targetNodeId).toBe(labels[cell.row].id);
  });
});
