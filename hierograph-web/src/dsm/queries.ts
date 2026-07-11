import { queryOptions } from "@tanstack/react-query";

import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

const DSM_TRIGGER = "DSM";

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
      return execute(dsmQuery, { id }, DSM_TRIGGER);
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
      return execute(nodesDsmQuery, { ids: sortedIds }, DSM_TRIGGER);
    },
  });
}

// Ancestor chains for the subject nodes, batched by id — used to expand and
// scroll the tree to a deep-linked / back-navigated selection via
// AsyncTree.revealNode (`predecessors` returns each node's ancestors, nearest
// first; the mapping to tree rows is by id only, never by fqn).
const dsmSubjectPredecessorsQuery = graphql(`
  query DsmSubjectPredecessors($ids: [ID!]!) {
    hierarchicalGraph {
      nodes(ids: $ids) {
        nodes {
          id
          predecessors {
            id
          }
        }
      }
    }
  }
`);

export function dsmSubjectPredecessorsQueryOptions(ids: string[]) {
  return queryOptions({
    queryKey: ["dsm", "subjectPredecessors", ids],
    async queryFn() {
      return execute(dsmSubjectPredecessorsQuery, { ids }, DSM_TRIGGER);
    },
  });
}
