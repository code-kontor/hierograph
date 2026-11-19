import { queryOptions } from "@tanstack/react-query";

import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

// The graph status the frame polls: cheap on the server (no node counts). A new
// `generation` means the server reloaded the graph. The workspace names the
// project in the header.
export const GraphStatusQuery = graphql(`
  query GraphStatus {
    server {
      workspace
      graph {
        state
        update
        generation
      }
    }
  }
`);

// The hierarchy views, to check the `view` URL parameter against.
export const HierarchyViewsQuery = graphql(`
  query HierarchyViews {
    views {
      name
      valid
      problems
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

// The views change with the graph (a reload) or a redefinition; the latter
// shows up on the next generation or a reload of the page.
export function hierarchyViewsQueryOptions(generation: string | null) {
  return queryOptions({
    queryKey: ["frame", "hierarchyViews", generation],
    queryFn() {
      return execute(HierarchyViewsQuery);
    },
  });
}
