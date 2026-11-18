# GraphQL test fixtures

This directory holds **recorded GraphQL responses** the browser tests run
against. MSW (`../msw/handlers.ts`) answers every GraphQL request from these
files, so the tests need no running server.

**Never edit the JSON files by hand.** They are generated: re-record them.

## What is recorded

One file per operation and scenario:

| File                        | Scenario   | Recorded from                    |
| --------------------------- | ---------- | -------------------------------- |
| `<Operation>.json`          | `default`  | a server serving the fixture-app |
| `<Operation>.no-graph.json` | `no-graph` | a server without a graph         |

Each file is `{ operation, scenario, entries: [{ variables, data }] }`. The
operations are listed in `record-fixtures.ts`: `GraphStatus` and
`HierarchyViews` (the frame) and `StartScreen` (the start screen), without
variables and in every scenario; and the hierarchy tree's `HierarchyTreeRoot`
and `HierarchyTreeChildren`, with variables and only in the `default` scenario.
They hold the complete tree of the fixture-app, in the scanned hierarchy and in
the `layers` view, one entry per expandable node, each level asked for as the
screen asks for it (in dependency order, a type's members in source order).

The `updating` scenario (the graph updater busy) can't be recorded reliably; the
handlers derive it from `default` by changing only the graph's state.

## Where the data comes from

The server serves the **fixture-app** (`examples/fixture-app/`), a deterministic
example project with known structure, its hierarchy views (one valid, one
deliberately invalid) and a declared but uncompiled test artifact.

Responses are **normalised** before writing (`normalize.ts`): the workspace
path becomes `/fixture-app`, and times, process id, port, version, commit,
URL origins and the JVM's metrics get fixed values. So re-recording unchanged data yields identical
files, and a diff means the schema, the queries or the fixture-app changed.

## Recording

Prerequisites: the Hierograph build installed (`mvn install -DskipTests` at the
repository root), and port 8765 free.

1. **Write the fixture-app's manifest:**

   ```bash
   cd examples/fixture-app
   mvn compile io.hierograph:hierograph-maven-plugin:0.4.0-SNAPSHOT:manifest
   ```

2. **Record the `default` scenario.** Start the server in `examples/fixture-app`
   and, once it is ready, record from `hierograph-web/`:

   ```bash
   java -jar ../../hierograph-server/io.hierograph.server/target/io.hierograph.server-0.4.0-SNAPSHOT-exec.jar
   pnpm fixtures:record
   ```

3. **Record the `no-graph` scenario.** Stop the server, start it again pointed
   at a manifest that doesn't exist, and record:

   ```bash
   java -jar ../../hierograph-server/io.hierograph.server/target/io.hierograph.server-0.4.0-SNAPSHOT-exec.jar \
     --hierograph.graph.manifest=.hierograph/no-manifest.json
   pnpm fixtures:record --scenario no-graph
   ```

Another server URL: `HIEROGRAPH_GRAPHQL_URL=http://localhost:8080/graphql`.

## Adding an operation

1. Write the query with `graphql()` in the vertical's `queries.ts` and run
   `pnpm codegen`.
2. Add it to `OPERATIONS` in `record-fixtures.ts`, and to `RECORDED` in
   `../msw/handlers.ts`.
3. Record both scenarios.

If a test needs data the fixture-app doesn't have, grow the fixture-app in a
new top-level package (see its README) and re-record; never edit a fixture.
