import { queryOptions } from "@tanstack/react-query";

import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

const crossReferenceExplorerLeftChildrenQuery = graphql(`
  query CrossReferenceExplorerLeftChildren(
    $parentNode: ID!
    $centerNodeIds: [ID!]!
  ) {
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

export function crossReferenceExplorerLeftChildrenQueryOptions(
  parentNodeId: string,
  centerNodeIds: string[],
) {
  return queryOptions({
    queryKey: ["crossReferenceExplorer", "left", parentNodeId, centerNodeIds],
    async queryFn() {
      return execute(crossReferenceExplorerLeftChildrenQuery, {
        parentNode: parentNodeId,
        centerNodeIds,
      });
    },
  });
}

const crossReferenceExplorerRightChildrenQuery = graphql(`
  query CrossReferenceExplorerRightChildren(
    $parentNode: ID!
    $centerNodeIds: [ID!]!
  ) {
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

export function crossReferenceExplorerRightChildrenQueryOptions(
  parentNodeId: string,
  centerNodeIds: string[],
) {
  return queryOptions({
    queryKey: ["crossReferenceExplorer", "right", parentNodeId, centerNodeIds],
    async queryFn() {
      return execute(crossReferenceExplorerRightChildrenQuery, {
        parentNode: parentNodeId,
        centerNodeIds,
      });
    },
  });
}

const crossReferenceExplorerCenterMarkedByLeftQuery = graphql(`
  query CrossReferenceExplorerCenterMarkedByLeft(
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

export function crossReferenceExplorerCenterMarkedByLeftQueryOptions(
  candidateIds: string[],
  selectionIds: string[],
) {
  return queryOptions({
    queryKey: [
      "crossReferenceExplorer",
      "markedByLeft",
      candidateIds,
      selectionIds,
    ],
    async queryFn() {
      return execute(crossReferenceExplorerCenterMarkedByLeftQuery, {
        candidateIds,
        selectionIds,
      });
    },
  });
}

const crossReferenceExplorerCenterMarkedByRightQuery = graphql(`
  query CrossReferenceExplorerCenterMarkedByRight(
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

export function crossReferenceExplorerCenterMarkedByRightQueryOptions(
  candidateIds: string[],
  selectionIds: string[],
) {
  return queryOptions({
    queryKey: [
      "crossReferenceExplorer",
      "markedByRight",
      candidateIds,
      selectionIds,
    ],
    async queryFn() {
      return execute(crossReferenceExplorerCenterMarkedByRightQuery, {
        candidateIds,
        selectionIds,
      });
    },
  });
}
