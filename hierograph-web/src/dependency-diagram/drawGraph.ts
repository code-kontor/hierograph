import type {
  ElkExtendedEdge,
  ElkNode,
  ElkPoint,
} from "elkjs/lib/elk.bundled.js";

import { formatNodeLabel, type NodeLabelFormat } from "@/graph/nodeLabel";

import type { GraphColors } from "./colorScheme";
import type { DiagramElkNode } from "./elkLayout";

const CORNER_RADIUS = 3;
const NODE_PADDING = 10;
// Height of the reserved top header band of a container, in which its own label
// is drawn (mirrors the top inset of CONTAINER_PADDING in elkLayout.ts).
const CONTAINER_HEADER_HEIGHT = 28;
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
  labelFormat: NodeLabelFormat,
  hovered?: boolean,
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

  const rawText = node.labels?.[0]?.text ?? node.id;
  const label = formatNodeLabel(
    rawText,
    labelFormat,
    (node as DiagramElkNode).nodeType,
  );
  ctx.fillStyle = colors.nodeLabel;
  ctx.textAlign = "left";
  ctx.textBaseline = "middle";
  ctx.fillText(label, node.x + NODE_PADDING, node.y + node.height / 2);

  ctx.restore();

  if (hovered) {
    ctx.save();
    ctx.lineWidth = 2;
    roundRect(
      ctx,
      node.x,
      node.y,
      node.width,
      node.height,
      CORNER_RADIUS,
      undefined,
      colors.nodeHoverBorder,
    );
    ctx.restore();
  }
}

// Draws an expanded container's box and its label as a top-aligned header (not
// vertically centered like a leaf). The container's children and internal edges
// are drawn separately by the recursive walk, in the container's translated
// coordinate space.
export function drawContainer(
  ctx: CanvasRenderingContext2D,
  node: ElkNode,
  colors: GraphColors,
  labelFormat: NodeLabelFormat,
  hovered?: boolean,
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
    colors.containerFill,
    colors.containerBorder,
  );

  ctx.beginPath();
  ctx.rect(node.x, node.y, node.width - NODE_PADDING, CONTAINER_HEADER_HEIGHT);
  ctx.clip();

  const rawText = node.labels?.[0]?.text ?? node.id;
  const label = formatNodeLabel(
    rawText,
    labelFormat,
    (node as DiagramElkNode).nodeType,
  );
  ctx.fillStyle = colors.containerHeaderLabel;
  ctx.textAlign = "left";
  ctx.textBaseline = "middle";
  ctx.fillText(
    label,
    node.x + NODE_PADDING,
    node.y + CONTAINER_HEADER_HEIGHT / 2,
  );

  ctx.restore();

  if (hovered) {
    ctx.save();
    ctx.lineWidth = 2;
    roundRect(
      ctx,
      node.x,
      node.y,
      node.width,
      node.height,
      CORNER_RADIUS,
      undefined,
      colors.nodeHoverBorder,
    );
    ctx.restore();
  }
}

// Distance the arrowhead centre sits back from the segment's end point, along
// the segment direction.
const ARROWHEAD_BACKOFF = 5;

export function drawArrowhead(
  ctx: CanvasRenderingContext2D,
  from: ElkPoint,
  to: ElkPoint,
  radius: number,
): void {
  let angle = Math.atan2(to.y - from.y, to.x - from.x);

  // Seat the arrowhead a fixed distance back from the end point *along the
  // segment* rather than by a fixed x/y offset. For an orthogonal segment this
  // reduces to the previous ±5 offset; for a diagonal segment (e.g. an edge
  // re-routed by a node drag) it keeps the head centred on the line instead of
  // pushing it sideways.
  const xCenter = to.x - ARROWHEAD_BACKOFF * Math.cos(angle);
  const yCenter = to.y - ARROWHEAD_BACKOFF * Math.sin(angle);

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
  highlighted?: boolean,
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
    const strokeColor = highlighted
      ? colors.nodeHoverBorder
      : backward
        ? colors.edgeBackward
        : colors.edge;
    ctx.strokeStyle = strokeColor;
    ctx.fillStyle = strokeColor;
    ctx.lineWidth = highlighted ? 2 : 1;

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

function isContainer(node: ElkNode): boolean {
  return (node.children?.length ?? 0) > 0;
}

// Recursively draws one container's contents in the current (already-translated)
// coordinate space. ELK gives child coordinates relative to their parent and an
// edge's section coordinates relative to the container that owns the edge, so
// every descent into a child pushes a matched translate. Per level, child boxes
// (and their subtrees) are drawn first, then this level's own edges on top —
// preserving the flat z-order (nodes below, edges above), so a tree with no
// containers renders exactly like the flat layout.
function drawSubtree(
  ctx: CanvasRenderingContext2D,
  node: ElkNode,
  colors: GraphColors,
  labelFormat: NodeLabelFormat,
  hoveredNodeId?: string,
  hoveredEdgeId?: string,
): void {
  for (const child of node.children ?? []) {
    const hovered = child.id === hoveredNodeId;
    if (isContainer(child)) {
      drawContainer(ctx, child, colors, labelFormat, hovered);
      if (child.x !== undefined && child.y !== undefined) {
        ctx.save();
        ctx.translate(child.x, child.y);
        drawSubtree(
          ctx,
          child,
          colors,
          labelFormat,
          hoveredNodeId,
          hoveredEdgeId,
        );
        ctx.restore();
      }
    } else {
      drawNode(ctx, child, colors, labelFormat, hovered);
    }
  }
  for (const edge of node.edges ?? []) {
    drawEdge(ctx, edge, colors, edge.id === hoveredEdgeId);
  }
}

export function drawGraph(
  ctx: CanvasRenderingContext2D,
  rootNode: ElkNode,
  colors: GraphColors,
  labelFormat: NodeLabelFormat,
  hoveredNodeId?: string,
  hoveredEdgeId?: string,
): void {
  // The root draws no box (it is the canvas): its children and its own edges are
  // drawn at offset 0, then each expanded child recurses in its own space.
  drawSubtree(ctx, rootNode, colors, labelFormat, hoveredNodeId, hoveredEdgeId);
}
