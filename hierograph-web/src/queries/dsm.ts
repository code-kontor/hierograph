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

const nodesDsmQuery = graphql(`
  query NodesAdjacencyMatrix($ids: [ID!]!) {
    hierarchicalGraph {
      nodes(ids: $ids) {
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
`);

export function nodesDsmQueryOptions(ids: string[]) {
  const sortedIds = [...ids].sort();
  return queryOptions({
    queryKey: ["hierarchicalGraph", "nodes", sortedIds, "dsm"],
    async queryFn() {
      return execute(nodesDsmQuery, { ids: sortedIds });
    },
  });
}
