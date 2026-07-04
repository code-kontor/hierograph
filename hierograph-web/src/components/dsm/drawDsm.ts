import { type DsmColors, resolveDsmColors } from "./colorScheme";
import { setupCanvas } from "./dpiFixer";
import {
  BOX_SIZE,
  type DsmMarkerSizes,
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

const FONT = '12px "IBM Plex Mono", ui-monospace, monospace';
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
  colors: DsmColors,
): void {
  ctx.save();

  const { verticalSideMarkerWidth, horizontalSideMarkerHeight } = markerSizes;
  const inCycle = isLabelInCycle(y, sccNodePositions);

  // fill rect
  ctx.fillStyle = mark ? colors.marker : inCycle ? colors.cycle : colors.empty;
  ctx.fillRect(
    0,
    horizontalSideMarkerHeight + verticalSliceSize(y),
    verticalSideMarkerWidth - SEP_SIZE,
    verticalSliceSize(y + 1) - verticalSliceSize(y),
  );

  // separators
  ctx.strokeStyle = colors.grid;
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

  // text
  ctx.fillStyle = colors.label;
  ctx.font = FONT;
  ctx.textAlign = "left";
  ctx.textBaseline = "middle";
  const maxWidth = verticalSideMarkerWidth - (SEP_SIZE + TEXT_CLIP_PADDING);
  ctx.fillText(
    fitLabel(ctx, formatLabel(labels[y].text), maxWidth),
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
  colors: DsmColors,
): void {
  ctx.save();

  const { verticalSideMarkerWidth, horizontalSideMarkerHeight } = markerSizes;
  const inCycle = isLabelInCycle(x, sccNodePositions);

  // fill rect
  ctx.fillStyle = mark ? colors.marker : inCycle ? colors.cycle : colors.empty;
  ctx.fillRect(
    verticalSideMarkerWidth + horizontalSliceSize(x),
    0,
    horizontalSliceSize(x + 1) - horizontalSliceSize(x),
    horizontalSideMarkerHeight - SEP_SIZE,
  );

  // separators
  ctx.strokeStyle = colors.grid;
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

  // rotated text
  ctx.translate(
    verticalSideMarkerWidth + horizontalSliceSize(x) + BOX_SIZE / 2,
    10,
  );
  ctx.rotate(Math.PI / 2);
  ctx.fillStyle = colors.label;
  ctx.font = FONT;
  ctx.textAlign = "left";
  ctx.textBaseline = "middle";
  const maxWidth = horizontalSideMarkerHeight - (SEP_SIZE + TEXT_CLIP_PADDING);
  ctx.fillText(fitLabel(ctx, formatLabel(labels[x].text), maxWidth), 0, 0);

  ctx.restore();
}

function fitLabel(
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number,
): string {
  if (ctx.measureText(text).width <= maxWidth) return text;
  let lo = 0;
  let hi = text.length;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    if (ctx.measureText(text.slice(0, mid) + "…").width <= maxWidth) {
      lo = mid;
    } else {
      hi = mid - 1;
    }
  }
  return text.slice(0, lo) + "…";
}

function drawMatrix(
  ctx: CanvasRenderingContext2D,
  labels: Label[],
  cells: Cell[],
  sccs: Scc[],
  markerSizes: DsmMarkerSizes,
  colors: DsmColors,
): void {
  const { verticalSideMarkerWidth, horizontalSideMarkerHeight } = markerSizes;

  // background (all non-cycle cells start neutral)
  ctx.fillStyle = colors.empty;
  ctx.fillRect(
    verticalSideMarkerWidth,
    horizontalSideMarkerHeight,
    horizontalSliceSize(labels.length),
    verticalSliceSize(labels.length),
  );

  // diagonal (self/inert)
  ctx.fillStyle = colors.diagonal;
  for (let index = 0; index < labels.length; index++) {
    ctx.fillRect(
      verticalSideMarkerWidth + horizontalSliceSize(index),
      horizontalSideMarkerHeight + verticalSliceSize(index),
      horizontalSliceSize(index + 1) - horizontalSliceSize(index),
      verticalSliceSize(index + 1) - verticalSliceSize(index),
    );
  }

  // cycle cells: each pair (col=i, row=j) with i !== j in the same SCC
  ctx.fillStyle = colors.cycle;
  for (const scc of sccs) {
    const { nodePositions } = scc;
    for (const i of nodePositions) {
      for (const j of nodePositions) {
        if (i !== j) {
          ctx.fillRect(
            verticalSideMarkerWidth + horizontalSliceSize(i),
            horizontalSideMarkerHeight + verticalSliceSize(j),
            BOX_SIZE,
            BOX_SIZE,
          );
        }
      }
    }
  }

  // cell numbers (drawn after fills so they appear on top)
  ctx.fillStyle = colors.label;
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

  // grid separators
  ctx.strokeStyle = colors.grid;
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
}

function markCell(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  markerSizes: DsmMarkerSizes,
  colors: DsmColors,
): void {
  ctx.save();
  ctx.strokeStyle = colors.outline;
  ctx.lineWidth = 2;
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

  const colors = resolveDsmColors();

  if (hover) {
    const sccNodePositions = sccs.flatMap((s) => s.nodePositions);
    markCell(ctx, hover.x, hover.y, markerSizes, colors);
    markCell(ctx, hover.y, hover.x, markerSizes, colors);
    drawVerticalBar(
      hover.y,
      ctx,
      labels,
      sccNodePositions,
      markerSizes,
      true,
      formatLabel,
      colors,
    );
    drawHorizontalBar(
      hover.x,
      ctx,
      labels,
      sccNodePositions,
      markerSizes,
      true,
      formatLabel,
      colors,
    );
  }

  if (selected) {
    markCell(ctx, selected.x, selected.y, markerSizes, colors);
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

  ctx.setTransform(1, 0, 0, 1, 0, 0);
  setupCanvas(canvas, ctx);

  const colors = resolveDsmColors();
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
      colors,
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
      colors,
    );
  }
  drawMatrix(ctx, labels, cells, sccs, markerSizes, colors);
}
