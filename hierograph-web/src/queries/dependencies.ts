import { queryOptions } from "@tanstack/react-query";

import { graphql } from "@/generated/graphql";
import type { NodeType } from "@/generated/graphql/graphql";
import { execute } from "@/lib/graphql-client";

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
