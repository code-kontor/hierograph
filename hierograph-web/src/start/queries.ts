import { graphql } from "@/graphql/generated";

// Everything the start screen shows: the workspace, the graph, the server, and
// the hierarchy views.
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
