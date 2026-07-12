import type { ElkNode } from "elkjs/lib/elk.bundled.js";

import { collectWorldGraph, type WorldNode } from "./edgeHitTest";

// Returns the point on a box's border where the ray from the box centre towards
// (towardX, towardY) exits the box, in world coordinates. Used to anchor a
// straightened edge at the box edges instead of the centres, so the line (and
// its arrowhead) starts/ends on the border like the original ELK routing.
function borderPoint(
  box: WorldNode,
  towardX: number,
  towardY: number,
): { x: number; y: number } {
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;
  const dx = towardX - cx;
  const dy = towardY - cy;
  if (dx === 0 && dy === 0) {
    return { x: cx, y: cy };
  }
  const halfW = box.width / 2;
  const halfH = box.height / 2;
  const scaleX = dx !== 0 ? halfW / Math.abs(dx) : Infinity;
  const scaleY = dy !== 0 ? halfH / Math.abs(dy) : Infinity;
  const scale = Math.min(scaleX, scaleY);
  return { x: cx + dx * scale, y: cy + dy * scale };
}

// Re-routes every edge incident to movedNodeId (as source or target) to a
// single straight section between the current world-space centers of its
// endpoints, in place on the ELK output tree. Called after the node's x/y
// have already been mutated, so collectWorldGraph reads fresh world boxes.
// Edges whose other endpoint has no known box (missing geometry) are left
// untouched rather than guessed at.
export function straightenIncidentEdges(
  root: ElkNode,
  movedNodeId: string,
): void {
  const { nodes, edges } = collectWorldGraph(root);

  for (const { edge, offsetX, offsetY } of edges) {
    if (
      edge.sources?.[0] !== movedNodeId &&
      edge.targets?.[0] !== movedNodeId
    ) {
      continue;
    }

    const sourceBox = nodes.get(edge.sources?.[0] ?? "");
    const targetBox = nodes.get(edge.targets?.[0] ?? "");
    if (sourceBox === undefined || targetBox === undefined) {
      continue;
    }

    const sourceCx = sourceBox.x + sourceBox.width / 2;
    const sourceCy = sourceBox.y + sourceBox.height / 2;
    const targetCx = targetBox.x + targetBox.width / 2;
    const targetCy = targetBox.y + targetBox.height / 2;

    // Anchor the straightened edge at the box borders (facing the other box's
    // centre), not the centres, then shift into the owning container's local
    // space. This keeps the line and arrowhead on the box edges.
    const sourceBorder = borderPoint(sourceBox, targetCx, targetCy);
    const targetBorder = borderPoint(targetBox, sourceCx, sourceCy);

    const startPoint = {
      x: sourceBorder.x - offsetX,
      y: sourceBorder.y - offsetY,
    };
    const endPoint = {
      x: targetBorder.x - offsetX,
      y: targetBorder.y - offsetY,
    };

    edge.sections = [
      { id: `${edge.id}-straight`, startPoint, endPoint, bendPoints: [] },
    ];

    const label = edge.labels?.[0];
    if (label !== undefined && label.x !== undefined && label.y !== undefined) {
      label.x = (startPoint.x + endPoint.x) / 2;
      label.y = (startPoint.y + endPoint.y) / 2;
    }
  }
}
