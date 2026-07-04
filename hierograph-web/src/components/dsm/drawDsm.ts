import * as colors from "./colorScheme";
import { setupCanvas } from "./dpiFixer";
import {
  BOX_SIZE,
  type DsmMarkerSizes,
  isCellInCycle,
  isLabelInCycle,
  SEP_SIZE,
} from "./dsmModel";

export type { DsmMarkerSizes };
export {
  BOX_SIZE,
  DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT,
  DEFAULT_VERTICAL_SIDE_MARKER_WIDTH,
  SEP_SIZE,
} from "./dsmModel";

const FONT = "12px Arial";
const TEXT_CLIP_PADDING = 5;

type Label = { id: string; text: string };
type Cell = { row: number; column: number; value: number };
type Scc = { nodePositions: number[] };

export type DsmData = {
  labels: Label[];
  cells: Cell[];
  sccs: Scc[];
};

export function computeDsmCanvasSize(
  itemCount: number,
  markerSizes: DsmMarkerSizes,
) {
  return {
    width: BOX_SIZE * itemCount + markerSizes.verticalSideMarkerWidth + 2,
    height: BOX_SIZE * itemCount + markerSizes.horizontalSideMarkerHeight + 2,
  };
}

function horizontalSliceSize(n: number): number {
  return BOX_SIZE * n;
}

function verticalSliceSize(n: number): number {
  return BOX_SIZE * n;
}

function drawVerticalBar(
  y: number,
  ctx: CanvasRenderingContext2D,
  labels: Label[],
  sccNodePositions: number[],
  markerSizes: DsmMarkerSizes,
  mark: boolean,
  formatLabel: (text: string) => string,
): void {
  ctx.save();

  const { verticalSideMarkerWidth, horizontalSideMarkerHeight } = markerSizes;
  const inCycle = isLabelInCycle(y, sccNodePositions);

  // step 1: fill rect
  ctx.fillStyle = mark
    ? inCycle
      ? colors.getCycleSideMarkerMarkedColor()
      : colors.getSideMarkerMarkedColor()
    : inCycle
      ? colors.getCycleSideMarkerColor()
      : colors.getSideMarkerBackgroundColor();
  ctx.fillRect(
    0,
    horizontalSideMarkerHeight + verticalSliceSize(y),
    verticalSideMarkerWidth - SEP_SIZE,
    verticalSliceSize(y + 1) - verticalSliceSize(y),
  );

  // step 2: separators
  ctx.strokeStyle = inCycle
    ? colors.getCycleSideMarkerSeparatorColor()
    : colors.getSideMarkerSeparatorColor();
  ctx.beginPath();
  ctx.moveTo(0, horizontalSideMarkerHeight + verticalSliceSize(y));
  ctx.lineTo(
    verticalSideMarkerWidth - SEP_SIZE,
    horizontalSideMarkerHeight + verticalSliceSize(y),
  );
  ctx.moveTo(
    verticalSideMarkerWidth - SEP_SIZE,
    horizontalSideMarkerHeight + verticalSliceSize(y),
  );
  ctx.lineTo(
    verticalSideMarkerWidth - SEP_SIZE,
    horizontalSideMarkerHeight + verticalSliceSize(y + 1),
  );
  if (y === labels.length - 1) {
    ctx.moveTo(
      0,
      horizontalSideMarkerHeight + verticalSliceSize(labels.length),
    );
    ctx.lineTo(
      verticalSideMarkerWidth - SEP_SIZE,
      horizontalSideMarkerHeight + verticalSliceSize(labels.length),
    );
  }
  ctx.stroke();

  // clip before text
  ctx.beginPath();
  ctx.rect(
    0,
    horizontalSideMarkerHeight + verticalSliceSize(y),
    verticalSideMarkerWidth - (SEP_SIZE + TEXT_CLIP_PADDING),
    verticalSliceSize(y + 1) - verticalSliceSize(y),
  );
  ctx.clip();

  // step 3: text (no icons)
  ctx.fillStyle = colors.getSideMarkerTextColor();
  ctx.font = FONT;
  ctx.textAlign = "left";
  ctx.textBaseline = "middle";
  ctx.fillText(
    formatLabel(labels[y].text),
    (BOX_SIZE - 18) / 2,
    horizontalSideMarkerHeight + verticalSliceSize(y) + BOX_SIZE / 2,
  );

  ctx.restore();
}

function drawHorizontalBar(
  x: number,
  ctx: CanvasRenderingContext2D,
  labels: Label[],
  sccNodePositions: number[],
  markerSizes: DsmMarkerSizes,
  mark: boolean,
  formatLabel: (text: string) => string,
): void {
  ctx.save();

  const { verticalSideMarkerWidth, horizontalSideMarkerHeight } = markerSizes;
  const inCycle = isLabelInCycle(x, sccNodePositions);

  // step 1: fill rect
  ctx.fillStyle = mark
    ? inCycle
      ? colors.getCycleSideMarkerMarkedColor()
      : colors.getSideMarkerMarkedColor()
    : inCycle
      ? colors.getCycleSideMarkerColor()
      : colors.getSideMarkerBackgroundColor();
  ctx.fillRect(
    verticalSideMarkerWidth + horizontalSliceSize(x),
    0,
    horizontalSliceSize(x + 1) - horizontalSliceSize(x),
    horizontalSideMarkerHeight - SEP_SIZE,
  );

  // step 2: separators
  ctx.strokeStyle = inCycle
    ? colors.getCycleSideMarkerSeparatorColor()
    : colors.getSideMarkerSeparatorColor();
  ctx.beginPath();
  ctx.moveTo(verticalSideMarkerWidth + horizontalSliceSize(x), 0);
  ctx.lineTo(
    verticalSideMarkerWidth + horizontalSliceSize(x),
    horizontalSideMarkerHeight - SEP_SIZE,
  );
  ctx.moveTo(
    verticalSideMarkerWidth + horizontalSliceSize(x),
    horizontalSideMarkerHeight - SEP_SIZE,
  );
  ctx.lineTo(
    verticalSideMarkerWidth + horizontalSliceSize(x + 1),
    horizontalSideMarkerHeight - SEP_SIZE,
  );
  if (x === labels.length - 1) {
    ctx.moveTo(verticalSideMarkerWidth + horizontalSliceSize(labels.length), 0);
    ctx.lineTo(
      verticalSideMarkerWidth + horizontalSliceSize(labels.length),
      horizontalSideMarkerHeight - SEP_SIZE,
    );
  }
  ctx.stroke();

  // clip before text
  ctx.beginPath();
  ctx.rect(
    verticalSideMarkerWidth + horizontalSliceSize(x),
    0,
    horizontalSliceSize(x + 1) - horizontalSliceSize(x),
    horizontalSideMarkerHeight - (SEP_SIZE + TEXT_CLIP_PADDING),
  );
  ctx.clip();

  // step 4: rotated text (no icons)
  ctx.translate(
    verticalSideMarkerWidth + horizontalSliceSize(x) + BOX_SIZE / 2,
    10,
  );
  ctx.rotate(Math.PI / 2);
  ctx.fillStyle = colors.getSideMarkerTextColor();
  ctx.font = FONT;
  ctx.textAlign = "left";
  ctx.textBaseline = "middle";
  ctx.fillText(formatLabel(labels[x].text), 0, 0);

  ctx.restore();
}

function drawMatrix(
  ctx: CanvasRenderingContext2D,
  labels: Label[],
  cells: Cell[],
  sccs: Scc[],
  markerSizes: DsmMarkerSizes,
): void {
  const { verticalSideMarkerWidth, horizontalSideMarkerHeight } = markerSizes;

  // background
  ctx.fillStyle = colors.getMatrixBackgroundColor();
  ctx.fillRect(
    verticalSideMarkerWidth,
    horizontalSideMarkerHeight,
    horizontalSliceSize(labels.length),
    verticalSliceSize(labels.length),
  );

  // diagonal
  ctx.fillStyle = colors.getMatrixDiagonalColor();
  for (let index = 0; index < labels.length; index++) {
    ctx.fillRect(
      verticalSideMarkerWidth + horizontalSliceSize(index),
      horizontalSideMarkerHeight + verticalSliceSize(index),
      horizontalSliceSize(index + 1) - horizontalSliceSize(index),
      verticalSliceSize(index + 1) - verticalSliceSize(index),
    );
  }

  // strongly connected components
  ctx.fillStyle = colors.getCycleSideMarkerColor();
  sccs.forEach((cycle) => {
    const { nodePositions } = cycle;
    ctx.fillRect(
      verticalSideMarkerWidth + horizontalSliceSize(nodePositions[0]),
      horizontalSideMarkerHeight + verticalSliceSize(nodePositions[0]),
      horizontalSliceSize(nodePositions.length),
      verticalSliceSize(nodePositions.length),
    );

    ctx.fillStyle = colors.getCycleMatrixDiagonalColor();
    for (const position of nodePositions) {
      ctx.fillRect(
        verticalSideMarkerWidth + horizontalSliceSize(position),
        horizontalSideMarkerHeight + verticalSliceSize(position),
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
        verticalSideMarkerWidth + horizontalSliceSize(cell.row) + BOX_SIZE / 2,
        horizontalSideMarkerHeight +
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
      verticalSideMarkerWidth,
      horizontalSideMarkerHeight + verticalSliceSize(index),
    );
    ctx.lineTo(
      verticalSideMarkerWidth + BOX_SIZE * labels.length,
      horizontalSideMarkerHeight + verticalSliceSize(index),
    );

    ctx.moveTo(
      verticalSideMarkerWidth + horizontalSliceSize(index),
      horizontalSideMarkerHeight,
    );
    ctx.lineTo(
      verticalSideMarkerWidth + horizontalSliceSize(index),
      horizontalSideMarkerHeight + BOX_SIZE * labels.length,
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
        verticalSideMarkerWidth + horizontalSliceSize(nodePositions[index]),
        horizontalSideMarkerHeight + verticalSliceSize(nodePositions[0]),
      );
      ctx.lineTo(
        verticalSideMarkerWidth + horizontalSliceSize(nodePositions[index]),
        horizontalSideMarkerHeight +
          verticalSliceSize(nodePositions[nodePositions.length - 1] + 1),
      );

      ctx.moveTo(
        verticalSideMarkerWidth + horizontalSliceSize(nodePositions[0]),
        horizontalSideMarkerHeight + verticalSliceSize(nodePositions[index]),
      );
      ctx.lineTo(
        verticalSideMarkerWidth +
          horizontalSliceSize(nodePositions[nodePositions.length - 1] + 1),
        horizontalSideMarkerHeight + verticalSliceSize(nodePositions[index]),
      );
    }
    ctx.stroke();
  });
}

function markCell(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  selected: boolean,
  markerSizes: DsmMarkerSizes,
  sccs: Scc[],
): void {
  ctx.save();
  ctx.strokeStyle = selected
    ? colors.getSelectedCellColor()
    : isCellInCycle(x, y, sccs)
      ? colors.getCycleMatrixMarkedCellColor()
      : colors.getMatrixMarkedCellColor();
  ctx.lineWidth = 3;
  ctx.strokeRect(
    markerSizes.verticalSideMarkerWidth + BOX_SIZE * x + 1,
    markerSizes.horizontalSideMarkerHeight + BOX_SIZE * y + 1,
    BOX_SIZE - 2,
    BOX_SIZE - 2,
  );
  ctx.restore();
}

type DsmOverlayInput = {
  labels: Label[];
  sccs: Scc[];
  hover: { x: number; y: number } | null;
  selected: { x: number; y: number } | null;
};

export function drawDsmOverlay(
  canvas: HTMLCanvasElement,
  { labels, sccs, hover, selected }: DsmOverlayInput,
  markerSizes: DsmMarkerSizes,
  formatLabel: (text: string) => string,
): void {
  const { width, height } = computeDsmCanvasSize(labels.length, markerSizes);
  canvas.width = width;
  canvas.height = height;

  const ctx = canvas.getContext("2d");
  if (!ctx) return;

  ctx.setTransform(1, 0, 0, 1, 0, 0);
  setupCanvas(canvas, ctx);

  ctx.clearRect(0, 0, canvas.width, canvas.height);

  if (hover) {
    const sccNodePositions = sccs.flatMap((s) => s.nodePositions);
    markCell(ctx, hover.x, hover.y, false, markerSizes, sccs);
    markCell(ctx, hover.y, hover.x, false, markerSizes, sccs);
    drawVerticalBar(
      hover.y,
      ctx,
      labels,
      sccNodePositions,
      markerSizes,
      true,
      formatLabel,
    );
    drawHorizontalBar(
      hover.x,
      ctx,
      labels,
      sccNodePositions,
      markerSizes,
      true,
      formatLabel,
    );
  }

  if (selected) {
    markCell(ctx, selected.x, selected.y, true, markerSizes, sccs);
  }
}

export function drawDsm(
  canvas: HTMLCanvasElement,
  { labels, cells, sccs }: DsmData,
  markerSizes: DsmMarkerSizes,
  formatLabel: (text: string) => string,
): void {
  const { width, height } = computeDsmCanvasSize(labels.length, markerSizes);

  canvas.width = width;
  canvas.height = height;

  const ctx = canvas.getContext("2d");
  if (!ctx) return;

  // Reset accumulated scale before re-applying HiDPI fix
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  setupCanvas(canvas, ctx);

  const sccNodePositions = sccs.flatMap((s) => s.nodePositions);

  for (let i = 0; i < labels.length; i++) {
    drawHorizontalBar(
      i,
      ctx,
      labels,
      sccNodePositions,
      markerSizes,
      false,
      formatLabel,
    );
  }
  for (let i = 0; i < labels.length; i++) {
    drawVerticalBar(
      i,
      ctx,
      labels,
      sccNodePositions,
      markerSizes,
      false,
      formatLabel,
    );
  }
  drawMatrix(ctx, labels, cells, sccs, markerSizes);
}
