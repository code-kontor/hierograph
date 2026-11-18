import { graphql } from "@/graphql/generated";

// The graph status the frame polls: cheap on the server (no node counts). A new
// `generation` means the server reloaded the graph.
export const GraphStatusQuery = graphql(`
  query GraphStatus {
    server {
      graph {
        state
        update
        generation
      }
    }
  }
`);
