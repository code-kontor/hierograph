import { mkdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

import { GraphQLClient } from "graphql-request";

import type {
  FilteredChildrenQuery,
  FilteredDependenciesQuery,
  NodeAdjacencyMatrixQuery,
  NodeBasicsQuery,
  NodeChildrenQuery,
  NodeDetailQuery,
  NodesAdjacencyMatrixQuery,
  RootNodeQuery,
} from "../../graphql/generated/graphql.ts";
import {
  FilteredChildrenDocument,
  FilteredDependenciesDocument,
  NodeAdjacencyMatrixDocument,
  NodeBasicsDocument,
  NodeChildrenDocument,
  NodeDetailDocument,
  NodesAdjacencyMatrixDocument,
  RootNodeDocument,
} from "../../graphql/generated/graphql.ts";

const GRAPHQL_URL =
  process.env.HIEROGRAPH_GRAPHQL_URL ?? "http://localhost:8080/graphql";
// This script lives in the fixtures directory, so the recorded JSON files are
// written next to it.
const FIXTURES_DIR = import.meta.dirname;

const client = new GraphQLClient(GRAPHQL_URL);

type Variables = Record<string, unknown>;
type Entry = { variables: Variables; data: unknown };

function stableKey(vars: Variables): string {
  const sorted: Variables = {};
  for (const k of Object.keys(vars).sort()) {
    sorted[k] = vars[k];
  }
  return JSON.stringify(sorted);
}

const store = new Map<string, Map<string, Entry>>();

async function record<TResult>(
  operationName: string,
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  document: any,
  variables: Variables = {},
): Promise<TResult | null> {
  const key = stableKey(variables);
  let opMap = store.get(operationName);
  if (!opMap) {
    opMap = new Map();
    store.set(operationName, opMap);
  }
  if (opMap.has(key)) {
    return opMap.get(key)!.data as TResult;
  }
  try {
    const data = await client.request<TResult>(document, variables);
    opMap.set(key, { variables, data });
    return data;
  } catch (err: unknown) {
    console.warn(
      `Skipping ${operationName}(${JSON.stringify(variables)}): ${err instanceof Error ? err.message.split("\n")[0] : String(err)}`,
    );
    return null;
  }
}

type NodeSummary = { id: string; text: string; hasChildren: boolean };

async function recordFilteredChildrenRecursive(
  sourceNodeId: string,
  targetNodeId: string,
  parentNode: string,
  parentNodeType: "SOURCE" | "TARGET",
  visited: Set<string>,
): Promise<void> {
  const visitKey = `${sourceNodeId}:${targetNodeId}:${parentNode}:${parentNodeType}`;
  if (visited.has(visitKey)) return;
  visited.add(visitKey);

  const result = await record<FilteredChildrenQuery>(
    "FilteredChildren",
    FilteredChildrenDocument,
    { sourceNodeId, targetNodeId, parentNode, parentNodeType },
  );
  if (!result) return;

  const children =
    result.hierarchicalGraph?.dependencySetForAggregatedDependency
      ?.filteredChildren ?? [];
  for (const child of children) {
    if (child.hasChildren) {
      await recordFilteredChildrenRecursive(
        sourceNodeId,
        targetNodeId,
        child.id,
        parentNodeType,
        visited,
      );
    }
  }
}

async function recordDependencyData(
  sourceNodeId: string,
  targetNodeId: string,
  visitedDeps: Set<string>,
): Promise<void> {
  const depKey = `${sourceNodeId}:${targetNodeId}`;
  if (visitedDeps.has(depKey)) return;
  visitedDeps.add(depKey);

  await record<FilteredDependenciesQuery>(
    "FilteredDependencies",
    FilteredDependenciesDocument,
    {
      sourceNodeId,
      targetNodeId,
      selectedSourceIds: [],
      selectedTargetIds: [],
    },
  );

  const filteredChildrenVisited = new Set<string>();
  await recordFilteredChildrenRecursive(
    sourceNodeId,
    targetNodeId,
    sourceNodeId,
    "SOURCE",
    filteredChildrenVisited,
  );
  await recordFilteredChildrenRecursive(
    sourceNodeId,
    targetNodeId,
    targetNodeId,
    "TARGET",
    filteredChildrenVisited,
  );

  // Cover the marking-with-selection path: record one FilteredDependencies
  // entry per direct child of the source and target roots, simulating a single
  // node being clicked in each tree. Both re-requests hit the FilteredChildren
  // cache populated above, so no extra round-trips are made.
  await recordDirectChildSelections(
    sourceNodeId,
    targetNodeId,
    sourceNodeId,
    "SOURCE",
  );
  await recordDirectChildSelections(
    sourceNodeId,
    targetNodeId,
    targetNodeId,
    "TARGET",
  );
}

async function recordDirectChildSelections(
  sourceNodeId: string,
  targetNodeId: string,
  parentNode: string,
  parentNodeType: "SOURCE" | "TARGET",
): Promise<void> {
  const result = await record<FilteredChildrenQuery>(
    "FilteredChildren",
    FilteredChildrenDocument,
    { sourceNodeId, targetNodeId, parentNode, parentNodeType },
  );
  const children =
    result?.hierarchicalGraph?.dependencySetForAggregatedDependency
      ?.filteredChildren ?? [];
  for (const child of children) {
    await record<FilteredDependenciesQuery>(
      "FilteredDependencies",
      FilteredDependenciesDocument,
      {
        sourceNodeId,
        targetNodeId,
        selectedSourceIds: parentNodeType === "SOURCE" ? [child.id] : [],
        selectedTargetIds: parentNodeType === "TARGET" ? [child.id] : [],
      },
    );
  }
}

async function run(): Promise<void> {
  const rootResult = await record<RootNodeQuery>("RootNode", RootNodeDocument);
  if (!rootResult) throw new Error("RootNode query failed");
  const rootNode = rootResult.hierarchicalGraph?.rootNode;
  if (!rootNode) throw new Error("No root node returned");

  const queue: NodeSummary[] = [rootNode];
  const visitedNodes = new Set<string>();
  const visitedDeps = new Set<string>();

  while (queue.length > 0) {
    const node = queue.shift()!;
    if (visitedNodes.has(node.id)) continue;
    visitedNodes.add(node.id);

    await record<NodeBasicsQuery>("NodeBasics", NodeBasicsDocument, {
      id: node.id,
    });
    await record<NodeDetailQuery>("NodeDetail", NodeDetailDocument, {
      id: node.id,
    });

    if (!node.hasChildren) continue;

    const childrenResult = await record<NodeChildrenQuery>(
      "NodeChildren",
      NodeChildrenDocument,
      { id: node.id },
    );
    const children =
      childrenResult?.hierarchicalGraph?.node?.children?.nodes ?? [];
    for (const child of children) {
      queue.push(child);
    }

    const matrixResult = await record<NodeAdjacencyMatrixQuery>(
      "NodeAdjacencyMatrix",
      NodeAdjacencyMatrixDocument,
      { id: node.id },
    );
    const matrix =
      matrixResult?.hierarchicalGraph?.node?.children?.orderedAdjacencyMatrix;
    if (!matrix) continue;

    if (children.length >= 2) {
      const sortedIds = [...children.map((c) => c.id)].sort();
      await record<NodesAdjacencyMatrixQuery>(
        "NodesAdjacencyMatrix",
        NodesAdjacencyMatrixDocument,
        { ids: sortedIds },
      );
    }

    for (const cell of matrix.cells) {
      if (cell.value > 0 && cell.row !== cell.column) {
        // API convention: sourceNodeId = orderedNodes[cell.row].id
        const sourceNodeId = matrix.orderedNodes[cell.row]?.id;
        const targetNodeId = matrix.orderedNodes[cell.column]?.id;
        if (sourceNodeId && targetNodeId) {
          await recordDependencyData(sourceNodeId, targetNodeId, visitedDeps);
        }
      }
    }
  }

  mkdirSync(FIXTURES_DIR, { recursive: true });

  for (const [operationName, entries] of store) {
    const sortedEntries = [...entries.values()].sort((a, b) =>
      stableKey(a.variables).localeCompare(stableKey(b.variables)),
    );
    const fixture = {
      operation: operationName,
      entries: sortedEntries.map((e) => ({
        variables: e.variables,
        data: e.data,
      })),
    };
    const path = resolve(FIXTURES_DIR, `${operationName}.json`);
    writeFileSync(path, JSON.stringify(fixture, null, 2) + "\n");
    console.log(`Wrote ${path} (${sortedEntries.length} entries)`);
  }
}

run().catch((err: unknown) => {
  console.error(err);
  process.exit(1);
});
