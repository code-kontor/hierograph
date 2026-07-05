import { type DsmColors, resolveDsmColors } from "./colorScheme";
import { setupCanvas } from "./dpiFixer";
import { type DsmMarkerSizes, isLabelInCycle, SEP_SIZE } from "./dsmModel";

export type { DsmMarkerSizes };
export {
  BOX_SIZE,
  DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT,
  DEFAULT_VERTICAL_SIDE_MARKER_WIDTH,
  SEP_SIZE,
} from "./dsmModel";

const FONT = '12px "IBM Plex Mono", ui-monospace, monospace';
const TEXT_CLIP_PADDING = 5;

type Label = { id: string; text: string; type?: string };
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
  boxSize: number,
) {
  return {
    width: boxSize * itemCount + markerSizes.verticalSideMarkerWidth + 2,
    height: boxSize * itemCount + markerSizes.horizontalSideMarkerHeight + 2,
  };
}

function horizontalSliceSize(n: number, boxSize: number): number {
  return boxSize * n;
}

function verticalSliceSize(n: number, boxSize: number): number {
  return boxSize * n;
}

function drawVerticalBar(
  y: number,
  ctx: CanvasRenderingContext2D,
  labels: Label[],
  sccNodePositions: number[],
  markerSizes: DsmMarkerSizes,
  mark: boolean,
  formatLabel: (text: string, type?: string) => string,
  colors: DsmColors,
  markColor: string,
  boxSize: number,
): void {
  ctx.save();

  const { verticalSideMarkerWidth, horizontalSideMarkerHeight } = markerSizes;
  const inCycle = isLabelInCycle(y, sccNodePositions);

  // fill rect
  ctx.fillStyle = mark ? markColor : inCycle ? colors.cycle : colors.empty;
  ctx.fillRect(
    0,
    horizontalSideMarkerHeight + verticalSliceSize(y, boxSize),
    verticalSideMarkerWidth - SEP_SIZE,
    verticalSliceSize(y + 1, boxSize) - verticalSliceSize(y, boxSize),
  );

  // separators
  ctx.strokeStyle = colors.grid;
  ctx.beginPath();
  ctx.moveTo(0, horizontalSideMarkerHeight + verticalSliceSize(y, boxSize));
  ctx.lineTo(
    verticalSideMarkerWidth - SEP_SIZE,
    horizontalSideMarkerHeight + verticalSliceSize(y, boxSize),
  );
  ctx.moveTo(
    verticalSideMarkerWidth - SEP_SIZE,
    horizontalSideMarkerHeight + verticalSliceSize(y, boxSize),
  );
  ctx.lineTo(
    verticalSideMarkerWidth - SEP_SIZE,
    horizontalSideMarkerHeight + verticalSliceSize(y + 1, boxSize),
  );
  if (y === labels.length - 1) {
    ctx.moveTo(
      0,
      horizontalSideMarkerHeight + verticalSliceSize(labels.length, boxSize),
    );
    ctx.lineTo(
      verticalSideMarkerWidth - SEP_SIZE,
      horizontalSideMarkerHeight + verticalSliceSize(labels.length, boxSize),
    );
  }
  ctx.stroke();

  // clip before text
  ctx.beginPath();
  ctx.rect(
    0,
    horizontalSideMarkerHeight + verticalSliceSize(y, boxSize),
    verticalSideMarkerWidth - (SEP_SIZE + TEXT_CLIP_PADDING),
    verticalSliceSize(y + 1, boxSize) - verticalSliceSize(y, boxSize),
  );
  ctx.clip();

  // text
  ctx.fillStyle = colors.label;
  ctx.font = FONT;
  ctx.textAlign = "left";
  ctx.textBaseline = "middle";
  const maxWidth = verticalSideMarkerWidth - (SEP_SIZE + TEXT_CLIP_PADDING);
  ctx.fillText(
    fitLabel(ctx, formatLabel(labels[y].text, labels[y].type), maxWidth),
    (boxSize - 18) / 2,
    horizontalSideMarkerHeight + verticalSliceSize(y, boxSize) + boxSize / 2,
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
  formatLabel: (text: string, type?: string) => string,
  colors: DsmColors,
  markColor: string,
  boxSize: number,
): void {
  ctx.save();

  const { verticalSideMarkerWidth, horizontalSideMarkerHeight } = markerSizes;
  const inCycle = isLabelInCycle(x, sccNodePositions);

  // fill rect
  ctx.fillStyle = mark ? markColor : inCycle ? colors.cycle : colors.empty;
  ctx.fillRect(
    verticalSideMarkerWidth + horizontalSliceSize(x, boxSize),
    0,
    horizontalSliceSize(x + 1, boxSize) - horizontalSliceSize(x, boxSize),
    horizontalSideMarkerHeight - SEP_SIZE,
  );

  // separators
  ctx.strokeStyle = colors.grid;
  ctx.beginPath();
  ctx.moveTo(verticalSideMarkerWidth + horizontalSliceSize(x, boxSize), 0);
  ctx.lineTo(
    verticalSideMarkerWidth + horizontalSliceSize(x, boxSize),
    horizontalSideMarkerHeight - SEP_SIZE,
  );
  ctx.moveTo(
    verticalSideMarkerWidth + horizontalSliceSize(x, boxSize),
    horizontalSideMarkerHeight - SEP_SIZE,
  );
  ctx.lineTo(
    verticalSideMarkerWidth + horizontalSliceSize(x + 1, boxSize),
    horizontalSideMarkerHeight - SEP_SIZE,
  );
  if (x === labels.length - 1) {
    ctx.moveTo(
      verticalSideMarkerWidth + horizontalSliceSize(labels.length, boxSize),
      0,
    );
    ctx.lineTo(
      verticalSideMarkerWidth + horizontalSliceSize(labels.length, boxSize),
      horizontalSideMarkerHeight - SEP_SIZE,
    );
  }
  ctx.stroke();

  // clip before text
  ctx.beginPath();
  ctx.rect(
    verticalSideMarkerWidth + horizontalSliceSize(x, boxSize),
    0,
    horizontalSliceSize(x + 1, boxSize) - horizontalSliceSize(x, boxSize),
    horizontalSideMarkerHeight - (SEP_SIZE + TEXT_CLIP_PADDING),
  );
  ctx.clip();

  // rotated text
  ctx.translate(
    verticalSideMarkerWidth + horizontalSliceSize(x, boxSize) + boxSize / 2,
    10,
  );
  ctx.rotate(Math.PI / 2);
  ctx.fillStyle = colors.label;
  ctx.font = FONT;
  ctx.textAlign = "left";
  ctx.textBaseline = "middle";
  const maxWidth = horizontalSideMarkerHeight - (SEP_SIZE + TEXT_CLIP_PADDING);
  ctx.fillText(
    fitLabel(ctx, formatLabel(labels[x].text, labels[x].type), maxWidth),
    0,
    0,
  );

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
  showDiagonal: boolean,
  boxSize: number,
): void {
  const { verticalSideMarkerWidth, horizontalSideMarkerHeight } = markerSizes;

  // background (all non-cycle cells start neutral)
  ctx.fillStyle = colors.empty;
  ctx.fillRect(
    verticalSideMarkerWidth,
    horizontalSideMarkerHeight,
    horizontalSliceSize(labels.length, boxSize),
    verticalSliceSize(labels.length, boxSize),
  );

  // diagonal (self/inert)
  ctx.fillStyle = colors.diagonal;
  for (let index = 0; index < labels.length; index++) {
    ctx.fillRect(
      verticalSideMarkerWidth + horizontalSliceSize(index, boxSize),
      horizontalSideMarkerHeight + verticalSliceSize(index, boxSize),
      horizontalSliceSize(index + 1, boxSize) -
        horizontalSliceSize(index, boxSize),
      verticalSliceSize(index + 1, boxSize) - verticalSliceSize(index, boxSize),
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
            verticalSideMarkerWidth + horizontalSliceSize(i, boxSize),
            horizontalSideMarkerHeight + verticalSliceSize(j, boxSize),
            boxSize,
            boxSize,
          );
        }
      }
    }
  }

  // cell numbers (drawn after fills so they appear on top)
  ctx.fillStyle = colors.label;
  ctx.font = FONT;
  cells.forEach((cell) => {
    if (cell.value && (cell.row !== cell.column || showDiagonal)) {
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.fillText(
        String(cell.value),
        verticalSideMarkerWidth +
          horizontalSliceSize(cell.row, boxSize) +
          boxSize / 2,
        horizontalSideMarkerHeight +
          verticalSliceSize(cell.column, boxSize) +
          boxSize / 2,
      );
    }
  });

  // grid separators
  ctx.strokeStyle = colors.grid;
  ctx.beginPath();
  for (let index = 0; index <= labels.length; index++) {
    ctx.moveTo(
      verticalSideMarkerWidth,
      horizontalSideMarkerHeight + verticalSliceSize(index, boxSize),
    );
    ctx.lineTo(
      verticalSideMarkerWidth + boxSize * labels.length,
      horizontalSideMarkerHeight + verticalSliceSize(index, boxSize),
    );

    ctx.moveTo(
      verticalSideMarkerWidth + horizontalSliceSize(index, boxSize),
      horizontalSideMarkerHeight,
    );
    ctx.lineTo(
      verticalSideMarkerWidth + horizontalSliceSize(index, boxSize),
      horizontalSideMarkerHeight + boxSize * labels.length,
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
  boxSize: number,
): void {
  ctx.save();
  ctx.strokeStyle = colors.outline;
  ctx.lineWidth = 2;
  ctx.strokeRect(
    markerSizes.verticalSideMarkerWidth + boxSize * x + 1,
    markerSizes.horizontalSideMarkerHeight + boxSize * y + 1,
    boxSize - 2,
    boxSize - 2,
  );
  ctx.restore();
}

type DsmOverlayInput = {
  labels: Label[];
  sccs: Scc[];
  hover: { x: number; y: number } | null;
  headerHover: { axis: "row" | "col"; index: number } | null;
  selected: { x: number; y: number } | null;
};

export function drawDsmOverlay(
  canvas: HTMLCanvasElement,
  { labels, sccs, hover, headerHover, selected }: DsmOverlayInput,
  markerSizes: DsmMarkerSizes,
  formatLabel: (text: string, type?: string) => string,
  boxSize: number,
): void {
  const { width, height } = computeDsmCanvasSize(
    labels.length,
    markerSizes,
    boxSize,
  );
  canvas.width = width;
  canvas.height = height;

  const ctx = canvas.getContext("2d");
  if (!ctx) return;

  ctx.setTransform(1, 0, 0, 1, 0, 0);
  setupCanvas(canvas, ctx);

  ctx.clearRect(0, 0, canvas.width, canvas.height);

  const colors = resolveDsmColors();
  const sccNodePositions = sccs.flatMap((s) => s.nodePositions);

  // 1) Body-cell hover: quiet amber on BOTH axis headers + grey cell/transpose outline
  if (hover) {
    markCell(ctx, hover.x, hover.y, markerSizes, colors, boxSize);
    markCell(ctx, hover.y, hover.x, markerSizes, colors, boxSize);
    drawVerticalBar(
      hover.y,
      ctx,
      labels,
      sccNodePositions,
      markerSizes,
      true,
      formatLabel,
      colors,
      colors.markerHover,
      boxSize,
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
      colors.markerHover,
      boxSize,
    );
  }

  // 2) Header-cell hover: quiet amber on the ONE hovered title band (no cell outline)
  if (headerHover) {
    if (headerHover.axis === "row") {
      drawVerticalBar(
        headerHover.index,
        ctx,
        labels,
        sccNodePositions,
        markerSizes,
        true,
        formatLabel,
        colors,
        colors.markerHover,
        boxSize,
      );
    } else {
      drawHorizontalBar(
        headerHover.index,
        ctx,
        labels,
        sccNodePositions,
        markerSizes,
        true,
        formatLabel,
        colors,
        colors.markerHover,
        boxSize,
      );
    }
  }

  // 3) Selection LAST: louder amber wins any overlap with hover
  if (selected) {
    drawVerticalBar(
      selected.y,
      ctx,
      labels,
      sccNodePositions,
      markerSizes,
      true,
      formatLabel,
      colors,
      colors.marker,
      boxSize,
    );
    drawHorizontalBar(
      selected.x,
      ctx,
      labels,
      sccNodePositions,
      markerSizes,
      true,
      formatLabel,
      colors,
      colors.marker,
      boxSize,
    );
    markCell(ctx, selected.x, selected.y, markerSizes, colors, boxSize);
  }
}

export function drawDsm(
  canvas: HTMLCanvasElement,
  { labels, cells, sccs }: DsmData,
  markerSizes: DsmMarkerSizes,
  formatLabel: (text: string, type?: string) => string,
  showDiagonal: boolean,
  boxSize: number,
): void {
  const { width, height } = computeDsmCanvasSize(
    labels.length,
    markerSizes,
    boxSize,
  );

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
      colors.marker,
      boxSize,
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
      colors.marker,
      boxSize,
    );
  }
  drawMatrix(
    ctx,
    labels,
    cells,
    sccs,
    markerSizes,
    colors,
    showDiagonal,
    boxSize,
  );
}
