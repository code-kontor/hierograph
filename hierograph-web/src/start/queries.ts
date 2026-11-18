import { keepPreviousData, queryOptions } from "@tanstack/react-query";

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
