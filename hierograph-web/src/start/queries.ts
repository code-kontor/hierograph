import {
  keepPreviousData,
  mutationOptions,
  queryOptions,
} from "@tanstack/react-query";

import { graphQueryKey, type GraphScope } from "@/frame/GraphScope";
import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

// Everything the start screen shows: the workspace, the graph, the server and
// its JVM, and the hierarchy views.
export const StartScreenQuery = graphql(`
  query StartScreen {
    server {
      url
      uiUrl
      version
      commit {
        id
        dirty
      }
      startedAt
      workspace
      stateDir
      source {
        kind
        manifest
        buildRoot
        includeTests
        watchMode
        buildFilesMode
        parquetDir
      }
      artifacts {
        declared
        scanned
        notCompiled {
          artifact
          dirs
        }
      }
      graph {
        state
        update
        generation
        loadedAt
        counts {
          modules
          packages
          types
          total
        }
      }
      jvm {
        javaVersion
        javaVendor
        vmName
        uptimeMs
        availableProcessors
        heap {
          usedBytes
          committedBytes
          maxBytes
          usedAfterGcBytes
        }
        nonHeap {
          usedBytes
          committedBytes
          maxBytes
          usedAfterGcBytes
        }
        garbageCollectors {
          name
          count
          timeMs
        }
        threads {
          live
          peak
          daemon
        }
        processCpuLoad
      }
    }
    views {
      name
      description
      source
      valid
      problems
      groupCount
      errors
      warnings
      restructure
      excludedCount
    }
  }
`);

export const START_SCREEN_POLL_INTERVAL_MS = 5000;

// The start screen shows live data: polled like the frame's graph status
// (every 5 s while the tab is visible), and keyed by the graph generation so a
// reload shows at once. `placeholderData` keeps the last answer on screen while
// a new generation is fetched.
export function startScreenQueryOptions(generation: string | null) {
  return queryOptions({
    queryKey: ["start", "screen", generation],
    queryFn() {
      return execute(StartScreenQuery);
    },
    placeholderData: keepPreviousData,
    staleTime: 0,
    refetchInterval: START_SCREEN_POLL_INTERVAL_MS,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  });
}

// Asks the server JVM for a garbage collection. The start screen is refetched
// afterwards, so its JVM figures show what the collection kept.
export const RunGarbageCollectionMutation = graphql(`
  mutation RunGarbageCollection {
    runGarbageCollection {
      heap {
        usedAfterGcBytes
      }
    }
  }
`);

export function runGarbageCollectionMutationOptions() {
  return mutationOptions({
    mutationFn() {
      return execute(RunGarbageCollectionMutation);
    },
    onSuccess(_data, _variables, _result, context) {
      return context.client.invalidateQueries({
        queryKey: ["start", "screen"],
      });
    },
  });
}

// The hierarchy tree: the top level of the hierarchy in a view (or the scanned
// hierarchy without one), then each node's children when it is expanded. The
// siblings come in dependency order (DSM order: dependents first, each cycle a
// contiguous block) with their cycles; a type's members, with `ordered: false`,
// in source order.
export const HierarchyTreeRootQuery = graphql(`
  query HierarchyTreeRoot($view: String) {
    hierarchicalGraph(view: $view) {
      rootNode {
        id
        children {
          orderedAdjacencyMatrix {
            orderedNodes {
              id
              text
              type
              hasChildren
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
        children {
          nodes @skip(if: $ordered) {
            id
            text
            type
            hasChildren
          }
          orderedAdjacencyMatrix @include(if: $ordered) {
            orderedNodes {
              id
              text
              type
              hasChildren
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

// What the current hierarchy view leaves out: the outermost excluded nodes and
// how many nodes went with them. `null` without a view, or for one without
// exclusions. Keyed by the graph scope, which changes with the view documents,
// so an edited exclusion shows at once.
export const ViewExclusionsQuery = graphql(`
  query ViewExclusions {
    views {
      name
      excludedCount
      excludedNodes {
        id
        text
        type
      }
    }
  }
`);

export type ExcludedNode = {
  id: string;
  text: string;
  type: string;
};

export type ViewExclusions = {
  count: number;
  nodes: ExcludedNode[];
};

export function viewExclusionsQueryOptions(scope: GraphScope) {
  return queryOptions({
    queryKey: graphQueryKey("start", scope, "exclusions"),
    async queryFn(): Promise<ViewExclusions | null> {
      const data = await execute(ViewExclusionsQuery);
      const view = data.views.find((entry) => entry.name === scope.view);
      if (!view?.excludedNodes || view.excludedCount == null) return null;
      return { count: view.excludedCount, nodes: view.excludedNodes };
    },
    enabled: scope.view !== undefined,
  });
}
