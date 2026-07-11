import type { ElkNode } from "elkjs/lib/elk.bundled.js";
import ELK from "elkjs/lib/elk.bundled.js";

import type { DependencyGraphEdge, DependencyGraphNode } from "./graphModel";

export const NODE_WIDTH = 250;
export const NODE_HEIGHT = 28;

export type DiagramElkNode = ElkNode & { nodeType?: string };

const elk = new ELK();

export function layoutGraph(
  nodes: DependencyGraphNode[],
  edges: DependencyGraphEdge[],
): Promise<ElkNode> {
  const graph: ElkNode = {
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": "DOWN",
      "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
      "elk.spacing.nodeNode": "24",
      "elk.layered.spacing.nodeNodeBetweenLayers": "48",
    },
    children: nodes.map((node): DiagramElkNode => ({
      id: node.id,
      width: NODE_WIDTH,
      height: NODE_HEIGHT,
      labels: [{ text: node.text }],
      nodeType: node.type,
    })),
    edges: edges.map((edge) => ({
      id: edge.id,
      sources: [edge.sourceId],
      targets: [edge.targetId],
      labels: [{ text: String(edge.weight) }],
    })),
  };

  return elk.layout(graph);
}
