import { graphql, HttpResponse } from "msw";

import filteredChildrenFixture from "@/testing/fixtures/FilteredChildren.json";
import filteredDependenciesFixture from "@/testing/fixtures/FilteredDependencies.json";
import nodeAdjacencyMatrixFixture from "@/testing/fixtures/NodeAdjacencyMatrix.json";
import nodeBasicsFixture from "@/testing/fixtures/NodeBasics.json";
import nodeChildrenFixture from "@/testing/fixtures/NodeChildren.json";
import nodeDetailFixture from "@/testing/fixtures/NodeDetail.json";
import nodesAdjacencyMatrixFixture from "@/testing/fixtures/NodesAdjacencyMatrix.json";
import rootNodeFixture from "@/testing/fixtures/RootNode.json";

type FixtureEntry = {
  variables: Record<string, unknown>;
  data: unknown;
};
type FixtureFile = { operation: string; entries: FixtureEntry[] };

function stableKey(vars: Record<string, unknown>): string {
  const sorted: Record<string, unknown> = {};
  for (const k of Object.keys(vars).sort()) {
    sorted[k] = vars[k];
  }
  return JSON.stringify(sorted);
}

function findEntry(
  fixture: FixtureFile,
  variables: Record<string, unknown>,
): Record<string, unknown> {
  const key = stableKey(variables);
  const entry = fixture.entries.find((e) => stableKey(e.variables) === key);
  if (!entry) {
    throw new Error(
      `No fixture recorded for ${fixture.operation} with variables: ${JSON.stringify(variables)}. Run pnpm fixtures:record to update fixtures.`,
    );
  }
  return entry.data as Record<string, unknown>;
}

export const handlers = [
  graphql.query("RootNode", () =>
    HttpResponse.json({ data: findEntry(rootNodeFixture, {}) }),
  ),

  graphql.query("NodeBasics", ({ variables }) =>
    HttpResponse.json({
      data: findEntry(nodeBasicsFixture, variables as Record<string, unknown>),
    }),
  ),

  graphql.query("NodeDetail", ({ variables }) =>
    HttpResponse.json({
      data: findEntry(nodeDetailFixture, variables as Record<string, unknown>),
    }),
  ),

  graphql.query("NodeChildren", ({ variables }) =>
    HttpResponse.json({
      data: findEntry(
        nodeChildrenFixture,
        variables as Record<string, unknown>,
      ),
    }),
  ),

  graphql.query("NodeAdjacencyMatrix", ({ variables }) =>
    HttpResponse.json({
      data: findEntry(
        nodeAdjacencyMatrixFixture,
        variables as Record<string, unknown>,
      ),
    }),
  ),

  graphql.query("NodesAdjacencyMatrix", ({ variables }) =>
    HttpResponse.json({
      data: findEntry(
        nodesAdjacencyMatrixFixture,
        variables as Record<string, unknown>,
      ),
    }),
  ),

  graphql.query("FilteredDependencies", ({ variables }) =>
    HttpResponse.json({
      data: findEntry(
        filteredDependenciesFixture,
        variables as Record<string, unknown>,
      ),
    }),
  ),

  graphql.query("FilteredChildren", ({ variables }) =>
    HttpResponse.json({
      data: findEntry(
        filteredChildrenFixture,
        variables as Record<string, unknown>,
      ),
    }),
  ),

  // Default empty responses — tests that need real data override via worker.use()
  graphql.query("CrossReferencesNodePredecessors", ({ variables }) =>
    HttpResponse.json({
      data: {
        hierarchicalGraph: {
          node: { id: (variables as { id: string }).id, predecessors: [] },
        },
      },
    }),
  ),

  graphql.query("CrossReferencesUsedBy", () =>
    HttpResponse.json({
      data: {
        hierarchicalGraph: {
          node: { childrenFilteredByReferencedNodes: { nodes: [] } },
        },
      },
    }),
  ),

  graphql.query("CrossReferencesUses", () =>
    HttpResponse.json({
      data: {
        hierarchicalGraph: {
          node: { childrenFilteredByReferencingNodes: { nodes: [] } },
        },
      },
    }),
  ),
];
