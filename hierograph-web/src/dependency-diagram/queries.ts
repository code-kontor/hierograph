import { queryOptions } from "@tanstack/react-query";

import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

const DIAGRAM_TRIGGER = "Dependency Diagram";

const diagramNodeAdjacencyMatrixQuery = graphql(`
  query DiagramNodeAdjacencyMatrix($id: ID!) {
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
          }
        }
      }
    }
  }
`);

export function diagramNodeAdjacencyMatrixQueryOptions(id: string) {
  return queryOptions({
    queryKey: ["dependencyDiagram", "node", id],
    async queryFn() {
      return execute(diagramNodeAdjacencyMatrixQuery, { id }, DIAGRAM_TRIGGER);
    },
  });
}

const diagramNodesAdjacencyMatrixQuery = graphql(`
  query DiagramNodesAdjacencyMatrix($ids: [ID!]!) {
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
        }
      }
    }
  }
`);

export function diagramNodesAdjacencyMatrixQueryOptions(ids: string[]) {
  const sortedIds = [...ids].sort();
  return queryOptions({
    queryKey: ["dependencyDiagram", "nodes", sortedIds],
    async queryFn() {
      return execute(
        diagramNodesAdjacencyMatrixQuery,
        { ids: sortedIds },
        DIAGRAM_TRIGGER,
      );
    },
  });
}
