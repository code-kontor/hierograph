import type {
  ElkExtendedEdge,
  ElkNode,
  ElkPoint,
} from "elkjs/lib/elk.bundled.js";

import type { GraphColors } from "./colorScheme";

const CORNER_RADIUS = 3;
const NODE_PADDING = 10;
const NODE_FONT = '12px "IBM Plex Mono", ui-monospace, monospace';
const EDGE_LABEL_FONT = '10px "IBM Plex Mono", ui-monospace, monospace';

export function roundRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number,
  fill?: string,
  stroke?: string,
): void {
  const right = x + w;
  const bottom = y + h;

  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.lineTo(right - r, y);
  ctx.quadraticCurveTo(right, y, right, y + r);
  ctx.lineTo(right, bottom - r);
  ctx.quadraticCurveTo(right, bottom, right - r, bottom);
  ctx.lineTo(x + r, bottom);
  ctx.quadraticCurveTo(x, bottom, x, bottom - r);
  ctx.lineTo(x, y + r);
  ctx.quadraticCurveTo(x, y, x + r, y);
  ctx.closePath();

  if (fill) {
    ctx.fillStyle = fill;
    ctx.fill();
  }
  if (stroke) {
    ctx.strokeStyle = stroke;
    ctx.stroke();
  }
}

export function drawNode(
  ctx: CanvasRenderingContext2D,
  node: ElkNode,
  colors: GraphColors,
): void {
  if (
    node.x === undefined ||
    node.y === undefined ||
    node.width === undefined ||
    node.height === undefined
  ) {
    return;
  }

  ctx.save();
  ctx.font = NODE_FONT;

  roundRect(
    ctx,
    node.x,
    node.y,
    node.width,
    node.height,
    CORNER_RADIUS,
    colors.nodeFill,
    colors.nodeBorder,
  );

  ctx.beginPath();
  ctx.rect(node.x, node.y, node.width - NODE_PADDING, node.height);
  ctx.clip();

  const label = node.labels?.[0]?.text ?? node.id;
  ctx.fillStyle = colors.nodeLabel;
  ctx.textAlign = "left";
  ctx.textBaseline = "middle";
  ctx.fillText(label, node.x + NODE_PADDING, node.y + node.height / 2);

  ctx.restore();
}

export function drawArrowhead(
  ctx: CanvasRenderingContext2D,
  from: ElkPoint,
  to: ElkPoint,
  radius: number,
): void {
  const xDelta = from.x - to.x;
  const yDelta = from.y - to.y;

  const xCenter = xDelta !== 0 ? (xDelta > 0 ? to.x + 5 : to.x - 5) : to.x;
  const yCenter = yDelta !== 0 ? (yDelta > 0 ? to.y + 5 : to.y - 5) : to.y;

  let angle = Math.atan2(to.y - from.y, to.x - from.x);

  ctx.beginPath();
  ctx.moveTo(
    radius * Math.cos(angle) + xCenter,
    radius * Math.sin(angle) + yCenter,
  );

  angle += (1 / 3) * (2 * Math.PI);
  ctx.lineTo(
    radius * Math.cos(angle) + xCenter,
    radius * Math.sin(angle) + yCenter,
  );

  angle += (1 / 3) * (2 * Math.PI);
  ctx.lineTo(
    radius * Math.cos(angle) + xCenter,
    radius * Math.sin(angle) + yCenter,
  );

  ctx.closePath();
  ctx.fill();
}

export function drawEdge(
  ctx: CanvasRenderingContext2D,
  edge: ElkExtendedEdge,
  colors: GraphColors,
): void {
  ctx.save();
  ctx.font = EDGE_LABEL_FONT;

  const label = edge.labels?.[0];
  if (
    label?.text !== undefined &&
    label.x !== undefined &&
    label.y !== undefined
  ) {
    ctx.fillStyle = colors.edgeLabel;
    ctx.fillText(label.text, label.x, label.y);
  }

  for (const section of edge.sections ?? []) {
    const backward = section.startPoint.y > section.endPoint.y;
    const strokeColor = backward ? colors.edgeBackward : colors.edge;
    ctx.strokeStyle = strokeColor;
    ctx.fillStyle = strokeColor;

    let lastPoint: ElkPoint = section.startPoint;
    const bendPoints = section.bendPoints ?? [];

    for (let i = 0; i < bendPoints.length; i++) {
      const currentPoint = bendPoints[i];
      const nextPoint =
        i < bendPoints.length - 1 ? bendPoints[i + 1] : section.endPoint;

      const lastDeltaX = currentPoint.x - lastPoint.x;
      const nextDeltaY = nextPoint.y - currentPoint.y;
      const nextDeltaX = nextPoint.x - currentPoint.x;

      ctx.beginPath();
      ctx.moveTo(lastPoint.x, lastPoint.y);
      const cornerFromPoint = { x: currentPoint.x, y: currentPoint.y };
      if (lastDeltaX !== 0) {
        ctx.lineTo(
          lastDeltaX > 0
            ? currentPoint.x - CORNER_RADIUS
            : currentPoint.x + CORNER_RADIUS,
          currentPoint.y,
        );
        const cornerY =
          nextDeltaY < 0
            ? currentPoint.y - CORNER_RADIUS
            : currentPoint.y + CORNER_RADIUS;
        ctx.quadraticCurveTo(
          currentPoint.x,
          currentPoint.y,
          currentPoint.x,
          cornerY,
        );
        cornerFromPoint.y = cornerY;
      } else {
        const lastDeltaY = currentPoint.y - lastPoint.y;
        if (lastDeltaY !== 0) {
          ctx.lineTo(
            currentPoint.x,
            lastDeltaY > 0
              ? currentPoint.y - CORNER_RADIUS
              : currentPoint.y + CORNER_RADIUS,
          );
          const cornerX =
            nextDeltaX < 0
              ? currentPoint.x - CORNER_RADIUS
              : currentPoint.x + CORNER_RADIUS;
          ctx.quadraticCurveTo(
            currentPoint.x,
            currentPoint.y,
            cornerX,
            currentPoint.y,
          );
          cornerFromPoint.x = cornerX;
        }
      }
      ctx.stroke();
      lastPoint = cornerFromPoint;
    }

    ctx.beginPath();
    ctx.moveTo(lastPoint.x, lastPoint.y);
    ctx.lineTo(section.endPoint.x, section.endPoint.y);
    ctx.stroke();

    drawArrowhead(ctx, lastPoint, section.endPoint, 4);
  }

  ctx.restore();
}

export function drawGraph(
  ctx: CanvasRenderingContext2D,
  rootNode: ElkNode,
  colors: GraphColors,
): void {
  for (const node of rootNode.children ?? []) {
    drawNode(ctx, node, colors);
  }
  for (const edge of rootNode.edges ?? []) {
    drawEdge(ctx, edge, colors);
  }
}
