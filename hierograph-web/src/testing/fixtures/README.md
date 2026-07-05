# GraphQL Test Fixtures

This directory holds **recorded GraphQL responses** used by the browser tests.
They let the whole suite run without a live backend: MSW intercepts every
GraphQL request and answers it from these JSON files.

## Where the data comes from

The fixtures are recorded **once** against a live backend and committed. The
backend is the deterministic **fixture-app**: its Neo4j store and the MCP
server expose the GraphQL API at `http://localhost:8080/graphql`. Because the
fixture-app never changes, re-recording produces bit-identical output (see
"Determinism" below). Expected values for the fixture-app live in
`hierograph/examples/fixture-app/EXPECTED_VALUES.md`.

## What is in each file

One JSON file per GraphQL operation (`RootNode.json`, `NodeChildren.json`,
`NodeAdjacencyMatrix.json`, …). Each file is:

```jsonc
{
  "operation": "NodeChildren",
  "entries": [
    { "variables": { "id": "100" }, "data": { /* the recorded response */ } },
    { "variables": { "id": "101" }, "data": { /* ... */ } }
  ]
}
```

An operation called with different variables produces multiple `entries` — one
per distinct variable set.

**Never edit these JSON files by hand.** They are generated data; hand edits are
overwritten on the next recording and easily drift from the real schema.

## How the tests consume them

`../msw/handlers.ts` registers one MSW handler per operation. For an incoming
request it looks up the entry whose `variables` match (by a stable,
key-sorted comparison) and returns its `data`. If no entry matches, the handler
throws:

> `No fixture recorded for <Operation> with variables: … Run pnpm fixtures:record to update fixtures.`

That error is the signal that a test needs data which has not been recorded yet
— re-record (and, if it is a brand-new operation, add a handler in
`handlers.ts`).

## How they are generated

`record-fixtures.ts` (in this directory) walks the fixture-app graph and records
every operation + variable combination it encounters:

1. Fetch `RootNode`, then **BFS** over the hierarchy via `NodeChildren`.
2. For every node, record `NodeBasics` and `NodeDetail`.
3. For every node with children, record its `NodeAdjacencyMatrix` (and
   `NodesAdjacencyMatrix` when it has ≥ 2 children).
4. For every non-diagonal matrix cell with a dependency, record the dependency
   detail (`FilteredDependencies`) and recurse the source/target subtrees
   (`FilteredChildren`).

Each response is written to `<OperationName>.json` in this directory.

### Determinism

Recording is deterministic and idempotent: variable sets are de-duplicated,
object keys are sorted (`stableKey`), and entries are sorted by their variables
before writing. Re-recording against the unchanged fixture-app yields a
byte-identical diff — so a non-empty diff means the schema or the fixture-app
data actually changed.

## When to re-record

Re-record when **either** side of the recorded contract changes:

- the GraphQL **schema / documents** change (new fields, new operations,
  changed shapes), or
- the **fixture-app data** changes.

## How to re-record

Start the fixture-app backend first (Neo4j store + MCP GraphQL server — see the
fixture-app loop in the workspace `CLAUDE.md`), then from `hierograph-web/`:

```bash
pnpm fixtures:record
```

The GraphQL endpoint defaults to `http://localhost:8080/graphql`; override it
with `HIEROGRAPH_GRAPHQL_URL` if the server runs elsewhere.

For the broader testing setup (unit vs. browser projects, MSW lifecycle), see
`../../../docs/testing-strategy.md`.
