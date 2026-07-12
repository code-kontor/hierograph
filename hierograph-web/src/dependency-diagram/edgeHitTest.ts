import type { ElkExtendedEdge, ElkNode } from "elkjs/lib/elk.bundled.js";

type Point = { x: number; y: number };

// A node's bounding box in accumulated world coordinates (used for the
// no-sections fallback polyline).
export type WorldNode = { x: number; y: number; width: number; height: number };

// An edge together with the world origin of the container that owns it. ELK
// gives edge section coordinates relative to that container, so its sections
// must be shifted by this offset before distance-testing against a world point.
export type EdgeAtOffset = {
  edge: ElkExtendedEdge;
  offsetX: number;
  offsetY: number;
};

// Walks the compound tree, accumulating each container's world origin. Fills
// nodeMap with every node's world-space box (for the fallback) and collects
// every edge tagged with the world origin of its owning container. The root's
// own edges use offset (0, 0).
function collectEdges(
  node: ElkNode,
  offsetX: number,
  offsetY: number,
  nodeMap: Map<string, WorldNode>,
  edges: EdgeAtOffset[],
): void {
  for (const child of node.children ?? []) {
    if (
      child.x !== undefined &&
      child.y !== undefined &&
      child.width !== undefined &&
      child.height !== undefined
    ) {
      nodeMap.set(child.id, {
        x: offsetX + child.x,
        y: offsetY + child.y,
        width: child.width,
        height: child.height,
      });
    }
    if (child.x !== undefined && child.y !== undefined) {
      collectEdges(child, offsetX + child.x, offsetY + child.y, nodeMap, edges);
    }
  }
  for (const edge of node.edges ?? []) {
    edges.push({ edge, offsetX, offsetY });
  }
}

// Walks the compound tree from its root and returns every node's world-space
// box together with every edge tagged with the world origin of its owning
// container. Shared by hit-testing (this module), edge straightening on node
// drag, and hover-toolbar positioning.
export function collectWorldGraph(root: ElkNode): {
  nodes: Map<string, WorldNode>;
  edges: EdgeAtOffset[];
} {
  const nodes = new Map<string, WorldNode>();
  const edges: EdgeAtOffset[] = [];
  collectEdges(root, 0, 0, nodes, edges);
  return { nodes, edges };
}

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

// Returns the edge's polylines in world coordinates: section points are shifted
// by the owning container's world origin; the fallback (no sections) uses the
// source/target boxes already stored in world coordinates in nodeMap.
function edgePolylines(
  edge: ElkExtendedEdge,
  nodeMap: Map<string, WorldNode>,
  offsetX: number,
  offsetY: number,
): Point[][] {
  const shift = (p: Point): Point => ({ x: p.x + offsetX, y: p.y + offsetY });
  const polylines: Point[][] = [];
  for (const section of edge.sections ?? []) {
    if (!section.startPoint || !section.endPoint) {
      continue;
    }
    polylines.push([
      shift(section.startPoint),
      ...(section.bendPoints ?? []).map(shift),
      shift(section.endPoint),
    ]);
  }
  if (polylines.length > 0) {
    return polylines;
  }

  const sourceNode = nodeMap.get(edge.sources?.[0] ?? "");
  const targetNode = nodeMap.get(edge.targets?.[0] ?? "");
  if (sourceNode === undefined || targetNode === undefined) {
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

// Returns the edge (at any nesting level) whose polyline is within
// toleranceWorld of the world-space point, or null. Edges of the root use
// offset (0, 0); edges of an expanded container are shifted by that container's
// world origin (never double-counted — collectEdges accumulates the offset
// once per descent). Closest-edge selection and tolerance semantics are
// unchanged from the flat layout.
export function hitTestEdge(
  rootNode: ElkNode,
  worldX: number,
  worldY: number,
  toleranceWorld: number,
): ElkExtendedEdge | null {
  const { nodes: nodeMap, edges } = collectWorldGraph(rootNode);

  let closestEdge: ElkExtendedEdge | null = null;
  let closestDist = Infinity;

  for (const { edge, offsetX, offsetY } of edges) {
    for (const polyline of edgePolylines(edge, nodeMap, offsetX, offsetY)) {
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
