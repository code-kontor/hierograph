/* eslint-disable */
import * as types from './graphql';
import type { TypedDocumentNode as DocumentNode } from '@graphql-typed-document-node/core';

/**
 * Map of all GraphQL operations in the project.
 *
 * This map has several performance disadvantages:
 * 1. It is not tree-shakeable, so it will include all operations in the project.
 * 2. It is not minifiable, so the string of a GraphQL query will be multiple times inside the bundle.
 * 3. It does not support dead code elimination, so it will add unused operations.
 *
 * Therefore it is highly recommended to use the babel or swc plugin for production.
 * Learn more about it here: https://the-guild.dev/graphql/codegen/plugins/presets/preset-client#reducing-bundle-size
 */
type Documents = {
    "\n  query FilteredChildren(\n    $sourceNodeId: ID!\n    $targetNodeId: ID!\n    $parentNode: ID!\n    $parentNodeType: NodeType!\n  ) {\n    hierarchicalGraph {\n      dependencySetForAggregatedDependency(\n        sourceNodeId: $sourceNodeId\n        targetNodeId: $targetNodeId\n      ) {\n        filteredChildren(\n          parentNode: $parentNode\n          parentNodeType: $parentNodeType\n        ) {\n          id\n          text\n          type\n          hasChildren\n        }\n      }\n    }\n  }\n": typeof types.FilteredChildrenDocument,
    "\n  query FilteredDependencies(\n    $sourceNodeId: ID!\n    $targetNodeId: ID!\n    $selectedSourceIds: [ID!]!\n    $selectedTargetIds: [ID!]!\n  ) {\n    hierarchicalGraph {\n      dependencySetForAggregatedDependency(\n        sourceNodeId: $sourceNodeId\n        targetNodeId: $targetNodeId\n      ) {\n        size\n        filteredDependencies(\n          nodeSelection: [\n            { selectedNodeIds: $selectedSourceIds, selectedNodesType: SOURCE }\n            { selectedNodeIds: $selectedTargetIds, selectedNodesType: TARGET }\n          ]\n        ) {\n          markedSourceIds: referencedNodeIds(\n            nodeType: SOURCE\n            includedPredecessors: true\n          )\n          markedTargetIds: referencedNodeIds(\n            nodeType: TARGET\n            includedPredecessors: true\n          )\n        }\n      }\n    }\n  }\n": typeof types.FilteredDependenciesDocument,
    "\n  query DependencyEdges($sourceNodeId: ID!, $targetNodeId: ID!) {\n    hierarchicalGraph {\n      dependencySetForAggregatedDependency(\n        sourceNodeId: $sourceNodeId\n        targetNodeId: $targetNodeId\n      ) {\n        size\n        dependencies {\n          id\n          type\n          sourceNode {\n            id\n            text\n            type\n          }\n          targetNode {\n            id\n            text\n            type\n          }\n        }\n      }\n    }\n  }\n": typeof types.DependencyEdgesDocument,
    "\n  query NodeAdjacencyMatrix($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        children {\n          orderedAdjacencyMatrix {\n            orderedNodes {\n              id\n              text\n              type\n            }\n            cells {\n              row\n              column\n              value\n            }\n            stronglyConnectedComponents {\n              nodePositions\n            }\n          }\n        }\n      }\n    }\n  }\n": typeof types.NodeAdjacencyMatrixDocument,
    "\n  query NodesAdjacencyMatrix($ids: [ID!]!) {\n    hierarchicalGraph {\n      nodes(ids: $ids) {\n        orderedAdjacencyMatrix {\n          orderedNodes {\n            id\n            text\n            type\n          }\n          cells {\n            row\n            column\n            value\n          }\n          stronglyConnectedComponents {\n            nodePositions\n          }\n        }\n      }\n    }\n  }\n": typeof types.NodesAdjacencyMatrixDocument,
    "\n  query RootNode {\n    hierarchicalGraph {\n      rootNode {\n        id\n        text\n        type\n        hasChildren\n      }\n    }\n  }\n": typeof types.RootNodeDocument,
    "\n  query NodeChildren($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        children {\n          nodes {\n            id\n            text\n            type\n            hasChildren\n          }\n        }\n      }\n    }\n  }\n": typeof types.NodeChildrenDocument,
    "\n  query NodeBasics($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        text\n        type\n        hasChildren\n      }\n    }\n  }\n": typeof types.NodeBasicsDocument,
    "\n  query NodeDetail($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        text\n        type\n        properties {\n          key\n          value\n        }\n      }\n    }\n  }\n": typeof types.NodeDetailDocument,
};
const documents: Documents = {
    "\n  query FilteredChildren(\n    $sourceNodeId: ID!\n    $targetNodeId: ID!\n    $parentNode: ID!\n    $parentNodeType: NodeType!\n  ) {\n    hierarchicalGraph {\n      dependencySetForAggregatedDependency(\n        sourceNodeId: $sourceNodeId\n        targetNodeId: $targetNodeId\n      ) {\n        filteredChildren(\n          parentNode: $parentNode\n          parentNodeType: $parentNodeType\n        ) {\n          id\n          text\n          type\n          hasChildren\n        }\n      }\n    }\n  }\n": types.FilteredChildrenDocument,
    "\n  query FilteredDependencies(\n    $sourceNodeId: ID!\n    $targetNodeId: ID!\n    $selectedSourceIds: [ID!]!\n    $selectedTargetIds: [ID!]!\n  ) {\n    hierarchicalGraph {\n      dependencySetForAggregatedDependency(\n        sourceNodeId: $sourceNodeId\n        targetNodeId: $targetNodeId\n      ) {\n        size\n        filteredDependencies(\n          nodeSelection: [\n            { selectedNodeIds: $selectedSourceIds, selectedNodesType: SOURCE }\n            { selectedNodeIds: $selectedTargetIds, selectedNodesType: TARGET }\n          ]\n        ) {\n          markedSourceIds: referencedNodeIds(\n            nodeType: SOURCE\n            includedPredecessors: true\n          )\n          markedTargetIds: referencedNodeIds(\n            nodeType: TARGET\n            includedPredecessors: true\n          )\n        }\n      }\n    }\n  }\n": types.FilteredDependenciesDocument,
    "\n  query DependencyEdges($sourceNodeId: ID!, $targetNodeId: ID!) {\n    hierarchicalGraph {\n      dependencySetForAggregatedDependency(\n        sourceNodeId: $sourceNodeId\n        targetNodeId: $targetNodeId\n      ) {\n        size\n        dependencies {\n          id\n          type\n          sourceNode {\n            id\n            text\n            type\n          }\n          targetNode {\n            id\n            text\n            type\n          }\n        }\n      }\n    }\n  }\n": types.DependencyEdgesDocument,
    "\n  query NodeAdjacencyMatrix($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        children {\n          orderedAdjacencyMatrix {\n            orderedNodes {\n              id\n              text\n              type\n            }\n            cells {\n              row\n              column\n              value\n            }\n            stronglyConnectedComponents {\n              nodePositions\n            }\n          }\n        }\n      }\n    }\n  }\n": types.NodeAdjacencyMatrixDocument,
    "\n  query NodesAdjacencyMatrix($ids: [ID!]!) {\n    hierarchicalGraph {\n      nodes(ids: $ids) {\n        orderedAdjacencyMatrix {\n          orderedNodes {\n            id\n            text\n            type\n          }\n          cells {\n            row\n            column\n            value\n          }\n          stronglyConnectedComponents {\n            nodePositions\n          }\n        }\n      }\n    }\n  }\n": types.NodesAdjacencyMatrixDocument,
    "\n  query RootNode {\n    hierarchicalGraph {\n      rootNode {\n        id\n        text\n        type\n        hasChildren\n      }\n    }\n  }\n": types.RootNodeDocument,
    "\n  query NodeChildren($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        children {\n          nodes {\n            id\n            text\n            type\n            hasChildren\n          }\n        }\n      }\n    }\n  }\n": types.NodeChildrenDocument,
    "\n  query NodeBasics($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        text\n        type\n        hasChildren\n      }\n    }\n  }\n": types.NodeBasicsDocument,
    "\n  query NodeDetail($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        text\n        type\n        properties {\n          key\n          value\n        }\n      }\n    }\n  }\n": types.NodeDetailDocument,
};

/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 *
 *
 * @example
 * ```ts
 * const query = graphql(`query GetUser($id: ID!) { user(id: $id) { name } }`);
 * ```
 *
 * The query argument is unknown!
 * Please regenerate the types.
 */
export function graphql(source: string): unknown;

/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  query FilteredChildren(\n    $sourceNodeId: ID!\n    $targetNodeId: ID!\n    $parentNode: ID!\n    $parentNodeType: NodeType!\n  ) {\n    hierarchicalGraph {\n      dependencySetForAggregatedDependency(\n        sourceNodeId: $sourceNodeId\n        targetNodeId: $targetNodeId\n      ) {\n        filteredChildren(\n          parentNode: $parentNode\n          parentNodeType: $parentNodeType\n        ) {\n          id\n          text\n          type\n          hasChildren\n        }\n      }\n    }\n  }\n"): (typeof documents)["\n  query FilteredChildren(\n    $sourceNodeId: ID!\n    $targetNodeId: ID!\n    $parentNode: ID!\n    $parentNodeType: NodeType!\n  ) {\n    hierarchicalGraph {\n      dependencySetForAggregatedDependency(\n        sourceNodeId: $sourceNodeId\n        targetNodeId: $targetNodeId\n      ) {\n        filteredChildren(\n          parentNode: $parentNode\n          parentNodeType: $parentNodeType\n        ) {\n          id\n          text\n          type\n          hasChildren\n        }\n      }\n    }\n  }\n"];
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  query FilteredDependencies(\n    $sourceNodeId: ID!\n    $targetNodeId: ID!\n    $selectedSourceIds: [ID!]!\n    $selectedTargetIds: [ID!]!\n  ) {\n    hierarchicalGraph {\n      dependencySetForAggregatedDependency(\n        sourceNodeId: $sourceNodeId\n        targetNodeId: $targetNodeId\n      ) {\n        size\n        filteredDependencies(\n          nodeSelection: [\n            { selectedNodeIds: $selectedSourceIds, selectedNodesType: SOURCE }\n            { selectedNodeIds: $selectedTargetIds, selectedNodesType: TARGET }\n          ]\n        ) {\n          markedSourceIds: referencedNodeIds(\n            nodeType: SOURCE\n            includedPredecessors: true\n          )\n          markedTargetIds: referencedNodeIds(\n            nodeType: TARGET\n            includedPredecessors: true\n          )\n        }\n      }\n    }\n  }\n"): (typeof documents)["\n  query FilteredDependencies(\n    $sourceNodeId: ID!\n    $targetNodeId: ID!\n    $selectedSourceIds: [ID!]!\n    $selectedTargetIds: [ID!]!\n  ) {\n    hierarchicalGraph {\n      dependencySetForAggregatedDependency(\n        sourceNodeId: $sourceNodeId\n        targetNodeId: $targetNodeId\n      ) {\n        size\n        filteredDependencies(\n          nodeSelection: [\n            { selectedNodeIds: $selectedSourceIds, selectedNodesType: SOURCE }\n            { selectedNodeIds: $selectedTargetIds, selectedNodesType: TARGET }\n          ]\n        ) {\n          markedSourceIds: referencedNodeIds(\n            nodeType: SOURCE\n            includedPredecessors: true\n          )\n          markedTargetIds: referencedNodeIds(\n            nodeType: TARGET\n            includedPredecessors: true\n          )\n        }\n      }\n    }\n  }\n"];
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  query DependencyEdges($sourceNodeId: ID!, $targetNodeId: ID!) {\n    hierarchicalGraph {\n      dependencySetForAggregatedDependency(\n        sourceNodeId: $sourceNodeId\n        targetNodeId: $targetNodeId\n      ) {\n        size\n        dependencies {\n          id\n          type\n          sourceNode {\n            id\n            text\n            type\n          }\n          targetNode {\n            id\n            text\n            type\n          }\n        }\n      }\n    }\n  }\n"): (typeof documents)["\n  query DependencyEdges($sourceNodeId: ID!, $targetNodeId: ID!) {\n    hierarchicalGraph {\n      dependencySetForAggregatedDependency(\n        sourceNodeId: $sourceNodeId\n        targetNodeId: $targetNodeId\n      ) {\n        size\n        dependencies {\n          id\n          type\n          sourceNode {\n            id\n            text\n            type\n          }\n          targetNode {\n            id\n            text\n            type\n          }\n        }\n      }\n    }\n  }\n"];
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  query NodeAdjacencyMatrix($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        children {\n          orderedAdjacencyMatrix {\n            orderedNodes {\n              id\n              text\n              type\n            }\n            cells {\n              row\n              column\n              value\n            }\n            stronglyConnectedComponents {\n              nodePositions\n            }\n          }\n        }\n      }\n    }\n  }\n"): (typeof documents)["\n  query NodeAdjacencyMatrix($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        children {\n          orderedAdjacencyMatrix {\n            orderedNodes {\n              id\n              text\n              type\n            }\n            cells {\n              row\n              column\n              value\n            }\n            stronglyConnectedComponents {\n              nodePositions\n            }\n          }\n        }\n      }\n    }\n  }\n"];
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  query NodesAdjacencyMatrix($ids: [ID!]!) {\n    hierarchicalGraph {\n      nodes(ids: $ids) {\n        orderedAdjacencyMatrix {\n          orderedNodes {\n            id\n            text\n            type\n          }\n          cells {\n            row\n            column\n            value\n          }\n          stronglyConnectedComponents {\n            nodePositions\n          }\n        }\n      }\n    }\n  }\n"): (typeof documents)["\n  query NodesAdjacencyMatrix($ids: [ID!]!) {\n    hierarchicalGraph {\n      nodes(ids: $ids) {\n        orderedAdjacencyMatrix {\n          orderedNodes {\n            id\n            text\n            type\n          }\n          cells {\n            row\n            column\n            value\n          }\n          stronglyConnectedComponents {\n            nodePositions\n          }\n        }\n      }\n    }\n  }\n"];
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  query RootNode {\n    hierarchicalGraph {\n      rootNode {\n        id\n        text\n        type\n        hasChildren\n      }\n    }\n  }\n"): (typeof documents)["\n  query RootNode {\n    hierarchicalGraph {\n      rootNode {\n        id\n        text\n        type\n        hasChildren\n      }\n    }\n  }\n"];
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  query NodeChildren($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        children {\n          nodes {\n            id\n            text\n            type\n            hasChildren\n          }\n        }\n      }\n    }\n  }\n"): (typeof documents)["\n  query NodeChildren($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        children {\n          nodes {\n            id\n            text\n            type\n            hasChildren\n          }\n        }\n      }\n    }\n  }\n"];
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  query NodeBasics($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        text\n        type\n        hasChildren\n      }\n    }\n  }\n"): (typeof documents)["\n  query NodeBasics($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        text\n        type\n        hasChildren\n      }\n    }\n  }\n"];
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  query NodeDetail($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        text\n        type\n        properties {\n          key\n          value\n        }\n      }\n    }\n  }\n"): (typeof documents)["\n  query NodeDetail($id: ID!) {\n    hierarchicalGraph {\n      node(id: $id) {\n        id\n        text\n        type\n        properties {\n          key\n          value\n        }\n      }\n    }\n  }\n"];

export function graphql(source: string) {
  return (documents as any)[source] ?? {};
}

export type DocumentType<TDocumentNode extends DocumentNode<any, any>> = TDocumentNode extends DocumentNode<  infer TType,  any>  ? TType  : never;