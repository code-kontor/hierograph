import type { ElkNode } from "elkjs/lib/elk.bundled.js";

import { collectWorldGraph } from "./edgeHitTest";

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

    const startPoint = { x: sourceCx - offsetX, y: sourceCy - offsetY };
    const endPoint = { x: targetCx - offsetX, y: targetCy - offsetY };

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
