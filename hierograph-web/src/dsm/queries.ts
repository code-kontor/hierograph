import { queryOptions } from "@tanstack/react-query";

import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

const dsmQuery = graphql(`
  query NodeAdjacencyMatrix($id: ID!) {
    hierarchicalGraph {
      node(id: $id) {
        id
        text
        type
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
        nodes {
          id
          text
          type
        }
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
