import { queryOptions } from "@tanstack/react-query";

import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

const crossReferencesUsedByQuery = graphql(`
  query CrossReferencesUsedBy($parentNode: ID!, $subjectIds: [ID!]!) {
    hierarchicalGraph {
      node(id: $parentNode) {
        childrenFilteredByReferencedNodes(
          referencedNodeIds: $subjectIds
          excludingNodeIds: $subjectIds
        ) {
          nodes {
            id
            text
            type
            hasChildren
            dependenciesTo(
              targetNodes: $subjectIds
              excludingNodeIds: $subjectIds
            ) {
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
  subjectIds: string[],
) {
  const subjectKey = [...subjectIds].sort().join(",");
  return queryOptions({
    queryKey: ["crossReferences", "usedBy", parentNodeId, subjectKey],
    async queryFn() {
      return execute(crossReferencesUsedByQuery, {
        parentNode: parentNodeId,
        subjectIds,
      });
    },
  });
}

const crossReferencesUsesQuery = graphql(`
  query CrossReferencesUses($parentNode: ID!, $subjectIds: [ID!]!) {
    hierarchicalGraph {
      node(id: $parentNode) {
        childrenFilteredByReferencingNodes(
          referencingNodeIds: $subjectIds
          excludingNodeIds: $subjectIds
        ) {
          nodes {
            id
            text
            type
            hasChildren
            dependenciesFrom(
              sourceNodes: $subjectIds
              excludingNodeIds: $subjectIds
            ) {
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
  subjectIds: string[],
) {
  const subjectKey = [...subjectIds].sort().join(",");
  return queryOptions({
    queryKey: ["crossReferences", "uses", parentNodeId, subjectKey],
    async queryFn() {
      return execute(crossReferencesUsesQuery, {
        parentNode: parentNodeId,
        subjectIds,
      });
    },
  });
}

const crossReferencesNodePredecessorsQuery = graphql(`
  query CrossReferencesNodePredecessors($id: ID!) {
    hierarchicalGraph {
      node(id: $id) {
        id
        predecessors {
          id
        }
      }
    }
  }
`);

export function crossReferencesNodePredecessorsQueryOptions(id: string) {
  return queryOptions({
    queryKey: ["crossReferences", "predecessors", id],
    // Predecessor relationships are structural and don't change mid-session.
    staleTime: Infinity,
    async queryFn() {
      return execute(crossReferencesNodePredecessorsQuery, { id });
    },
  });
}
