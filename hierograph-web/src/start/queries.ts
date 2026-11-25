import { keepPreviousData, queryOptions } from "@tanstack/react-query";

import { graphQueryKey, type GraphScope } from "@/frame/GraphScope";
import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

// The hierarchy tree: the top level of the hierarchy in a view (or the scanned
// hierarchy without one), then each node's children when it is expanded,
// folded: a chain of lone packages is one row, its last package, which names
// the packages it stands for (`compactedFrom`, #63). The
// siblings come in dependency order (DSM order: dependents first, each cycle a
// contiguous block) with their cycles; a type's members, with `ordered: false`,
// in source order.
export const HierarchyTreeRootQuery = graphql(`
  query HierarchyTreeRoot($view: String) {
    hierarchicalGraph(view: $view) {
      rootNode {
        id
        children(compact: true) {
          orderedAdjacencyMatrix {
            orderedNodes {
              id
              text
              type
              hasChildren
              compactedFrom {
                text
              }
            }
            stronglyConnectedComponents {
              nodeIds
            }
          }
        }
      }
    }
  }
`);

export const HierarchyTreeChildrenQuery = graphql(`
  query HierarchyTreeChildren($view: String, $id: ID!, $ordered: Boolean!) {
    hierarchicalGraph(view: $view) {
      node(id: $id) {
        id
        children(compact: true) {
          nodes @skip(if: $ordered) {
            id
            text
            type
            hasChildren
            compactedFrom {
              text
            }
          }
          orderedAdjacencyMatrix @include(if: $ordered) {
            orderedNodes {
              id
              text
              type
              hasChildren
              compactedFrom {
                text
              }
            }
            stronglyConnectedComponents {
              nodeIds
            }
          }
        }
      }
    }
  }
`);

export type TreeNode = {
  id: string;
  text: string;
  type: string;
  hasChildren: boolean;
  // The lone packages the row stands for, outermost first; empty if it isn't
  // folded.
  compactedFrom: { text: string }[];
};

// One level of the tree: the siblings in display order and their dependency
// cycles (each a list of node ids).
export type TreeLevelData = {
  nodes: TreeNode[];
  cycles: string[][];
};

type TreeChildren = {
  nodes?: TreeNode[];
  orderedAdjacencyMatrix?: {
    orderedNodes: TreeNode[];
    stronglyConnectedComponents: { nodeIds: string[] }[];
  };
};

function treeLevel(children: TreeChildren | undefined): TreeLevelData {
  const matrix = children?.orderedAdjacencyMatrix;
  if (matrix) {
    return {
      nodes: matrix.orderedNodes,
      cycles: matrix.stronglyConnectedComponents.map((scc) => scc.nodeIds),
    };
  }
  return { nodes: children?.nodes ?? [], cycles: [] };
}

// Graph queries: keyed by the graph scope, so a new generation or another
// hierarchy view loads them again; within one they never go stale.
export function hierarchyTreeRootQueryOptions(scope: GraphScope) {
  return queryOptions({
    queryKey: graphQueryKey("start", scope, "tree", "root"),
    async queryFn(): Promise<TreeLevelData> {
      const data = await execute(HierarchyTreeRootQuery, {
        view: scope.view ?? null,
      });
      return treeLevel(data.hierarchicalGraph?.rootNode.children);
    },
  });
}

// `ordered`: whether the children come in dependency order; a type's members
// don't (`ordersChildren` in treeLabels.ts).
export function hierarchyTreeChildrenQueryOptions(
  scope: GraphScope,
  id: string,
  ordered: boolean,
) {
  return queryOptions({
    queryKey: graphQueryKey("start", scope, "tree", id),
    async queryFn(): Promise<TreeLevelData> {
      const data = await execute(HierarchyTreeChildrenQuery, {
        view: scope.view ?? null,
        id,
        ordered,
      });
      return treeLevel(data.hierarchicalGraph?.node?.children);
    },
  });
}

// The dependencies between the tree's visible rows: the aggregated weight from
// each row's subtree into each other's, as the cells of a matrix over them.
export const HierarchyArcsQuery = graphql(`
  query HierarchyArcs($view: String, $ids: [ID!]!) {
    hierarchicalGraph(view: $view) {
      nodes(ids: $ids) {
        orderedAdjacencyMatrix {
          orderedNodes {
            id
          }
          cells {
            row
            column
            value
          }
        }
      }
    }
  }
`);

// A dependency between two rows: `from`'s subtree uses `to`'s.
export type TreeDependency = { from: string; to: string; weight: number };

// `ids` sorted, so the same rows share one cache entry whatever their order.
// The previous arcs stay on screen while the next ones load.
export function hierarchyArcsQueryOptions(scope: GraphScope, ids: string[]) {
  const sorted = [...ids].sort();
  return queryOptions({
    queryKey: graphQueryKey("start", scope, "arcs", sorted.join(",")),
    async queryFn(): Promise<TreeDependency[]> {
      const data = await execute(HierarchyArcsQuery, {
        view: scope.view ?? null,
        ids: sorted,
      });
      const matrix = data.hierarchicalGraph?.nodes?.orderedAdjacencyMatrix;
      if (!matrix) return [];
      const id = (index: number) => matrix.orderedNodes[index].id;
      return matrix.cells
        .filter((cell) => cell.row !== cell.column && cell.value > 0)
        .map((cell) => ({
          from: id(cell.row),
          to: id(cell.column),
          weight: cell.value,
        }));
    },
    placeholderData: keepPreviousData,
  });
}

// The dependencies of the tree's selection (#77), in one direction: incoming,
// the nodes that depend on the selection's subtree, or outgoing, the nodes it
// depends on (dependencies inside the subtree are left out). `ends` are those
// nodes (types); the root is where the tree of the ends starts.
export const DependencyEndsQuery = graphql(`
  query DependencyEnds($view: String, $id: ID!, $incoming: Boolean!) {
    hierarchicalGraph(view: $view) {
      rootNode {
        id
        text
        type
        hasChildren
        compactedFrom {
          text
        }
      }
      node(id: $id) {
        id
        text
        type
        hasChildren
        compactedFrom {
          text
        }
        incomingEnds: referencingNodes @include(if: $incoming) {
          nodeIds
        }
        outgoingEnds: referencedNodes @skip(if: $incoming) {
          nodeIds
        }
      }
    }
  }
`);

export type DependencyDirection = "incoming" | "outgoing";

// A row of either dependency tree, as the hierarchy tree has its rows: folded
// (#63), naming the lone packages it stands for.
export type DependencyNode = {
  id: string;
  text: string;
  type: string;
  hasChildren: boolean;
  compactedFrom: { text: string }[];
};

// The selection, the root, and the ends of the selection's dependencies.
export type DependencyEnds = {
  selection: DependencyNode;
  root: DependencyNode;
  ends: string[];
};

export function dependencyEndsQueryOptions(
  scope: GraphScope,
  id: string,
  direction: DependencyDirection,
) {
  return queryOptions({
    queryKey: graphQueryKey("start", scope, "dependencyEnds", id, direction),
    async queryFn(): Promise<DependencyEnds | null> {
      const data = await execute(DependencyEndsQuery, {
        view: scope.view ?? null,
        id,
        incoming: direction === "incoming",
      });
      const graph = data.hierarchicalGraph;
      const node = graph?.node;
      if (!graph || !node) return null;
      const ends =
        (direction === "incoming" ? node.incomingEnds : node.outgoingEnds)
          ?.nodeIds ?? [];
      const selection: DependencyNode = {
        id: node.id,
        text: node.text,
        type: node.type,
        hasChildren: node.hasChildren,
        compactedFrom: node.compactedFrom,
      };
      return { selection, root: graph.rootNode, ends };
    },
  });
}

// One level of a dependency tree, folded like the hierarchy tree
// (`compact`): the children of `id` that
// - `used`: the ends depend on (the selection's tree, incoming),
// - `using`: depend on the ends (the selection's tree, outgoing),
// - `holding`: hold an end (the tree of the ends).
export type DependencyTreeMode = "used" | "using" | "holding";

export const DependencyTreeChildrenQuery = graphql(`
  query DependencyTreeChildren(
    $view: String
    $id: ID!
    $ends: [ID!]!
    $used: Boolean!
    $using: Boolean!
    $holding: Boolean!
  ) {
    hierarchicalGraph(view: $view) {
      node(id: $id) {
        id
        used: childrenFilteredByReferencingNodes(
          referencingNodeIds: $ends
          compact: true
        ) @include(if: $used) {
          nodes {
            id
            text
            type
            hasChildren
            compactedFrom {
              text
            }
          }
        }
        using: childrenFilteredByReferencedNodes(
          referencedNodeIds: $ends
          compact: true
        ) @include(if: $using) {
          nodes {
            id
            text
            type
            hasChildren
            compactedFrom {
              text
            }
          }
        }
        holding: childrenContainingNodes(nodeIds: $ends, compact: true)
          @include(if: $holding) {
          nodes {
            id
            text
            type
            hasChildren
            compactedFrom {
              text
            }
          }
        }
      }
    }
  }
`);

// Keyed by the selection and the direction, which decide the `ends`, and the
// mode.
export function dependencyTreeChildrenQueryOptions(
  scope: GraphScope,
  selectionId: string,
  direction: DependencyDirection,
  mode: DependencyTreeMode,
  id: string,
  ends: string[],
) {
  return queryOptions({
    queryKey: graphQueryKey(
      "start",
      scope,
      "dependencyTree",
      selectionId,
      direction,
      mode,
      id,
    ),
    async queryFn(): Promise<DependencyNode[]> {
      const data = await execute(DependencyTreeChildrenQuery, {
        view: scope.view ?? null,
        id,
        ends,
        used: mode === "used",
        using: mode === "using",
        holding: mode === "holding",
      });
      const node = data.hierarchicalGraph?.node;
      return node?.[mode]?.nodes ?? [];
    },
  });
}
