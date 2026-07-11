import type { ElkNode } from "elkjs/lib/elk.bundled.js";

// Returns the deepest nested node whose bounding box contains the point, or
// null. Coordinates are ELK world coordinates (same space as
// node.x/y/width/height), so callers must convert screen -> world first (see
// viewport.ts screenToWorld). Child coordinates are parent-relative, so on
// descent the point is translated by the container origin (the same transform
// stack as drawGraph). Innermost hit wins: a hit on a nested child takes
// precedence over the enclosing container; a hit in a container's header band
// (outside any child) returns the container itself. The flat top-level case
// (leaves only) is unchanged.
export function hitTestNode(
  rootNode: ElkNode,
  worldX: number,
  worldY: number,
): ElkNode | null {
  for (const node of rootNode.children ?? []) {
    const { x, y, width, height } = node;
    if (
      x === undefined ||
      y === undefined ||
      width === undefined ||
      height === undefined
    ) {
      continue;
    }
    if (
      worldX >= x &&
      worldX <= x + width &&
      worldY >= y &&
      worldY <= y + height
    ) {
      const deeper = hitTestNode(node, worldX - x, worldY - y);
      return deeper ?? node;
    }
  }
  return null;
}
