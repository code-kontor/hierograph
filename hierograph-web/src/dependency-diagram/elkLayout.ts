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

// Root layout options for the compound path: the same layered options as the
// flat layout, plus INCLUDE_CHILDREN so edges that touch an expanded container
// route to its border and across levels (this is what makes cross-level edges
// draw correctly). A fully-collapsed compound tree lays out identically to the
// flat `layoutGraph`, so callers can always use the compound path.
const COMPOUND_ROOT_OPTIONS: Record<string, string> = {
  "elk.algorithm": "layered",
  "elk.direction": "DOWN",
  "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
  "elk.spacing.nodeNode": "24",
  "elk.layered.spacing.nodeNodeBetweenLayers": "48",
  "elk.hierarchyHandling": "INCLUDE_CHILDREN",
};

// Container padding reserves a top header band for the container's own label
// plus an inset around its children. These values are empirical placeholders,
// tuned live against the running diagram (task #0128, step 8). The string is
// the ELK ElkPadding literal.
const CONTAINER_PADDING = "[top=28.0,left=12.0,bottom=12.0,right=12.0]";

// A node with children is a container: give it padding for its header band and
// leave its width/height unset so ELK grows it around its children. Leaves keep
// their fixed 250x28 from the builder.
function applyContainerPadding(node: ElkNode): void {
  for (const child of node.children ?? []) {
    if ((child.children?.length ?? 0) > 0) {
      child.layoutOptions = {
        ...(child.layoutOptions ?? {}),
        "elk.padding": CONTAINER_PADDING,
      };
      applyContainerPadding(child);
    }
  }
}

// Lays out the nested tree from buildCompoundElkGraph. The root gets the
// layered + INCLUDE_CHILDREN options; every nested container gets header
// padding. ELK returns container-child coordinates parent-relative and
// container edge sections container-relative — an invariant the recursive draw
// and hit-tests must honor.
export function layoutCompoundGraph(root: ElkNode): Promise<ElkNode> {
  root.layoutOptions = { ...COMPOUND_ROOT_OPTIONS };
  applyContainerPadding(root);
  return elk.layout(root);
}
