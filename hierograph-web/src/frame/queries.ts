import {
  keepPreviousData,
  mutationOptions,
  queryOptions,
} from "@tanstack/react-query";

import { type SourceLocation, sourceLocation } from "@/frame/copyEntries";
import { graphQueryKey, type GraphScope } from "@/frame/GraphScope";
import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

// The graph status the frame polls: cheap on the server (no node counts). A new
// `generation` means the server reloaded the graph, a new `viewsVersion` that a
// view document was added, changed or removed. The workspace names the project
// in the header; artifacts not compiled yet and a server built from
// uncommitted changes mark the status as needing attention.
export const GraphStatusQuery = graphql(`
  query GraphStatus {
    server {
      workspace
      viewsVersion
      commit {
        dirty
      }
      artifacts {
        notCompiled {
          artifact
        }
      }
      graph {
        state
        update
        generation
      }
    }
  }
`);

// The hierarchy views: to check the `view` URL parameter against, and to
// choose one from in the header's menu. The scanned modules describe the
// hierarchy without a view there.
export const HierarchyViewsQuery = graphql(`
  query HierarchyViews {
    server {
      graph {
        counts {
          modules
        }
      }
    }
    views {
      name
      description
      source
      valid
      problems
      groupCount
      restructure
      excludedCount
      excludedNodes {
        type
      }
    }
  }
`);

export const GRAPH_STATUS_POLL_INTERVAL_MS = 5000;

// Polled every 5 s while the tab is visible; paused while it is hidden, with
// an immediate check when it becomes visible again. `staleTime: 0` overrides
// the global `Infinity`, which would otherwise suppress the refetch on focus.
export function graphStatusQueryOptions() {
  return queryOptions({
    queryKey: ["frame", "graphStatus"],
    queryFn() {
      return execute(GraphStatusQuery);
    },
    staleTime: 0,
    refetchInterval: GRAPH_STATUS_POLL_INTERVAL_MS,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  });
}

// The views change with the graph (a reload: their summaries) and whenever a
// view document is added, changed or removed (`viewsVersion`, polled with the
// graph status), so a view the agent just defined can be chosen at once.
export function hierarchyViewsQueryOptions(
  generation: string | null,
  viewsVersion: string | null,
) {
  return queryOptions({
    queryKey: ["frame", "hierarchyViews", generation, viewsVersion],
    queryFn() {
      return execute(HierarchyViewsQuery);
    },
  });
}

// Everything the server status panel shows: the workspace, the graph, the
// server and its JVM.
export const ServerStatusQuery = graphql(`
  query ServerStatus {
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
  }
`);

export const SERVER_STATUS_POLL_INTERVAL_MS = 5000;

// The panel shows live data: polled like the graph status (every 5 s while
// the tab is visible), but only while the panel is open, as only its content
// uses the query; keyed by the graph generation so a reload shows at once. `placeholderData` keeps the last answer on screen while
// a new generation is fetched.
export function serverStatusQueryOptions(generation: string | null) {
  return queryOptions({
    queryKey: ["frame", "serverStatus", generation],
    queryFn() {
      return execute(ServerStatusQuery);
    },
    placeholderData: keepPreviousData,
    staleTime: 0,
    refetchInterval: SERVER_STATUS_POLL_INTERVAL_MS,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  });
}

// Asks the server JVM for a garbage collection. The server status is refetched
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
        queryKey: ["frame", "serverStatus"],
      });
    },
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
    queryKey: graphQueryKey("frame", scope, "exclusions"),
    async queryFn(): Promise<ViewExclusions | null> {
      const data = await execute(ViewExclusionsQuery);
      const view = data.views.find((entry) => entry.name === scope.view);
      if (!view?.excludedNodes || view.excludedCount == null) return null;
      return { count: view.excludedCount, nodes: view.excludedNodes };
    },
    enabled: scope.view !== undefined,
  });
}

// Where a type's source file is, for the Copy submenu's paths (#88): the
// node's source-file properties, and the workspace as the host sees it, which
// the paths are below. Only asked for when the submenu of a type opens.
export const NodeSourceLocationQuery = graphql(`
  query NodeSourceLocation($view: String, $id: ID!) {
    server {
      workspace
    }
    hierarchicalGraph(view: $view) {
      node(id: $id) {
        id
        properties {
          key
          value
        }
      }
    }
  }
`);

export type NodeSourceLocation = {
  workspace: string;
  source: SourceLocation;
};

export function nodeSourceLocationQueryOptions(scope: GraphScope, id: string) {
  return queryOptions({
    queryKey: graphQueryKey("frame", scope, "sourceLocation", id),
    async queryFn(): Promise<NodeSourceLocation> {
      const data = await execute(NodeSourceLocationQuery, {
        view: scope.view ?? null,
        id,
      });
      return {
        workspace: data.server.workspace,
        source: sourceLocation(data.hierarchicalGraph?.node?.properties ?? []),
      };
    },
  });
}
