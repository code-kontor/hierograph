import type { ElkExtendedEdge, ElkNode } from "elkjs/lib/elk.bundled.js";

type Point = { x: number; y: number };

function distancePointToSegment(
  px: number,
  py: number,
  ax: number,
  ay: number,
  bx: number,
  by: number,
): number {
  const dx = bx - ax;
  const dy = by - ay;
  if (dx === 0 && dy === 0) {
    return Math.hypot(px - ax, py - ay);
  }
  const t = Math.max(
    0,
    Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)),
  );
  const closestX = ax + t * dx;
  const closestY = ay + t * dy;
  return Math.hypot(px - closestX, py - closestY);
}

function edgePolylines(edge: ElkExtendedEdge, nodeMap: Map<string, ElkNode>) {
  const polylines: Point[][] = [];
  for (const section of edge.sections ?? []) {
    if (!section.startPoint || !section.endPoint) {
      continue;
    }
    polylines.push([
      section.startPoint,
      ...(section.bendPoints ?? []),
      section.endPoint,
    ]);
  }
  if (polylines.length > 0) {
    return polylines;
  }

  const sourceNode = nodeMap.get(edge.sources?.[0] ?? "");
  const targetNode = nodeMap.get(edge.targets?.[0] ?? "");
  if (
    sourceNode?.x === undefined ||
    sourceNode.y === undefined ||
    sourceNode.width === undefined ||
    sourceNode.height === undefined ||
    targetNode?.x === undefined ||
    targetNode.y === undefined ||
    targetNode.width === undefined ||
    targetNode.height === undefined
  ) {
    return polylines;
  }
  polylines.push([
    {
      x: sourceNode.x + sourceNode.width / 2,
      y: sourceNode.y + sourceNode.height / 2,
    },
    {
      x: targetNode.x + targetNode.width / 2,
      y: targetNode.y + targetNode.height / 2,
    },
  ]);
  return polylines;
}

// Returns the top-level edge whose polyline is within toleranceWorld of the
// world-space point, or null. Iterating rootNode.edges only (flat top-level
// edges) is sufficient for the #0127 node-link layout; #0128 (in-place
// expand / compound edges) will need descent into child nodes' `.edges`.
export function hitTestEdge(
  rootNode: ElkNode,
  worldX: number,
  worldY: number,
  toleranceWorld: number,
): ElkExtendedEdge | null {
  const nodeMap = new Map<string, ElkNode>();
  for (const node of rootNode.children ?? []) {
    nodeMap.set(node.id, node);
  }

  let closestEdge: ElkExtendedEdge | null = null;
  let closestDist = Infinity;

  for (const edge of rootNode.edges ?? []) {
    for (const polyline of edgePolylines(edge, nodeMap)) {
      for (let i = 0; i < polyline.length - 1; i++) {
        const a = polyline[i];
        const b = polyline[i + 1];
        const dist = distancePointToSegment(worldX, worldY, a.x, a.y, b.x, b.y);
        if (dist < closestDist) {
          closestDist = dist;
          closestEdge = edge;
        }
      }
    }
  }

  return closestDist <= toleranceWorld ? closestEdge : null;
}
