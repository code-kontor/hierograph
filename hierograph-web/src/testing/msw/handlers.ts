import { graphql, HttpResponse, type RequestHandler } from "msw";

import dependencyPickPath from "@/testing/fixtures/DependencyPickPath.json";
import dependencySet from "@/testing/fixtures/DependencySet.json";
import dependencySetChildren from "@/testing/fixtures/DependencySetChildren.json";
import dependencySetPage from "@/testing/fixtures/DependencySetPage.json";
import graphStatus from "@/testing/fixtures/GraphStatus.json";
import graphStatusNoGraph from "@/testing/fixtures/GraphStatus.no-graph.json";
import hierarchyArcs from "@/testing/fixtures/HierarchyArcs.json";
import hierarchyTreeChildren from "@/testing/fixtures/HierarchyTreeChildren.json";
import hierarchyTreeRoot from "@/testing/fixtures/HierarchyTreeRoot.json";
import hierarchyViews from "@/testing/fixtures/HierarchyViews.json";
import hierarchyViewsNoGraph from "@/testing/fixtures/HierarchyViews.no-graph.json";
import serverStatus from "@/testing/fixtures/ServerStatus.json";
import serverStatusNoGraph from "@/testing/fixtures/ServerStatus.no-graph.json";
import structureCells from "@/testing/fixtures/StructureCells.json";
import structureGiven from "@/testing/fixtures/StructureGiven.json";
import structureLevel from "@/testing/fixtures/StructureLevel.json";
import structureRoot from "@/testing/fixtures/StructureRoot.json";
import viewExclusions from "@/testing/fixtures/ViewExclusions.json";
import viewExclusionsNoGraph from "@/testing/fixtures/ViewExclusions.no-graph.json";

// GraphQL handlers answering from the recorded fixtures
// (`src/testing/fixtures/`, see the README there). Every unhandled request
// fails the test (`onUnhandledRequest: "error"` in `setup.ts`).
//
// Scenarios:
// - `default`: the fixture-app with its graph loaded (recorded).
// - `no-graph`: a server without a graph (recorded).
// - `updating`: the default recording while the graph updater rescans. It
//   can't be recorded reliably, so it is derived from `default` by changing
//   only the graph's state.
//
// The default scenario is active in every test; a test switches with
// `worker.use(...handlersFor("no-graph"))`.

export type Scenario = "default" | "no-graph" | "updating";

type FixtureEntry = { variables: Record<string, unknown>; data: unknown };
type FixtureFile = { operation: string; entries: FixtureEntry[] };

// Per operation, the recorded scenarios. The tree and structure queries exist
// only with a graph; without one the screens never ask for them.
const RECORDED: Record<
  string,
  Partial<Record<"default" | "no-graph", FixtureFile>>
> = {
  GraphStatus: { default: graphStatus, "no-graph": graphStatusNoGraph },
  DependencyPickPath: { default: dependencyPickPath },
  DependencySet: { default: dependencySet },
  DependencySetChildren: { default: dependencySetChildren },
  DependencySetPage: { default: dependencySetPage },
  HierarchyViews: {
    default: hierarchyViews,
    "no-graph": hierarchyViewsNoGraph,
  },
  ServerStatus: { default: serverStatus, "no-graph": serverStatusNoGraph },
  ViewExclusions: {
    default: viewExclusions,
    "no-graph": viewExclusionsNoGraph,
  },
  HierarchyTreeRoot: { default: hierarchyTreeRoot },
  HierarchyTreeChildren: { default: hierarchyTreeChildren },
  HierarchyArcs: { default: hierarchyArcs },
  StructureRoot: { default: structureRoot },
  StructureLevel: { default: structureLevel },
  StructureCells: { default: structureCells },
  StructureGiven: { default: structureGiven },
};

export function handlersFor(scenario: Scenario): RequestHandler[] {
  return Object.keys(RECORDED).map((operation) =>
    graphql.query(operation, ({ variables }) =>
      HttpResponse.json({ data: fixtureData(operation, scenario, variables) }),
    ),
  );
}

export const handlers = handlersFor("default");

export function fixtureData(
  operation: string,
  scenario: Scenario,
  variables: Record<string, unknown> = {},
): Record<string, unknown> {
  if (scenario === "updating") {
    return updating(fixtureData(operation, "default", variables));
  }
  const fixture = RECORDED[operation][scenario];
  if (!fixture) {
    throw new Error(`No ${operation} recorded for the ${scenario} scenario.`);
  }
  const key = stableKey(variables);
  const entry = fixture.entries.find((e) => stableKey(e.variables) === key);
  if (!entry) {
    throw new Error(
      `No fixture recorded for ${operation} (${scenario}) with variables: ${JSON.stringify(variables)}. Run pnpm fixtures:record to update fixtures.`,
    );
  }
  return entry.data as Record<string, unknown>;
}

// The same response while the graph updater rescans: state UPDATING, the rest
// (the graph still in service) unchanged.
function updating(data: Record<string, unknown>): Record<string, unknown> {
  const copy = structuredClone(data) as {
    server?: { graph?: { state: string; update: string | null } };
  };
  if (copy.server?.graph) {
    copy.server.graph.state = "UPDATING";
    copy.server.graph.update = "rescanning";
  }
  return copy;
}

function stableKey(vars: Record<string, unknown>): string {
  const sorted: Record<string, unknown> = {};
  for (const k of Object.keys(vars).sort()) {
    sorted[k] = vars[k];
  }
  return JSON.stringify(sorted);
}
