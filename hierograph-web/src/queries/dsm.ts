import { queryOptions } from "@tanstack/react-query";

import { graphql } from "@/generated/graphql";
import { execute } from "@/lib/graphql-client";

const dsmQuery = graphql(`
  query NodeAdjacencyMatrix($id: ID!) {
    hierarchicalGraph {
      node(id: $id) {
        id
        children {
          orderedAdjacencyMatrix {
            orderedNodes {
              id
              text
              type
            }
            cells {
              row
              column
              value
            }
            stronglyConnectedComponents {
              nodePositions
            }
          }
        }
      }
    }
  }
`);

export function dsmQueryOptions(id: string) {
  return queryOptions({
    queryKey: ["hierarchicalGraph", "node", id, "dsm"],
    async queryFn() {
      return execute(dsmQuery, { id });
    },
  });
}
