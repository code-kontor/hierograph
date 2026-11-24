import { queryOptions } from "@tanstack/react-query";

import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

// The graph status the frame polls: cheap on the server (no node counts). A new
// `generation` means the server reloaded the graph, a new `viewsVersion` that a
// view document was added, changed or removed. The workspace names the project
// in the header.
export const GraphStatusQuery = graphql(`
  query GraphStatus {
    server {
      workspace
      viewsVersion
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
