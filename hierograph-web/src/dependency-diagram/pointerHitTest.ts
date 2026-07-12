import type { ElkExtendedEdge, ElkNode } from "elkjs/lib/elk.bundled.js";

import { hitTestEdge } from "./edgeHitTest";
import { hitTestNode } from "./hitTest";

// What the pointer is over, once node and edge hits are reconciled.
export type PointerHit =
  { kind: "node"; node: ElkNode } | { kind: "edge"; edge: ElkExtendedEdge };

// Resolves the element under a world-space point, giving edges precedence over
// containers but never over leaf boxes. hitTestNode returns the enclosing
// container for any point in its interior — including the padding/routing space
// where the container's own edges are drawn — so without this precedence an edge
// nested inside an expanded container would always be shadowed by the container
// and never react to hover or click. A leaf box (no children) still wins over
// any edge under it, matching the flat top-level behaviour. Coordinates are ELK
// world coordinates; toleranceWorld is the edge hit distance in the same space.
export function resolvePointerHit(
  rootNode: ElkNode,
  worldX: number,
  worldY: number,
  toleranceWorld: number,
): PointerHit | null {
  const node = hitTestNode(rootNode, worldX, worldY);
  if (node && (node.children?.length ?? 0) === 0) {
    return { kind: "node", node };
  }
  const edge = hitTestEdge(rootNode, worldX, worldY, toleranceWorld);
  if (edge) {
    return { kind: "edge", edge };
  }
  if (node) {
    return { kind: "node", node };
  }
  return null;
}
