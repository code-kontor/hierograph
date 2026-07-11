import type { ElkNode } from "elkjs/lib/elk.bundled.js";

import { type DiagramElkNode, NODE_HEIGHT, NODE_WIDTH } from "./elkLayout";
import type { DependencyGraph, DependencyGraphNode } from "./graphModel";

// Builds a nested ELK graph (a compound tree of DiagramElkNode) from the root
// dependency graph plus the already-loaded child graphs and the set of
// currently-expanded node ids. A node becomes a container only when it is both
// expanded and its children have been loaded; otherwise it stays a leaf. A
// fully-collapsed tree is structurally identical to the flat layout (leaves
// only), so callers can always use this path.

// buildDependencyGraph produces positional edge ids (`${column}-${row}`) that
// are only unique within a single matrix. Merging several matrices into one
// compound graph would collide, so every ELK edge id is scoped by its owning
// container. sources/targets keep the globally-unique node ids so edge
// activation still resolves the correct nodes across levels.
function scopedContainerEdges(containerId: string, graph: DependencyGraph) {
  return graph.edges.map((edge) => ({
    id: `${containerId}:${edge.id}`,
    sources: [edge.sourceId],
    targets: [edge.targetId],
    labels: [{ text: String(edge.weight) }],
  }));
}

function buildNode(
  node: DependencyGraphNode,
  loadedChildren: Map<string, DependencyGraph>,
  expanded: Set<string>,
): DiagramElkNode {
  const base: DiagramElkNode = {
    id: node.id,
    labels: [{ text: node.text }],
    nodeType: node.type,
  };

  const childGraph = loadedChildren.get(node.id);
  const isExpandedContainer = expanded.has(node.id) && childGraph !== undefined;

  if (!isExpandedContainer) {
    // Leaf — also covers "expanded but not yet loaded": treated as a leaf
    // until its children arrive. Fixed size; ELK does not resize leaves.
    return { ...base, width: NODE_WIDTH, height: NODE_HEIGHT };
  }

  // Container — nest its children and its own internal edges. No width/height
  // so ELK grows the box around its content. Recursion only descends into
  // loaded+expanded ids, so it always terminates.
  return {
    ...base,
    children: childGraph.nodes.map((child) =>
      buildNode(child, loadedChildren, expanded),
    ),
    edges: scopedContainerEdges(node.id, childGraph),
  };
}

export function buildCompoundElkGraph(
  rootGraph: DependencyGraph,
  loadedChildren: Map<string, DependencyGraph>,
  expanded: Set<string>,
): ElkNode {
  return {
    id: "root",
    children: rootGraph.nodes.map((node) =>
      buildNode(node, loadedChildren, expanded),
    ),
    edges: scopedContainerEdges("root", rootGraph),
  };
}
