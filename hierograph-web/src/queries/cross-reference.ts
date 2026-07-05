import { queryOptions } from "@tanstack/react-query";

import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

const crossReferenceLeftChildrenQuery = graphql(`
  query CrossReferenceLeftChildren($parentNode: ID!, $centerNodeIds: [ID!]!) {
    hierarchicalGraph {
      node(id: $parentNode) {
        childrenFilteredByReferencedNodes(referencedNodeIds: $centerNodeIds) {
          nodes {
            id
            text
            type
            hasChildren
          }
        }
      }
    }
  }
`);

export function crossReferenceLeftChildrenQueryOptions(
  parentNodeId: string,
  centerNodeIds: string[],
) {
  return queryOptions({
    queryKey: ["crossReference", "left", parentNodeId, centerNodeIds],
    async queryFn() {
      return execute(crossReferenceLeftChildrenQuery, {
        parentNode: parentNodeId,
        centerNodeIds,
      });
    },
  });
}

const crossReferenceRightChildrenQuery = graphql(`
  query CrossReferenceRightChildren($parentNode: ID!, $centerNodeIds: [ID!]!) {
    hierarchicalGraph {
      node(id: $parentNode) {
        childrenFilteredByReferencingNodes(referencingNodeIds: $centerNodeIds) {
          nodes {
            id
            text
            type
            hasChildren
          }
        }
      }
    }
  }
`);

export function crossReferenceRightChildrenQueryOptions(
  parentNodeId: string,
  centerNodeIds: string[],
) {
  return queryOptions({
    queryKey: ["crossReference", "right", parentNodeId, centerNodeIds],
    async queryFn() {
      return execute(crossReferenceRightChildrenQuery, {
        parentNode: parentNodeId,
        centerNodeIds,
      });
    },
  });
}

const crossReferenceCenterMarkedByLeftQuery = graphql(`
  query CrossReferenceCenterMarkedByLeft(
    $candidateIds: [ID!]!
    $selectionIds: [ID!]!
  ) {
    hierarchicalGraph {
      nodes(ids: $candidateIds) {
        filterReferencingNodes(
          nodeIds: $selectionIds
          nodesToConsider: SELF_AND_CHILDREN
          includePredecessorsInResult: true
        ) {
          nodeIds
        }
      }
    }
  }
`);

export function crossReferenceCenterMarkedByLeftQueryOptions(
  candidateIds: string[],
  selectionIds: string[],
) {
  return queryOptions({
    queryKey: ["crossReference", "markedByLeft", candidateIds, selectionIds],
    async queryFn() {
      return execute(crossReferenceCenterMarkedByLeftQuery, {
        candidateIds,
        selectionIds,
      });
    },
  });
}

const crossReferenceCenterMarkedByRightQuery = graphql(`
  query CrossReferenceCenterMarkedByRight(
    $candidateIds: [ID!]!
    $selectionIds: [ID!]!
  ) {
    hierarchicalGraph {
      nodes(ids: $candidateIds) {
        filterReferencedNodes(
          nodeIds: $selectionIds
          nodesToConsider: SELF_AND_CHILDREN
          includePredecessorsInResult: true
        ) {
          nodeIds
        }
      }
    }
  }
`);

export function crossReferenceCenterMarkedByRightQueryOptions(
  candidateIds: string[],
  selectionIds: string[],
) {
  return queryOptions({
    queryKey: ["crossReference", "markedByRight", candidateIds, selectionIds],
    async queryFn() {
      return execute(crossReferenceCenterMarkedByRightQuery, {
        candidateIds,
        selectionIds,
      });
    },
  });
}
