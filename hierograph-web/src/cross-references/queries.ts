import { queryOptions } from "@tanstack/react-query";

import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

const crossReferencesUsedByQuery = graphql(`
  query CrossReferencesUsedBy($parentNode: ID!, $subjectId: ID!) {
    hierarchicalGraph {
      node(id: $parentNode) {
        childrenFilteredByReferencedNodes(referencedNodeIds: [$subjectId]) {
          nodes {
            id
            text
            type
            hasChildren
            dependenciesTo(targetNodes: [$subjectId]) {
              weight
            }
          }
        }
      }
    }
  }
`);

export function crossReferencesUsedByQueryOptions(
  parentNodeId: string,
  subjectId: string,
) {
  return queryOptions({
    queryKey: ["crossReferences", "usedBy", parentNodeId, subjectId],
    async queryFn() {
      return execute(crossReferencesUsedByQuery, {
        parentNode: parentNodeId,
        subjectId,
      });
    },
  });
}

const crossReferencesUsesQuery = graphql(`
  query CrossReferencesUses($parentNode: ID!, $subjectId: ID!) {
    hierarchicalGraph {
      node(id: $parentNode) {
        childrenFilteredByReferencingNodes(referencingNodeIds: [$subjectId]) {
          nodes {
            id
            text
            type
            hasChildren
            dependenciesFrom(sourceNodes: [$subjectId]) {
              weight
            }
          }
        }
      }
    }
  }
`);

export function crossReferencesUsesQueryOptions(
  parentNodeId: string,
  subjectId: string,
) {
  return queryOptions({
    queryKey: ["crossReferences", "uses", parentNodeId, subjectId],
    async queryFn() {
      return execute(crossReferencesUsesQuery, {
        parentNode: parentNodeId,
        subjectId,
      });
    },
  });
}
