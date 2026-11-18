// Records the GraphQL fixtures the browser tests run against, from a running
// Hierograph server serving the fixture-app (see README.md in this directory).
//
//   pnpm fixtures:record                      the default scenario: a loaded graph
//   pnpm fixtures:record --scenario no-graph  a server without a graph
//
// Writes `<Operation>.json` (default scenario) or `<Operation>.<scenario>.json`
// next to this script. Responses are normalised (normalize.ts), so re-recording
// unchanged data yields identical files.

import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { parseArgs } from "node:util";

import { GraphQLClient } from "graphql-request";

import {
  GraphStatusDocument,
  HierarchyTreeChildrenDocument,
  HierarchyTreeRootDocument,
  HierarchyViewsDocument,
  StartScreenDocument,
} from "../../graphql/generated/graphql.ts";
import { normalize } from "./normalize.ts";

const GRAPHQL_URL =
  process.env.HIEROGRAPH_GRAPHQL_URL ?? "http://localhost:8765/graphql";
const FIXTURES_DIR = import.meta.dirname;

// The operations without variables, recorded in every scenario. A new
// operation the tests need is added here, or below like the tree queries when
// it takes variables (and a handler in ../msw/handlers.ts).
const OPERATIONS = [
  { name: "GraphStatus", document: GraphStatusDocument },
  { name: "HierarchyViews", document: HierarchyViewsDocument },
  { name: "StartScreen", document: StartScreenDocument },
] as const;

const { values } = parseArgs({
  options: { scenario: { type: "string", default: "default" } },
});
const scenario = values.scenario;
const client = new GraphQLClient(GRAPHQL_URL);

type Entry = { variables: Record<string, unknown>; data: unknown };

function write(name: string, entries: Entry[]) {
  const file = resolve(
    FIXTURES_DIR,
    scenario === "default" ? `${name}.json` : `${name}.${scenario}.json`,
  );
  const fixture = { operation: name, scenario, entries };
  writeFileSync(file, `${JSON.stringify(fixture, null, 2)}\n`);
  console.log(`Recorded ${name} (${scenario}) → ${file}`);
}

for (const operation of OPERATIONS) {
  const data = await client.request<Record<string, unknown>>(
    operation.document,
  );
  write(operation.name, [{ variables: {}, data: normalize(data as never) }]);
}

// The hierarchy tree, only with a graph: the complete tree (the fixture-app is
// small), in the scanned hierarchy and in the `layers` view, whose groups sit
// deep inside the module.
type TreeNode = { id: string; hasChildren: boolean };

if (scenario === "default") {
  const roots: Entry[] = [];
  const children: Entry[] = [];
  const expand = async (view: string | null, nodes: TreeNode[]) => {
    for (const node of nodes) {
      if (!node.hasChildren) continue;
      const variables = { view, id: node.id };
      const data = await client.request<{
        hierarchicalGraph: { node: { children: { nodes: TreeNode[] } } };
      }>(HierarchyTreeChildrenDocument, variables);
      children.push({ variables, data: normalize(data as never) });
      await expand(view, data.hierarchicalGraph.node.children.nodes);
    }
  };
  for (const view of [null, "layers"]) {
    const variables = { view };
    const data = await client.request<{
      hierarchicalGraph: { rootNode: { children: { nodes: TreeNode[] } } };
    }>(HierarchyTreeRootDocument, variables);
    roots.push({ variables, data: normalize(data as never) });
    await expand(view, data.hierarchicalGraph.rootNode.children.nodes);
  }
  write("HierarchyTreeRoot", roots);
  write("HierarchyTreeChildren", children);
}
