import type { ElkNode } from "elkjs/lib/elk.bundled.js";

// Returns the top-level child node whose bounding box contains the world-space
// point, or null. Coordinates are ELK world coordinates (same space as
// node.x/y/width/height), so callers must convert screen -> world first
// (see viewport.ts screenToWorld). Iterating rootNode.children only is
// sufficient for the flat node-link layout of #0127; #0128 (in-place expand)
// will extend this to descend into nested children (innermost hit wins).
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
      return node;
    }
  }
  return null;
}
