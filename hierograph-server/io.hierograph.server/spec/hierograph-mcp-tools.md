# Hierograph MCP Tools — Specification

`io.hierograph.mcp.server` is Hierograph's **architectural query surface for language
models**: a set of fourteen MCP tools that answer structural and dependency questions
about a scanned JVM codebase. Every architectural question an assistant asks Hierograph
(what is in this module, what depends on this type, is there a path from here to there)
goes through one of these tools.

This document explains why the tool surface exists, the concepts all tools share (node
identity, kinds, node references, pagination, errors), and how the tools fit together.
Each tool has its own specification, linked from §4.

---

## Contents

1. [Purpose](#1-purpose)
2. [Where it sits](#2-where-it-sits)
3. [The graph as the tools see it](#3-the-graph-as-the-tools-see-it)
4. [The tools](#4-the-tools)
5. [Parameters](#5-parameters)
6. [Node references](#6-node-references)
7. [Pagination](#7-pagination)
8. [Errors](#8-errors)
9. [Working with the tools](#9-working-with-the-tools)
10. [Guarantees](#10-guarantees)
11. [Boundaries](#11-boundaries)

---

## 1. Purpose

A language model that reasons about a codebase's architecture needs facts it cannot get
by reading source files one at a time: which modules exist, how strongly two packages
are coupled, what a change to one type ripples into. The hierarchical graph holds those
facts (see the
[hierarchical graph model specification](../../../hierograph-hierarchicalgraph/io.hierograph.hierarchicalgraph.model/spec/hierarchicalgraph-model.md)).
The tool surface turns them into answers sized for a model's context window.

The surface has three design goals:

**Answers, not dumps.** Every tool returns a bounded response with a `summary` computed
over the *full* result, however many items the page carries. A caller that only needs a
ranking or a count reads the summary of a one-item page. Nothing forces the model to
page through thousands of edges to learn which target dominates.

**One identity everywhere.** Every node is addressed by one numeric `id`, and every tool
accepts and returns that same id. A result of one tool is always a valid input to the
next, so calls chain without translation.

**Fail with a way forward.** Every error carries a machine-readable `code` and, for most
tools, a `recovery` sentence that names the next call to make. A model that hits an
error knows what to do next without guessing.

---

## 2. Where it sits

```
   Build manifest / Parquet snapshot
                  │
                  ▼
   ┌──────────────────────────────────┐
   │   HierarchicalGraphService       │  loads, watches, reloads the graph;
   │   (io.hierograph.server.         │  owns the search index, the data hash
   │    graphservice)                 │  and the detail-level snapshot
   └────────────────┬─────────────────┘
                    │
                    ▼
   ┌──────────────────────────────────┐
   │   MCP tool surface               │  14 tools, node references,
   │   (io.hierograph.mcp.server)     │  pagination, errors
   └────────────────┬─────────────────┘
                    │  MCP (JSON over HTTP)
                    ▼
       Language-model client (assistant, agent, IDE)
```

**Producers.** The
[hierarchical graph service](../../io.hierograph.server.graphservice/spec/hierarchical-graph-service.md)
supplies the loaded model, the node search index, the data hash used by cursors (§7.2)
and the detail-level snapshot read by the detail tools. The tools never read build
output or Parquet files on their own.

**Consumers.**

- **Language-model clients** call the tools over MCP. Each tool's `description` is
  written for them: it states what the tool answers and which tool to use instead.
- **Hierograph skills** (`hierograph-structure`, `hierograph-extract`) script sequences
  of tool calls for recurring tasks such as drawing a dependency structure matrix or
  planning a module extraction.

---

## 3. The graph as the tools see it

### 3.1 Nodes and the containment tree

The tools see one tree of **nodes**. Each node has a numeric **id**, a **name**, a
**qualified name** and a **kind**:

```
<root>                                   (synthetic, no kind, name "")
├── billing-core                         java.module
│   ├── com                              java.package
│   │   └── example                      java.package
│   │       ├── billing                  java.package  qualified com.example.billing
│   │       │   ├── Invoice              java.class
│   │       │   │   ├── …Money total()   java.method
│   │       │   │   └── …Ledger ledger   java.field
│   │       │   ├── Invoice$Line         java.class
│   │       │   └── Ledger               java.interface
│   │       └── money
│   │           └── Money                java.record
└── External Types                       java.module   (synthetic)
    └── java ── math
                └── BigDecimal           external.type
```

**The root** is synthetic and has no kind: a NodeRef reports `kind` `null`, a slim
node map entry reports `"Unknown"`. Modules carry its id as `parent_id`, with
`parent_kind` `null`. Tools that take a scope accept the root's id, which stands for the whole graph.

**Modules** are named by their artifact id; their `qualified_name` is the Maven
coordinate `com.example:billing-core:1.4.0`, which tells two modules with the same
artifact id apart. The synthetic `External Types` module uses its name as its
qualified name.

**Packages** nest by segment: `com.example.billing` is the package `billing` inside
`example` inside `com`. A package's `name` is its last segment and its
`qualified_name` is the dotted path.

**Nested types** sit in their package, beside their enclosing type, and keep the
binary `$` name: `Invoice$Line`. A type's children are its members only.

**Members** are named by their signature, because Java overloads names:
`name` is `com.example.money.Money total()` and `qualified_name` is
`com.example.billing.Invoice#com.example.money.Money total()`. Return and parameter
types are fully qualified, so the signature is unambiguous. Constructors are members
of kind `java.method` named `void <init>(…)`, for example
`void <init>(com.example.billing.Ledger)`.

**External types** are types the scanned code references but does not contain, such
as `java.math.BigDecimal`. They live under the synthetic module `External Types`, have
kind `external.type` and no members. They are first-class dependency endpoints: a
dependency on `BigDecimal` is as visible as a dependency on `Money`.

### 3.2 Kinds

| Kind | Meaning |
|------|---------|
| `java.module` | A build module, plus the synthetic `External Types` module |
| `java.package` | One package segment |
| `java.class` | A class |
| `java.interface` | An interface |
| `java.enum` | An enum |
| `java.record` | A record |
| `java.annotation` | An annotation type |
| `java.method` | A method or constructor |
| `java.field` | A field |
| `external.type` | A type referenced from outside the scanned code |

Two further kinds appear only inside responses and never as tree nodes:
`java.primitive` for primitive types such as `int` and `void` (always with `id: null`),
and `java.constructor`, which the detail tools report for a `<init>` member.

**Kind filters** accept the kind values above except `external.type`, plus three
**group aliases** that expand to several kinds:

| Alias | Expands to |
|-------|------------|
| `types` | `java.class`, `java.interface`, `java.enum`, `java.record`, `java.annotation` |
| `members` | `java.method`, `java.field` |
| `packages` | `java.package` |

The `types` alias does not include `external.type`. External types are kept out of
kind-filtered results because they have no members or metadata to browse.

Every tool with a `kindFilter` (`find_node`, `list_children`, `list_descendants`,
`affected_by`) parses it the same way. An absent or empty filter is no filter. A value
that is neither a kind nor an alias fails the call with `INVALID_KIND`, which names
the invalid values and lists the valid ones, so the caller can correct the filter
without a second lookup:

```json
{ "error": {
    "code": "INVALID_KIND",
    "message": "Unknown kind: 'classes'. Valid kinds: java.module, java.package, java.class, java.interface, java.enum, java.record, java.annotation, java.method, java.field. Group aliases: types, members, packages.",
    "invalid_values": ["classes"],
    "valid_kinds": ["java.module", "java.package", "java.class", "java.interface", "java.enum", "java.record", "java.annotation", "java.method", "java.field"],
    "valid_aliases": ["types", "members", "packages"],
    "recovery": "Use values from valid_kinds or valid_aliases, or omit kindFilter." } }
```

With several invalid values the message starts `Unknown kinds:`. The pseudo-kinds
`java.primitive` and `java.constructor` are not valid filter values.

### 3.3 Two levels of dependency

The tools report dependencies at two levels of granularity.

**Type level.** A **type-level dependency** is one weighted edge from one type to
another. It records *that* `Invoice` depends on `Money` and how strongly, plus four
**attribute flags**. The **weight** is the number of references from the source type
and its members to the target: three calls and one field of type `Money` give
`Invoice → Money` a weight of 4.

| Flag | Set when |
|------|----------|
| `is_extends` | the source type extends the target |
| `is_implements` | the source type implements the target |
| `is_annotated_by` | the source type itself is annotated with the target |
| `is_depends_on_other` | any other reference: calls, field and parameter types, member annotations, … |

The flags are not exclusive. A class that extends `Ledger` and also calls one of its
methods has both `is_extends` and `is_depends_on_other`.

Rolled up the tree, type-level edges become **aggregated dependencies** between
packages and modules, whose weight is the sum of the underlying weights. This is the
level for architecture questions.

**Detail level.** A **detail-level dependency** is one concrete reference between
members, with a relationship and a source line: `Invoice.total()` *invokes*
`Money.plus(Money)` at line 42. It answers *how* one type uses another. The
relationship vocabulary is listed in the
[`outgoing_dependencies` specification](hierograph-tool-outgoing-dependencies.md).

---

## 4. The tools

| Tool | Category | Answers | Paginated |
|------|----------|---------|-----------|
| [`graph_overview`](hierograph-tool-graph-overview.md) | Orientation | What is in the graph, and what do kinds and relationships mean? | no |
| [`find_node`](hierograph-tool-find-node.md) | Navigation | Which node has this name? | no (capped) |
| [`list_children`](hierograph-tool-list-children.md) | Navigation | What is directly inside this node? | no (capped) |
| [`list_descendants`](hierograph-tool-list-descendants.md) | Navigation | What is anywhere inside this node? | yes |
| [`aggregated_dependencies`](hierograph-tool-aggregated-dependencies.md) | Dependencies | How strongly does each source depend on each target? | no (capped input) |
| [`pairwise_dependencies`](hierograph-tool-pairwise-dependencies.md) | Dependencies | How are these nodes coupled to each other, and are they layered? | yes |
| [`outgoing_dependencies`](hierograph-tool-outgoing-dependencies.md) | Dependencies | What does this node depend on, type by type or reference by reference? | yes |
| [`incoming_dependencies`](hierograph-tool-incoming-dependencies.md) | Dependencies | What depends on this node? | yes |
| [`affected_by`](hierograph-tool-affected-by.md) | Reachability | What is transitively affected by, or needed by, this node? | yes |
| [`find_dependency_path`](hierograph-tool-find-dependency-path.md) | Reachability | Is there a dependency chain from here to there, and what does it look like? | no (capped) |
| [`type_details`](hierograph-tool-type-details.md) | Entity detail | What is this type's declaration? | no |
| [`method_details`](hierograph-tool-method-details.md) | Entity detail | What is this method's signature? | no |
| [`field_details`](hierograph-tool-field-details.md) | Entity detail | What is this field, and which methods use it? | no |
| [`reload_graph`](hierograph-tool-reload-graph.md) | Maintenance | Rebuild the graph from its source now. | no |

Each tool description starts with its category in brackets, for example
`[Discovery and navigation]`, so a client that lists the tools sees them grouped.

---

## 5. Parameters

Parameter names are the camelCase names of the Kotlin parameters: `nodeId`,
`kindFilter`, `namePattern`, `fromId`, `detailLevel`. A tool's JSON schema lists them
exactly as the client must send them. Response fields, by contrast, are snake_case
(`qualified_name`, `next_cursor`). The two conventions apply to different things and do
not mix: parameters are camelCase, response keys are snake_case.

Node ids are JSON integers (Kotlin `Long`). List parameters are JSON arrays. Parameters
whose values form a closed set (`direction`, `detailLevel`, `edgeSort`) are plain
strings in the schema; the tool validates them and returns `INVALID_PARAMETER` for a
value outside the set.

Optional numeric limits are clamped to the tool's range, not rejected: a `limit` of
`0` becomes `1`, a `limit` above the cap becomes the cap.

---

## 6. Node references

A node appears in responses in one of three forms. They differ in size, because a
response that names a hundred nodes cannot afford a full record for each.

### 6.1 Minimal NodeRef

The identity of a node, used wherever a node is part of a larger structure: a scope
echo, an error's `declaring_type`, a `by_parent` entry.

```kotlin
val minimal = mapOf(
    "id" to 4711L,                                  // node id, the input for every other tool
    "name" to "Invoice",                            // simple name, segment, or member signature
    "qualified_name" to "com.example.billing.Invoice",
    "kind" to "java.class",                         // one of the kinds in §3.2
    "parent_id" to 4702L,                           // the containing node; the root's id for a module
    "parent_kind" to "java.package"                 // null when the parent is the root
)
```

`parent_id` and `parent_kind` let a caller place a node in the tree without a second
call.

### 6.2 Enriched NodeRef

A minimal NodeRef plus counts that depend on the kind. Browse results
(`find_node`, `list_children`, `list_descendants`, `affected_by`, `graph_overview`'s
hierarchy) use it, because a caller choosing where to look next needs to know how big
each candidate is.

| Kind | Extra fields | Why |
|------|--------------|-----|
| `java.module` | `child_count`, `descendant_type_count`, `descendant_method_count` | size of the module at a glance |
| `java.package` | `child_count`, `descendant_type_count`, `direct_type_count` | tells a package with types apart from a pure namespace segment |
| `java.class`, `java.interface`, `java.enum`, `java.record`, `java.annotation` | `member_count`, `method_count`, `field_count` | shows whether a type is worth opening |
| `java.method`, `java.field`, `external.type` | none | the minimal fields identify them fully |

`descendant_type_count` and `direct_type_count` count the five Java type kinds, not
`external.type`. Packages under `External Types` therefore report `0`.
`method_count` includes constructors.

For declaration-level facts (modifiers, supertypes, annotations, signatures) the caller
uses the entity-detail tools.

### 6.3 Slim node map

Responses that name many nodes repeatedly (edge lists, paths) carry a top-level
`nodes` map and refer to nodes by id elsewhere. Each node appears once:

```json
"nodes": {
  "4711": { "name": "Invoice", "qualified_name": "com.example.billing.Invoice", "kind": "java.class" },
  "4730": { "name": "Money",   "qualified_name": "com.example.money.Money",     "kind": "java.record" }
}
```

Keys are ids as strings, because JSON object keys are strings. An edge then reads
`{"from": 4711, "to": 4730, "weight": 3}`. On a response with a few hundred edges between
a few dozen types, this removes most of the repetition.

---

## 7. Pagination

### 7.1 Pages and summaries

Paginated tools compute the complete result in a fixed order, then return one slice.
`list_descendants`, `affected_by` and the type level of `outgoing_dependencies` /
`incoming_dependencies` carry:

| Field | Meaning |
|-------|---------|
| `summary.total` | number of items in the full result |
| `summary.returned` | number of items on this page |
| `summary.truncated` | `true` when `total > returned` |
| `next_cursor` | opaque token for the next page; the key is absent on the last page |

`truncated` compares the page with the full result. It stays `true` on the last page of
a multi-page walk. The presence of `next_cursor` is the signal that more pages follow.

The detail level of `outgoing_dependencies` / `incoming_dependencies` names the count
`summary.total_edges` instead of `summary.total`. `pairwise_dependencies` carries no
page counts: it returns its summary (with `total_matching_edges`) on the first page
only, and every tool marks further pages with `next_cursor`.

The other summary fields (`by_kind`, `by_target`, `by_distance`, …) describe the full
result, on every page that carries a summary.

### 7.2 Cursors

A **cursor** is a base64url-encoded JSON object (no padding) that carries everything
needed to resume:

| Field | Meaning | Why |
|-------|---------|-----|
| `v` | cursor format version, `1` | lets the format evolve without misreading a cursor of another version |
| `tool` | the tool that issued it | a cursor from one tool is meaningless to another |
| `qh` | hash of the query parameters | resuming a different query at the same offset returns garbage |
| `dh` | **data hash** of the loaded graph | resuming over changed data skips or repeats items |
| `offset` | position of the next item | where to resume |

The server keeps no session state. A cursor stays valid across reloads and server
restarts that leave the graph unchanged, and becomes invalid as soon as a rebuild
changes it.

The query hash covers the parameters that define the result and excludes `limit` and
`cursor`, so a caller can change the page size between pages. Kind and modifier
filters are sorted before hashing, so reordering them keeps the cursor valid. The
`nodeIds` of `pairwise_dependencies` are hashed in the given order.

The **data hash** is a fingerprint of the loaded graph: the node count, the edge count
and an order-independent sum over the hashes of every node's id and kind, each taken
from its text. Two loads of the same code give the same hash, in the same server
process or in another one.

A cursor is checked in this order, and the first failure is reported:

| Code | Situation |
|------|-----------|
| `INVALID_CURSOR_FORMAT` | not base64url, not a JSON object, a field missing or mistyped, or a negative offset |
| `STALE_CURSOR_VERSION` | a format version the server does not support |
| `WRONG_TOOL_CURSOR` | issued by another tool; the error names `issued_by` and `called_on` |
| `STALE_CURSOR_QUERY` | the query parameters differ from those it was issued for |
| `STALE_CURSOR_DATA` | the graph changed since it was issued |

Every cursor error carries a `recovery` that says to restart without a cursor (or, for
`WRONG_TOOL_CURSOR` and `STALE_CURSOR_QUERY`, to restore the original tool or
parameters).

### 7.3 The response budget

Each paginated tool estimates its page size in bytes as `returned × bytesPerItem`.
A page above **50,000 bytes** fails with `RESULT_TOO_LARGE` instead of being returned.
A one-item page always passes, so the summary is always reachable.

| Tool | Default `limit` | Cap | Bytes per item | Largest page that fits |
|------|-----------------|-----|----------------|------------------------|
| `list_descendants` | 150 | 500 | 250 | 200 |
| `pairwise_dependencies` | 25 | 200 | 250 | 200 |
| `outgoing_dependencies`, `incoming_dependencies` (type level) | 100 | 400 | 350 | 142 |
| `outgoing_dependencies`, `incoming_dependencies` (detail level) | 80 | 250 | 550 | 90 |
| `affected_by` | 100 | 350 | 450 | 111 |

**Why the budget exists.** Without it, a page on a dense node overflows the client's
context, the client truncates it, and the model loses the summary along with the
edges. Failing early with a specific error keeps the answer reachable:

```json
{ "error": {
    "code": "RESULT_TOO_LARGE",
    "message": "This page (~52500 bytes for 150 items) exceeds the 50000-byte response budget and would overflow the caller's context.",
    "returned": 150, "estimated_bytes": 52500, "budget_bytes": 50000,
    "suggested_limit": 142,
    "recovery": "If you only need the ranking or breakdown, re-query with limit=1 and read the summary — …" } }
```

A `limit` between the largest fitting page and the cap succeeds only when fewer items
than the budget allows remain.

---

## 8. Errors

### 8.1 Error shapes

Every tool returns errors as a nested object:

```json
{ "error": {
    "code": "NODE_NOT_FOUND",
    "message": "No node with id 9999 exists in the graph.",
    "recovery": "Use find_node to look up the correct node ID." } }
```

`code` comes first and `recovery` last. Some errors add fields between them that help
the caller recover, such as `actual_kind`, `declaring_type`, `invalid_values` or
`suggested_limit`. A caller therefore tests one key, `error`, and always finds the
same three fields inside it, whichever tool answered.

Each tool specification lists its errors and their extra fields.

### 8.2 Common codes

| Code | Situation |
|------|-----------|
| `NODE_NOT_FOUND` | an id names no node |
| `INVALID_NODE_KIND` / `WRONG_NODE_KIND` | an id names a node of the wrong kind for this tool (for example a method passed to a type-level tool) |
| `INVALID_KIND` | a kind filter contains an unknown kind |
| `INVALID_PARAMETER` | a parameter is missing or outside its value set |
| `INPUT_TOO_SMALL` / `INPUT_TOO_LARGE` | a list parameter has too few or too many entries |
| `DETAIL_REBUILDING` | the entity-detail tools cannot read the detail-level snapshot yet; retry shortly |
| `DETAIL_UNAVAILABLE` | the detail-level engine finds no detail-level data for the loaded graph |
| cursor codes, `RESULT_TOO_LARGE` | §7 |

### 8.3 No graph loaded

The server starts even when no graph can be built, for example before the project has
been compiled. While no graph is loaded, every tool except `reload_graph` returns:

```json
{ "status": "no_graph_available", "message": "No hierarchical graph is available. …" }
```

`status` is stable and meant for programmatic checks. `reload_graph` is exempt because it is the
way to load a graph once the input exists.

---

## 9. Working with the tools

**Orient.** Start with `graph_overview`. It lists the modules with their sizes and
coupling, and defines every kind and relationship the other tools return.

**Find a starting node.** `find_node(name = "Invoice")` returns candidates ranked by how
well they match. Take the `id` of the right one.

**Browse.** `list_children(nodeId = <module id>)` shows the packages of a module.
`list_descendants(nodeId = <package id>, kindFilter = ["types"])` lists every type below a
package.

**Measure coupling.** `pairwise_dependencies(nodeIds = [<module ids>])` gives the full
dependency matrix between modules, with cycles and a topological order.
`aggregated_dependencies(sourceIds = [a], targetIds = [b])` gives the weight between
specific scopes.

**Drill down.** `outgoing_dependencies(fromId = a, toId = b)` lists the type-level edges
behind an aggregated weight. The same call with `detailLevel = "detail"` lists the
member references with line numbers.

**Assess impact.** `affected_by(nodeId = t, direction = "incoming")` lists every type
that transitively depends on `t`. `find_dependency_path(fromId = a, toId = b)` shows how
`a` reaches `b`.

**Read a declaration.** `type_details`, `method_details` and `field_details` describe
one element's signature, modifiers and annotations.

**Page.** When a response carries `next_cursor`, pass it back unchanged with the same
parameters. When only the summary is needed, ask for `limit = 1`.

---

## 10. Guarantees

1. **Stable ids.** Within one loaded graph, a node's id does not change, and every id a
   tool returns is accepted by every tool that takes an id of that kind.
2. **Deterministic order.** For the same graph and parameters, a tool returns the same
   items in the same order. Pagination relies on this.
3. **Full-result summaries.** Summary fields of a paginated tool describe the whole
   result on every page.
4. **No partial pages.** A paginated tool either returns a page within the response
   budget or fails with `RESULT_TOO_LARGE`; it never returns a silently shortened page.
5. **Cursors never cross graphs.** A cursor issued against one graph is rejected after
   the graph changes.
6. **One graph per call.** Each tool call is computed from the graph that is live
   when it starts, even when a reload replaces the graph while it runs. Its data hash,
   and the cursor it issues, belong to that same graph.
7. **Read-only.** No tool other than `reload_graph` changes server state.

---

## 11. Boundaries

| Not in the tool surface | Where it lives |
|-------------------------|----------------|
| Scanning bytecode, building the graph | [GraphStore](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore.md) and the [bytecode scanner](../../../hierograph-graphstore/io.hierograph.graphstore.jvm.scanner/spec/hierograph-graphstore-scanner.md) |
| Loading, watching and reloading the graph | [hierarchical graph service](../../io.hierograph.server.graphservice/spec/hierarchical-graph-service.md) |
| Rolling dependencies up the tree, caching | [hierarchical graph model](../../../hierograph-hierarchicalgraph/io.hierograph.hierarchicalgraph.model/spec/hierarchicalgraph-model.md) |
| The GraphQL API | `io.hierograph.server.graphql` |
| Interpreting results (layering verdicts, extraction plans) | the calling model and Hierograph's skills |

Keeping the tools to query and presentation keeps each one small: it resolves ids,
calls the model, shapes a bounded response and leaves judgement to the caller.
