import { queryOptions } from "@tanstack/react-query";

import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";
import type { NodeType } from "@/graphql/generated/graphql";

export type { NodeType };

const filteredChildrenQuery = graphql(`
  query FilteredChildren(
    $sourceNodeId: ID!
    $targetNodeId: ID!
    $parentNode: ID!
    $parentNodeType: NodeType!
  ) {
    hierarchicalGraph {
      dependencySetForAggregatedDependency(
        sourceNodeId: $sourceNodeId
        targetNodeId: $targetNodeId
      ) {
        filteredChildren(
          parentNode: $parentNode
          parentNodeType: $parentNodeType
        ) {
          id
          text
          type
          hasChildren
        }
      }
    }
  }
`);

export function filteredChildrenQueryOptions(
  sourceNodeId: string,
  targetNodeId: string,
  parentNodeId: string,
  parentNodeType: NodeType,
) {
  return queryOptions({
    queryKey: [
      "dependencySet",
      sourceNodeId,
      targetNodeId,
      "filteredChildren",
      parentNodeId,
      parentNodeType,
    ],
    async queryFn() {
      return execute(filteredChildrenQuery, {
        sourceNodeId,
        targetNodeId,
        parentNode: parentNodeId,
        parentNodeType,
      });
    },
  });
}

const filteredDependenciesQuery = graphql(`
  query FilteredDependencies(
    $sourceNodeId: ID!
    $targetNodeId: ID!
    $selectedSourceIds: [ID!]!
    $selectedTargetIds: [ID!]!
  ) {
    hierarchicalGraph {
      dependencySetForAggregatedDependency(
        sourceNodeId: $sourceNodeId
        targetNodeId: $targetNodeId
      ) {
        size
        filteredDependencies(
          nodeSelection: [
            { selectedNodeIds: $selectedSourceIds, selectedNodesType: SOURCE }
            { selectedNodeIds: $selectedTargetIds, selectedNodesType: TARGET }
          ]
        ) {
          markedSourceIds: referencedNodeIds(
            nodeType: SOURCE
            includedPredecessors: true
          )
          markedTargetIds: referencedNodeIds(
            nodeType: TARGET
            includedPredecessors: true
          )
        }
      }
    }
  }
`);

const dependencyEdgesQuery = graphql(`
  query DependencyEdges($sourceNodeId: ID!, $targetNodeId: ID!) {
    hierarchicalGraph {
      dependencySetForAggregatedDependency(
        sourceNodeId: $sourceNodeId
        targetNodeId: $targetNodeId
      ) {
        size
        dependencies {
          id
          type
          sourceNode {
            id
            text
            type
          }
          targetNode {
            id
            text
            type
          }
        }
      }
    }
  }
`);

export function dependencyEdgesQueryOptions(
  sourceNodeId: string,
  targetNodeId: string,
) {
  return queryOptions({
    queryKey: ["dependencySet", sourceNodeId, targetNodeId, "edges"],
    async queryFn() {
      return execute(dependencyEdgesQuery, { sourceNodeId, targetNodeId });
    },
  });
}

export function filteredDependenciesQueryOptions(
  sourceNodeId: string,
  targetNodeId: string,
  selectedSourceIds: string[],
  selectedTargetIds: string[],
) {
  return queryOptions({
    queryKey: [
      "dependencySet",
      sourceNodeId,
      targetNodeId,
      "filteredDependencies",
      selectedSourceIds,
      selectedTargetIds,
    ],
    async queryFn() {
      return execute(filteredDependenciesQuery, {
        sourceNodeId,
        targetNodeId,
        selectedSourceIds,
        selectedTargetIds,
      });
    },
  });
}
