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

// The dependencies of the tree's selection (#77), as two dependency sets
// (#79): incoming, the dependencies into the selection's subtree from outside
// it; outgoing, those from it to outside. The root is where the trees of
// their far ends start.
export const DependencySetQuery = graphql(`
  query DependencySet($view: String, $id: ID!) {
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
        incomingDependencies {
          size
        }
        outgoingDependencies {
          size
        }
      }
    }
  }
`);

export type DependencyDirection = "incoming" | "outgoing";

// The two sides of a dependency: `SOURCE` depends on `TARGET`. The selection
// holds the targets of the incoming dependencies and the sources of the
// outgoing ones.
export type DependencySide = "SOURCE" | "TARGET";

// A row of a dependency tree, as the hierarchy tree has its rows: folded
// (#63), naming the lone packages it stands for.
export type DependencyNode = {
  id: string;
  text: string;
  type: string;
  hasChildren: boolean;
  compactedFrom: { text: string }[];
  // In a tree of a set's side: the dependencies with their end there in the
  // row's subtree, and their references.
  dependencies?: number;
  references?: number;
};

// The selection, the root, and how many dependencies each set holds.
export type DependencySetInfo = {
  selection: DependencyNode;
  root: DependencyNode;
  incoming: number;
  outgoing: number;
};

export function dependencySetQueryOptions(scope: GraphScope, id: string) {
  return queryOptions({
    queryKey: graphQueryKey("start", scope, "dependencySet", id),
    async queryFn(): Promise<DependencySetInfo | null> {
      const data = await execute(DependencySetQuery, {
        view: scope.view ?? null,
        id,
      });
      const graph = data.hierarchicalGraph;
      const node = graph?.node;
      if (!graph || !node) return null;
      const selection: DependencyNode = {
        id: node.id,
        text: node.text,
        type: node.type,
        hasChildren: node.hasChildren,
        compactedFrom: node.compactedFrom,
      };
      return {
        selection,
        root: graph.rootNode,
        incoming: node.incomingDependencies.size,
        outgoing: node.outgoingDependencies.size,
      };
    },
  });
}

// Where a selection in the dependency trees narrows the set: a node on the
// source side, one on the target side, or both. An absent side doesn't
// narrow.
export type DependencyPicks = { source?: string; target?: string };

function nodeSelection(picks: DependencyPicks) {
  return [
    ...(picks.source
      ? [
          {
            selectedNodeIds: [picks.source],
            selectedNodesType: "SOURCE" as const,
          },
        ]
      : []),
    ...(picks.target
      ? [
          {
            selectedNodeIds: [picks.target],
            selectedNodesType: "TARGET" as const,
          },
        ]
      : []),
  ];
}

// One level of a dependency tree, folded like the hierarchy tree: the
// children of `parent` that hold an end on `side` of a dependency in the set
// of `direction`, narrowed by `picks`, each with how many.
export const DependencySetChildrenQuery = graphql(`
  query DependencySetChildren(
    $view: String
    $id: ID!
    $incoming: Boolean!
    $picks: [NodeSelection!]!
    $parent: ID!
    $side: NodeType!
  ) {
    hierarchicalGraph(view: $view) {
      node(id: $id) {
        incomingDependencies @include(if: $incoming) {
          filteredDependencies(nodeSelection: $picks) {
            filteredChildrenWithCounts(
              parentNode: $parent
              parentNodeType: $side
              compact: true
            ) {
              node {
                id
                text
                type
                hasChildren
                compactedFrom {
                  text
                }
              }
              dependencies
              references
            }
          }
        }
        outgoingDependencies @skip(if: $incoming) {
          filteredDependencies(nodeSelection: $picks) {
            filteredChildrenWithCounts(
              parentNode: $parent
              parentNodeType: $side
              compact: true
            ) {
              node {
                id
                text
                type
                hasChildren
                compactedFrom {
                  text
                }
              }
              dependencies
              references
            }
          }
        }
      }
    }
  }
`);

export function dependencySetChildrenQueryOptions(
  scope: GraphScope,
  selectionId: string,
  direction: DependencyDirection,
  picks: DependencyPicks,
  parent: string,
  side: DependencySide,
) {
  return queryOptions({
    queryKey: graphQueryKey(
      "start",
      scope,
      "dependencySetChildren",
      selectionId,
      direction,
      picks.source ?? "",
      picks.target ?? "",
      side,
      parent,
    ),
    async queryFn(): Promise<DependencyNode[]> {
      const data = await execute(DependencySetChildrenQuery, {
        view: scope.view ?? null,
        id: selectionId,
        incoming: direction === "incoming",
        picks: nodeSelection(picks),
        parent,
        side,
      });
      const node = data.hierarchicalGraph?.node;
      const set =
        direction === "incoming"
          ? node?.incomingDependencies
          : node?.outgoingDependencies;
      return (set?.filteredDependencies.filteredChildrenWithCounts ?? []).map(
        (child) => ({
          ...child.node,
          dependencies: child.dependencies,
          references: child.references,
        }),
      );
    },
  });
}

// One page of a set's dependencies, narrowed by the picks, with the picked
// nodes' names.
export const DependencySetPageQuery = graphql(`
  query DependencySetPage(
    $view: String
    $id: ID!
    $incoming: Boolean!
    $picks: [NodeSelection!]!
    $picked: [ID!]!
    $page: Int!
    $pageSize: Int!
  ) {
    hierarchicalGraph(view: $view) {
      picked: nodes(ids: $picked) {
        nodes {
          id
          text
          type
        }
      }
      node(id: $id) {
        incomingDependencies @include(if: $incoming) {
          filteredDependencies(nodeSelection: $picks) {
            references
            dependencyPage(pageNumber: $page, pageSize: $pageSize) {
              pageInfo {
                pageNumber
                maxPages
                totalCount
              }
              dependencies {
                id
                weight
                sourceNode {
                  id
                  text
                  type
                }
                targetNode {
                  id
                  text
                  type
                }
              }
            }
          }
        }
        outgoingDependencies @skip(if: $incoming) {
          filteredDependencies(nodeSelection: $picks) {
            references
            dependencyPage(pageNumber: $page, pageSize: $pageSize) {
              pageInfo {
                pageNumber
                maxPages
                totalCount
              }
              dependencies {
                id
                weight
                sourceNode {
                  id
                  text
                  type
                }
                targetNode {
                  id
                  text
                  type
                }
              }
            }
          }
        }
      }
    }
  }
`);

export type DependencyEnd = { id: string; text: string; type: string };

export type DependencyListEntry = {
  id: string;
  weight: number;
  source: DependencyEnd;
  target: DependencyEnd;
};

export type DependencyListPage = {
  page: number;
  pages: number;
  total: number;
  // The individual references all of them stand for.
  references: number;
  dependencies: DependencyListEntry[];
  // The selected nodes, by id.
  picked: Map<string, DependencyEnd>;
};

export const DEPENDENCY_PAGE_SIZE = 50;

export function dependencySetPageQueryOptions(
  scope: GraphScope,
  selectionId: string,
  direction: DependencyDirection,
  picks: DependencyPicks,
  page: number,
) {
  return queryOptions({
    queryKey: graphQueryKey(
      "start",
      scope,
      "dependencySetPage",
      selectionId,
      direction,
      picks.source ?? "",
      picks.target ?? "",
      page,
    ),
    async queryFn(): Promise<DependencyListPage> {
      const data = await execute(DependencySetPageQuery, {
        view: scope.view ?? null,
        id: selectionId,
        incoming: direction === "incoming",
        picks: nodeSelection(picks),
        picked: [picks.source, picks.target].filter(
          (id): id is string => id !== undefined,
        ),
        page,
        pageSize: DEPENDENCY_PAGE_SIZE,
      });
      const graph = data.hierarchicalGraph;
      const set =
        direction === "incoming"
          ? graph?.node?.incomingDependencies
          : graph?.node?.outgoingDependencies;
      const result = set?.filteredDependencies.dependencyPage;
      const references = set?.filteredDependencies.references ?? 0;
      const picked = new Map(
        (graph?.picked?.nodes ?? []).map((node) => [node.id, node]),
      );
      return {
        page: result?.pageInfo.pageNumber ?? page,
        pages: result?.pageInfo.maxPages ?? 0,
        total: result?.pageInfo.totalCount ?? 0,
        references,
        dependencies: (result?.dependencies ?? []).map((dependency) => ({
          id: dependency.id,
          weight: dependency.weight,
          source: dependency.sourceNode,
          target: dependency.targetNode,
        })),
        picked,
      };
    },
    placeholderData: keepPreviousData,
  });
}

// The rows above a picked node, as the trees fold them (#63): its display
// ancestors, nearest first. A link that names a pick opens the trees down to
// it.
export const DependencyPickPathQuery = graphql(`
  query DependencyPickPath($view: String, $id: ID!) {
    hierarchicalGraph(view: $view) {
      node(id: $id) {
        predecessors(compact: true) {
          id
        }
      }
    }
  }
`);

export function dependencyPickPathQueryOptions(scope: GraphScope, id: string) {
  return queryOptions({
    queryKey: graphQueryKey("start", scope, "pickPath", id),
    async queryFn(): Promise<string[]> {
      const data = await execute(DependencyPickPathQuery, {
        view: scope.view ?? null,
        id,
      });
      return (data.hierarchicalGraph?.node?.predecessors ?? []).map(
        (node) => node.id,
      );
    },
  });
}
