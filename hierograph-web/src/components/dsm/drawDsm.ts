import * as colors from "./colorScheme";
import { setupCanvas } from "./dpiFixer";

const FONT = "12px Arial";
const SEP_SIZE = 4;
const TEXT_CLIP_PADDING = 5;
const BOX_SIZE = 35;
const VERTICAL_SIDE_MARKER_WIDTH = 150;
// The slizaa default of 10 is too short for readable rotated labels;
// 150 gives enough room without clipping.
const HORIZONTAL_SIDE_MARKER_HEIGHT = 150;

type Label = { id: string; text: string };
type Cell = { row: number; column: number; value: number };
type Scc = { nodePositions: number[] };

export type DsmData = {
  labels: Label[];
  cells: Cell[];
  sccs: Scc[];
};

function horizontalSliceSize(n: number): number {
  return BOX_SIZE * n;
}

function verticalSliceSize(n: number): number {
  return BOX_SIZE * n;
}

function isLabelInCycle(index: number, sccNodePositions: number[]): boolean {
  return sccNodePositions.includes(index);
}

function drawVerticalBar(
  y: number,
  ctx: CanvasRenderingContext2D,
  labels: Label[],
  sccNodePositions: number[],
): void {
  ctx.save();

  const inCycle = isLabelInCycle(y, sccNodePositions);

  // step 1: fill rect
  ctx.fillStyle = inCycle
    ? colors.getCycleSideMarkerColor()
    : colors.getSideMarkerBackgroundColor();
  ctx.fillRect(
    0,
    HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(y),
    VERTICAL_SIDE_MARKER_WIDTH - SEP_SIZE,
    verticalSliceSize(y + 1) - verticalSliceSize(y),
  );

  // step 2: separators
  ctx.strokeStyle = inCycle
    ? colors.getCycleSideMarkerSeparatorColor()
    : colors.getSideMarkerSeparatorColor();
  ctx.beginPath();
  ctx.moveTo(0, HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(y));
  ctx.lineTo(
    VERTICAL_SIDE_MARKER_WIDTH - SEP_SIZE,
    HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(y),
  );
  ctx.moveTo(
    VERTICAL_SIDE_MARKER_WIDTH - SEP_SIZE,
    HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(y),
  );
  ctx.lineTo(
    VERTICAL_SIDE_MARKER_WIDTH - SEP_SIZE,
    HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(y + 1),
  );
  if (y === labels.length - 1) {
    ctx.moveTo(
      0,
      HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(labels.length),
    );
    ctx.lineTo(
      VERTICAL_SIDE_MARKER_WIDTH - SEP_SIZE,
      HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(labels.length),
    );
  }
  ctx.stroke();

  // clip before text
  ctx.beginPath();
  ctx.rect(
    0,
    HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(y),
    VERTICAL_SIDE_MARKER_WIDTH - (SEP_SIZE + TEXT_CLIP_PADDING),
    verticalSliceSize(y + 1) - verticalSliceSize(y),
  );
  ctx.clip();

  // step 3: text (no icons)
  ctx.fillStyle = colors.getSideMarkerTextColor();
  ctx.font = FONT;
  ctx.textAlign = "left";
  ctx.textBaseline = "middle";
  ctx.fillText(
    labels[y].text,
    (BOX_SIZE - 18) / 2,
    HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(y) + BOX_SIZE / 2,
  );

  ctx.restore();
}

function drawHorizontalBar(
  x: number,
  ctx: CanvasRenderingContext2D,
  labels: Label[],
  sccNodePositions: number[],
): void {
  ctx.save();

  const inCycle = isLabelInCycle(x, sccNodePositions);

  // step 1: fill rect
  ctx.fillStyle = inCycle
    ? colors.getCycleSideMarkerColor()
    : colors.getSideMarkerBackgroundColor();
  ctx.fillRect(
    VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(x),
    0,
    horizontalSliceSize(x + 1) - horizontalSliceSize(x),
    HORIZONTAL_SIDE_MARKER_HEIGHT - SEP_SIZE,
  );

  // step 2: separators
  ctx.strokeStyle = inCycle
    ? colors.getCycleSideMarkerSeparatorColor()
    : colors.getSideMarkerSeparatorColor();
  ctx.beginPath();
  ctx.moveTo(VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(x), 0);
  ctx.lineTo(
    VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(x),
    HORIZONTAL_SIDE_MARKER_HEIGHT - SEP_SIZE,
  );
  ctx.moveTo(
    VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(x),
    HORIZONTAL_SIDE_MARKER_HEIGHT - SEP_SIZE,
  );
  ctx.lineTo(
    VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(x + 1),
    HORIZONTAL_SIDE_MARKER_HEIGHT - SEP_SIZE,
  );
  if (x === labels.length - 1) {
    ctx.moveTo(
      VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(labels.length),
      0,
    );
    ctx.lineTo(
      VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(labels.length),
      HORIZONTAL_SIDE_MARKER_HEIGHT - SEP_SIZE,
    );
  }
  ctx.stroke();

  // clip before text
  ctx.beginPath();
  ctx.rect(
    VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(x),
    0,
    horizontalSliceSize(x + 1) - horizontalSliceSize(x),
    HORIZONTAL_SIDE_MARKER_HEIGHT - (SEP_SIZE + TEXT_CLIP_PADDING),
  );
  ctx.clip();

  // step 4: rotated text (no icons)
  ctx.translate(
    VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(x) + BOX_SIZE / 2,
    10,
  );
  ctx.rotate(Math.PI / 2);
  ctx.fillStyle = colors.getSideMarkerTextColor();
  ctx.font = FONT;
  ctx.textAlign = "left";
  ctx.textBaseline = "middle";
  ctx.fillText(labels[x].text, 0, 0);

  ctx.restore();
}

function drawMatrix(
  ctx: CanvasRenderingContext2D,
  labels: Label[],
  cells: Cell[],
  sccs: Scc[],
): void {
  // background
  ctx.fillStyle = colors.getMatrixBackgroundColor();
  ctx.fillRect(
    VERTICAL_SIDE_MARKER_WIDTH,
    HORIZONTAL_SIDE_MARKER_HEIGHT,
    horizontalSliceSize(labels.length),
    verticalSliceSize(labels.length),
  );

  // diagonal
  ctx.fillStyle = colors.getMatrixDiagonalColor();
  for (let index = 0; index < labels.length; index++) {
    ctx.fillRect(
      VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(index),
      HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(index),
      horizontalSliceSize(index + 1) - horizontalSliceSize(index),
      verticalSliceSize(index + 1) - verticalSliceSize(index),
    );
  }

  // strongly connected components
  ctx.fillStyle = colors.getCycleSideMarkerColor();
  sccs.forEach((cycle) => {
    const { nodePositions } = cycle;
    ctx.fillRect(
      VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(nodePositions[0]),
      HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(nodePositions[0]),
      horizontalSliceSize(nodePositions.length),
      verticalSliceSize(nodePositions.length),
    );

    ctx.fillStyle = colors.getCycleMatrixDiagonalColor();
    for (const position of nodePositions) {
      ctx.fillRect(
        VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(position),
        HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(position),
        horizontalSliceSize(position + 1) - horizontalSliceSize(position),
        verticalSliceSize(position + 1) - verticalSliceSize(position),
      );
    }
    // reset for next scc
    ctx.fillStyle = colors.getCycleSideMarkerColor();
  });

  // cell text — row=X (horizontal), column=Y (vertical)
  ctx.fillStyle = colors.getMatrixTextColor();
  ctx.font = FONT;
  cells.forEach((cell) => {
    if (cell.row !== cell.column && cell.value) {
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.fillText(
        String(cell.value),
        VERTICAL_SIDE_MARKER_WIDTH +
          horizontalSliceSize(cell.row) +
          BOX_SIZE / 2,
        HORIZONTAL_SIDE_MARKER_HEIGHT +
          verticalSliceSize(cell.column) +
          BOX_SIZE / 2,
      );
    }
  });

  // separator lines
  ctx.strokeStyle = colors.getMatrixSeparatorColor();
  ctx.beginPath();
  for (let index = 0; index <= labels.length; index++) {
    ctx.moveTo(
      VERTICAL_SIDE_MARKER_WIDTH,
      HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(index),
    );
    ctx.lineTo(
      VERTICAL_SIDE_MARKER_WIDTH + BOX_SIZE * labels.length,
      HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(index),
    );

    ctx.moveTo(
      VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(index),
      HORIZONTAL_SIDE_MARKER_HEIGHT,
    );
    ctx.lineTo(
      VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(index),
      HORIZONTAL_SIDE_MARKER_HEIGHT + BOX_SIZE * labels.length,
    );
  }
  ctx.stroke();

  // SCC separator lines
  ctx.strokeStyle = colors.getCycleSideMarkerSeparatorColor();
  ctx.beginPath();
  sccs.forEach((cycle) => {
    const { nodePositions } = cycle;
    for (let index = 1; index < nodePositions.length; index++) {
      ctx.moveTo(
        VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(nodePositions[index]),
        HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(nodePositions[0]),
      );
      ctx.lineTo(
        VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(nodePositions[index]),
        HORIZONTAL_SIDE_MARKER_HEIGHT +
          verticalSliceSize(nodePositions[nodePositions.length - 1] + 1),
      );

      ctx.moveTo(
        VERTICAL_SIDE_MARKER_WIDTH + horizontalSliceSize(nodePositions[0]),
        HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(nodePositions[index]),
      );
      ctx.lineTo(
        VERTICAL_SIDE_MARKER_WIDTH +
          horizontalSliceSize(nodePositions[nodePositions.length - 1] + 1),
        HORIZONTAL_SIDE_MARKER_HEIGHT + verticalSliceSize(nodePositions[index]),
      );
    }
    ctx.stroke();
  });

  // updateMarkedLayer() intentionally omitted — overlay is #0007
}

export function drawDsm(
  canvas: HTMLCanvasElement,
  { labels, cells, sccs }: DsmData,
): void {
  const itemCount = labels.length;
  const width = horizontalSliceSize(itemCount) + VERTICAL_SIDE_MARKER_WIDTH + 2;
  const height =
    verticalSliceSize(itemCount) + HORIZONTAL_SIDE_MARKER_HEIGHT + 2;

  canvas.width = width;
  canvas.height = height;

  const ctx = canvas.getContext("2d");
  if (!ctx) return;

  // Reset accumulated scale before re-applying HiDPI fix
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  setupCanvas(canvas, ctx);

  const sccNodePositions = sccs.flatMap((s) => s.nodePositions);

  for (let i = 0; i < labels.length; i++) {
    drawHorizontalBar(i, ctx, labels, sccNodePositions);
  }
  for (let i = 0; i < labels.length; i++) {
    drawVerticalBar(i, ctx, labels, sccNodePositions);
  }
  drawMatrix(ctx, labels, cells, sccs);
}
