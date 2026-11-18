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
  StartScreenDocument,
} from "../../graphql/generated/graphql.ts";
import { normalize } from "./normalize.ts";

const GRAPHQL_URL =
  process.env.HIEROGRAPH_GRAPHQL_URL ?? "http://localhost:8765/graphql";
const FIXTURES_DIR = import.meta.dirname;

// The operations to record, all without variables. A new operation the tests
// need is added here (and a handler in ../msw/handlers.ts).
const OPERATIONS = [
  { name: "GraphStatus", document: GraphStatusDocument },
  { name: "StartScreen", document: StartScreenDocument },
] as const;

const { values } = parseArgs({
  options: { scenario: { type: "string", default: "default" } },
});
const scenario = values.scenario;
const client = new GraphQLClient(GRAPHQL_URL);

for (const operation of OPERATIONS) {
  const data = await client.request<Record<string, unknown>>(
    operation.document,
  );
  const file = resolve(
    FIXTURES_DIR,
    scenario === "default"
      ? `${operation.name}.json`
      : `${operation.name}.${scenario}.json`,
  );
  const fixture = {
    operation: operation.name,
    scenario,
    entries: [{ variables: {}, data: normalize(data as never) }],
  };
  writeFileSync(file, `${JSON.stringify(fixture, null, 2)}\n`);
  console.log(`Recorded ${operation.name} (${scenario}) → ${file}`);
}
