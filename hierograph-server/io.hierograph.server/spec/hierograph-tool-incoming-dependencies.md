# Hierograph Tool `incoming_dependencies` — Specification

`incoming_dependencies` is Hierograph's **evidence tool for what depends on a
subtree**. Given a depended-upon node and, optionally, the depending node, it lists
the dependencies that lead into it: at the **type level** as weighted type-to-type
edges, at the **detail level** as individual member references with source lines. It
answers "who uses this, and how?", the question behind every impact assessment.

This document explains why the tool exists, what it accepts, how it collects and
orders its edges at both levels, what it returns and how to work with it. The tool is
the mirror of [`outgoing_dependencies`](hierograph-tool-outgoing-dependencies.md):
the edges, fields and vocabularies are the same, and this document links to that
specification for the parts the two share. The concepts all tools share are defined
in the [MCP tools specification](hierograph-mcp-tools.md).

---

## Contents

1. [Purpose](#1-purpose)
2. [Parameters](#2-parameters)
3. [Behaviour](#3-behaviour)
4. [Response](#4-response)
5. [Errors](#5-errors)
6. [Working with the tool](#6-working-with-the-tool)
7. [Guarantees](#7-guarantees)

---

## 1. Purpose

Before changing a type, a caller needs to know what depends on it: which types, how
heavily, through which members. The graph keeps every type-level dependency on both
of its ends, so "what uses `Money`?" is as cheap to answer as "what does `Invoice`
use?". `incoming_dependencies` answers it from the depended-upon side.

**Anchored on the depended-upon side.** `fromId` names the node whose users are
wanted; `toId`, when given, restricts the users. The open form (no `toId`) lists every
user from anywhere in the graph. That makes "what depends on X?" one call with one
id, without knowing in advance where the users are.

**Same edges, other anchor.** The edges point the way the dependency points: `from` is
always the depending type or member, `to` the depended-upon one. Only the anchor and
the summaries differ from `outgoing_dependencies`. A caller can therefore combine
results of both tools without re-orienting them.

**Two levels, one tool.** As in `outgoing_dependencies`, `detailLevel = "type"` lists
weighted type edges from the in-memory graph, and `detailLevel = "detail"` lists
member references with source lines from the snapshot's member-level data (see
[Two levels of dependency](hierograph-mcp-tools.md#33-two-levels-of-dependency)).

### `incoming_dependencies` and its neighbours

| Tool | Answers |
|------|---------|
| [`outgoing_dependencies`](hierograph-tool-outgoing-dependencies.md) | what a subtree depends on, type by type or reference by reference |
| `incoming_dependencies` | what depends on a subtree, type by type or reference by reference |
| [`aggregated_dependencies`](hierograph-tool-aggregated-dependencies.md) | how strongly each source depends on each target, as one number per pair |
| [`affected_by`](hierograph-tool-affected-by.md) | what depends on a node *transitively*, through chains of dependencies |

`incoming_dependencies` is needed beside `outgoing_dependencies` because the open form
only works from the anchor: "everything `Invoice` uses" and "everything that uses
`Money`" are different scans, and the summaries rank different things. It is needed
beside `affected_by` because it reports direct users with their weights and
references, where `affected_by` reports reach.

---

## 2. Parameters

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `fromId` | node id | yes | — | The depended-upon subtree |
| `toId` | node id | at detail level | — | The depending subtree |
| `detailLevel` | string | no | `"type"` | `"type"` for type edges, `"detail"` for member references |
| `relationship` | string | no | — | Detail level only: keep references of this relationship |
| `limit` | integer | no | 100 (type), 80 (detail) | Edges per page; capped at 125 (type), 90 (detail) |
| `cursor` | string | no | — | `next_cursor` of the previous page |
| `view` | string | no | none | the view to answer in |

The names are those of `outgoing_dependencies`, but the roles of `fromId` and `toId`
are reversed: here `fromId` is the side that is *used*.

**`fromId`** names the subtree whose users are listed: a module, a package, a type,
the root or an external type. It stands for the node and everything below it; a type
covers itself and its members, not its nested types. At the type level, methods and
fields are rejected (§5). At the detail level they are accepted and narrow the scope
to that member, as in
[`outgoing_dependencies`](hierograph-tool-outgoing-dependencies.md#2-parameters):
`fromId = Money#times(int)` lists who calls `times`.

**`toId`** restricts the users to this subtree, turning "what uses `money`" into
"what does `billing` use of `money`". It is optional at the type level and required at
the detail level, for the reasons given in
[`outgoing_dependencies`](hierograph-tool-outgoing-dependencies.md#2-parameters).

**`detailLevel`**, **`relationship`**, **`limit`** and **`cursor`** behave as in
[`outgoing_dependencies`](hierograph-tool-outgoing-dependencies.md#2-parameters):
`detailLevel` accepts only `"type"` and `"detail"`; `relationship` is an error at the
type level; a page holds at most 125 type edges or 90 detail rows, the caps that keep
it within the response budget. A cursor is bound to `incoming_dependencies`: a cursor issued by
`outgoing_dependencies` fails here with `WRONG_TOOL_CURSOR`.

**`view`** names a [view](../../io.hierograph.server.views/spec/hierograph-views.md) to
answer in ([§5 of the MCP tools specification](hierograph-mcp-tools.md#5-parameters)).
The server adds it to the tool's schema and resolves it before the tool runs: an unknown
name is a `VIEW_NOT_FOUND` error, a document that does not parse a `VIEW_INVALID` error,
and a value that is not a string an `INVALID_PARAMETER` error. Without it, or with a
blank value, the tool answers from the hierarchy as scanned. In a view, a group's id is
accepted wherever this tool takes a node id, and stands for the subtree below the group
in the view. Dependencies are the same in every view; only where nodes sit changes.

---

## 3. Behaviour

### 3.1 Type level

**Scope.** The **anchor types** are every type in the `fromId` subtree, including
`fromId` itself when it is a type. With `toId`, the tool also collects the types of
the `toId` subtree.

**Edges.** For each anchor type, in tree order, the tool takes the type-level
dependencies that end at it. Without `toId` it keeps all of them; with `toId` it keeps
those whose depending type lies in the `toId` subtree. Each kept dependency becomes
one edge with `from` the depending type, `to` the anchor type, the dependency's weight,
its four attribute flags and its `kinds`. The weight is the number of individual references from
`from` to `to`, over all reference kinds (see the
[type-level aggregate](../../../hierograph-graphstore/io.hierograph.graphstore.jvm.archive/spec/hierograph-graphstore-format.md#64-the-type-level-aggregate)).
In the open form on a package, dependencies between types of the package itself are
included.

**Ordering.** Edges are sorted by the qualified name of `from`, then of `to`, with the
ids as tiebreakers: the users appear grouped and in name order.

**Summaries.** Over all kept edges:

- `by_target`: the anchor types ranked by summed incoming weight, top 10: the most
  heavily used types of `fromId`.
- `by_source_type`: the depending types ranked by number of edges, top 10: the users
  that touch the most anchor types.
- `by_attribute`: for each of the four flags, the number of edges that carry it.
  `is_extends` and `is_implements` count the subclasses and implementations, which are
  the users hardest to change.
- `by_kind`: for each kind, the number of references over all kept edges: how the
  anchor types are used — called, extended, named in signatures, cast to, …

### 3.2 Detail level

At the detail level the tool runs the detail query of `outgoing_dependencies` with the
two scopes exchanged: `toId` (the depender) as the `from` side and `fromId` (the
depended-upon node) as the `to` side. Everything described in
[`outgoing_dependencies` §3.2](hierograph-tool-outgoing-dependencies.md#32-detail-level)
then applies unchanged:

- each side is expanded to its **inheritance closure**, the types of the subtree plus
  their transitive supertypes, so a use of an interface method that `fromId`
  implements is found; the rows only the closure brings in carry `"inherited": true`;
- the rows come from the snapshot's
  [`dependencies.parquet`](../../../hierograph-graphstore/io.hierograph.graphstore.jvm.archive/spec/hierograph-graphstore-format.md#44-dependenciesparquet)
  plus the load-derived overrides and virtual-call targets, in the same order;
- the `relationship` vocabulary, including the reverse views `read_by` and
  `written_by`, is the same;
- the summaries (`by_relationship`, `by_source_type`, `by_source_nodes`,
  `by_target_nodes`) are computed the same way, from the depender's side.

The response states the exchange: `from_scope` is `toId`, the depender, and
`to_scope` is `fromId`. `by_source_type` ranks the depending types, and
`by_target_nodes` drills into `fromId`.

The exchange also applies to `read_by` and `written_by`, whose rows read field →
method. `incoming_dependencies(fromId = A, toId = B, relationship = "read_by")` lists
the fields of `B` that methods of `A` read.

### 3.3 Worked example

The example graph, with its type-level dependencies and their weights (package
segments above `billing` and `money` omitted):

```
billing-core                                 type-level dependencies (weight)
├── com.example.billing        4702
│   ├── Invoice                4711          → Invoice$Line 4, Money 3, Logger 2, Ledger 1
│   ├── Invoice$Line           4712          → Money 2, BigDecimal 1
│   └── Ledger                 4713          → Invoice 2, Money 1
└── com.example.money          4720
    └── Money                  4730          → BigDecimal 4
External Types
├── java.math.BigDecimal       4803
└── org.slf4j.Logger           4806
```

`incoming_dependencies(fromId = 4730)` is the open form on `Money`. Three types depend
on it:

| `from` | `to` | `weight` |
|--------|------|---------:|
| `com.example.billing.Invoice` | `Money` | 3 |
| `com.example.billing.Invoice$Line` | `Money` | 2 |
| `com.example.billing.Ledger` | `Money` | 1 |

`by_target` is `Money` with 6; `by_source_type` lists each of the three users with one
edge. `incoming_dependencies(fromId = 4720, toId = 4702)`, from the `money` package
to its users in `billing`, returns the same three edges: these are exactly the
dependencies that
[`outgoing_dependencies`](hierograph-tool-outgoing-dependencies.md#33-worked-example-type-level)
lists for `fromId = 4702, toId = 4720`.

External types work as anchors: `incoming_dependencies(fromId = 4803)` lists
`Invoice$Line → BigDecimal` 1 and `Money → BigDecimal` 4, and `by_target` ranks
`BigDecimal` with 5.

At the detail level, `incoming_dependencies(fromId = 4720, toId = 4702,
detailLevel = "detail")` lists the six member references of
[`outgoing_dependencies` §3.4](hierograph-tool-outgoing-dependencies.md#34-worked-example-detail-level),
in the same order, with `from_scope` 4702 and `to_scope` 4720.

---

## 4. Response

### 4.1 Type level

The open form on `Money` with `limit = 2`:

```json
{
  "nodes": {
    "4730": { "name": "Money", "qualified_name": "com.example.money.Money", "kind": "java.record" },
    "4711": { "name": "Invoice", "qualified_name": "com.example.billing.Invoice", "kind": "java.class" },
    "4712": { "name": "Invoice$Line", "qualified_name": "com.example.billing.Invoice$Line", "kind": "java.class" }
  },
  "edges": [
    { "from": 4711, "to": 4730, "weight": 3, "type_pair_count": 1,
      "attributes": { "is_extends": false, "is_implements": false,
                      "is_annotated_by": false, "is_depends_on_other": true },
      "kinds": { "returns": 1, "calls": 2 } },
    { "from": 4712, "to": 4730, "weight": 2, "type_pair_count": 1,
      "attributes": { "is_extends": false, "is_implements": false,
                      "is_annotated_by": false, "is_depends_on_other": true },
      "kinds": { "calls": 2 } }
  ],
  "summary": {
    "total": 3,
    "returned": 2,
    "truncated": true,
    "by_attribute": { "is_extends": 0, "is_implements": 0, "is_annotated_by": 0, "is_depends_on_other": 3 },
    "by_kind": { "returns": 1, "calls": 5 },
    "by_source_type": [
      { "id": 4711, "count": 1 },
      { "id": 4712, "count": 1 },
      { "id": 4713, "count": 1 }
    ],
    "by_target": [ { "id": 4730, "weight": 6 } ]
  },
  "next_cursor": "eyJ2IjoxLCJ0b29sIjoiaW5jb21pbmdfZGVwZW5kZW5jaWVzIiwi…"
}
```

The fields are those of the type level of `outgoing_dependencies`
([§4.1 there](hierograph-tool-outgoing-dependencies.md#41-type-level)), with these
readings:

| Field | Reading in `incoming_dependencies` |
|-------|------------------------------------|
| `nodes` | `fromId`, `toId` when given, and the endpoints of this page's edges; ids found only in the rankings are not included |
| `edges[].from` | the depending type, possibly from anywhere in the graph |
| `edges[].to` | an anchor type of `fromId` |
| `edges[].weight` | number of references from `from` to `to` |
| `edges[].type_pair_count` | always `1`: each edge is one type pair, shaped like an aggregated edge |
| `edges[].attributes` | the four attribute flags of the dependency |
| `edges[].kinds` | the number of references per kind; they sum to `weight` |
| `summary.total`, `returned`, `truncated` | as in [Pages and summaries](hierograph-mcp-tools.md#71-pages-and-summaries) |
| `summary.by_attribute` | all four flags with their edge counts, 0 included |
| `summary.by_kind` | the number of references per kind over all matching edges |
| `summary.by_source_type` | `{id, count}`: the users by number of edges, top 10 |
| `summary.by_target` | `{id, weight}`: the anchor types by summed incoming weight, top 10 |
| `next_cursor` | present when more edges follow, absent on the last page |

**`by_target`** is the field that most incoming questions need: it ranks the types of
`fromId` by how much the rest of the graph relies on them, which tells a caller
which types are expensive to change.

**`by_source_type`** ranks the users by breadth: a user with many edges into a package
is coupled to many of its types.

### 4.2 Detail level

The detail example of §3.3 with `limit = 1`:

```json
{
  "nodes": {
    "4702": { "name": "billing", "qualified_name": "com.example.billing", "kind": "java.package" },
    "4720": { "name": "money", "qualified_name": "com.example.money", "kind": "java.package" },
    "4711": { "name": "Invoice", "qualified_name": "com.example.billing.Invoice", "kind": "java.class" },
    "4712": { "name": "Invoice$Line", "qualified_name": "com.example.billing.Invoice$Line", "kind": "java.class" },
    "4713": { "name": "Ledger", "qualified_name": "com.example.billing.Ledger", "kind": "java.interface" },
    "4715": { "name": "com.example.money.Money total(java.lang.String)",
              "qualified_name": "com.example.billing.Invoice#com.example.money.Money total(java.lang.String)",
              "kind": "java.method" },
    "4730": { "name": "Money", "qualified_name": "com.example.money.Money", "kind": "java.record" }
  },
  "from_scope": 4702,
  "to_scope": 4720,
  "edges": [
    { "from": 4715, "from_parent": 4711, "to": 4730,
      "relationship": "returns", "location": { "line_number": 13 } }
  ],
  "summary": {
    "total": 6,
    "returned": 1,
    "truncated": true,
    "inherited": 0,
    "by_relationship": { "calls": 2, "has_type": 1, "parameter_type": 1, "returns": 2 },
    "by_source_type": [
      { "type": 4711, "edge_count": 3 },
      { "type": 4712, "edge_count": 2 },
      { "type": 4713, "edge_count": 1 }
    ],
    "by_source_nodes": [
      { "node": 4711, "aggregated_weight": 3 },
      { "node": 4712, "aggregated_weight": 2 },
      { "node": 4713, "aggregated_weight": 1 }
    ],
    "by_target_nodes": [
      { "node": 4730, "aggregated_weight": 6 }
    ]
  },
  "next_cursor": "eyJ2IjoxLCJ0b29sIjoiaW5jb21pbmdfZGVwZW5kZW5jaWVzIiwi…"
}
```

The fields are those of the detail level of `outgoing_dependencies`
([§4.2 there](hierograph-tool-outgoing-dependencies.md#42-detail-level)), read with
the exchanged scopes:

| Field | Reading in `incoming_dependencies` |
|-------|------------------------------------|
| `from_scope` | `toId`, the depending scope |
| `to_scope` | `fromId`, the depended-upon scope |
| `edges[].from`, `from_parent` | the referencing member and its type, on the depender's side |
| `edges[].to`, `to_parent` | the referenced member or type and, for a member, its type, on the `fromId` side |
| `edges[].relationship`, `location` | the relationship and the source line, or `null` |
| `edges[].inherited` | `true` when the row comes from the inheritance closure; absent otherwise |
| `summary.total`, `returned`, `truncated` | count of rows, rows on this page, and `total > returned` |
| `summary.inherited` | the number of rows marked `inherited` |
| `summary.by_relationship` | rows per relationship; a filtered relationship is present even with 0 |
| `summary.by_source_type`, `others_count` | the depending types by number of rows, top 10, and how many more exist |
| `summary.by_source_nodes` | drill-down of `toId`, weighted by type-level weight into `fromId` |
| `summary.by_target_nodes` | drill-down of `fromId`, weighted by type-level weight from `toId` |

**`from_scope`** and **`to_scope`** are the reason a caller can read this response
without remembering the exchange: they name the depender and the depended-upon side
explicitly. In the example, `by_target_nodes` drills into `money`, the `fromId` side:
its only child is the type `Money`, so the drill-down lists `Money` with its weight
from `billing`, 6.

---

## 5. Errors

The checks and shapes are those of
[`outgoing_dependencies` §5](hierograph-tool-outgoing-dependencies.md#5-errors). Type
All errors use the nested shape with `code`, `message` and `recovery`:

| Code | Situation |
|------|-----------|
| `INVALID_PARAMETER` | `detailLevel` is not `"type"` or `"detail"`; `relationship` given at the type level; `toId` missing at the detail level |
| `NODE_NOT_FOUND` | `fromId`, then `toId`, names no node |
| `INVALID_NODE_KIND` | `fromId` or `toId` names a method or a field; carries `actual_kind` and `declaring_type` |
| cursor codes | see [Cursors](hierograph-mcp-tools.md#72-cursors) |

The detail level checks `fromId`, then `toId`, before it exchanges the scopes, so
`NODE_NOT_FOUND` names the missing id in parameter order, as at the type level, and
then adds `INVALID_RELATIONSHIP`, `DETAIL_UNAVAILABLE` and `DETAIL_REBUILDING`:

```json
{ "error": {
    "code": "NODE_NOT_FOUND",
    "message": "No node with id 9999 exists in the graph.",
    "recovery": "Use find_node to look up the correct node ID." } }
```

While no graph is loaded the tool returns the
[no-graph response](hierograph-mcp-tools.md#83-no-graph-loaded).

---

## 6. Working with the tool

**Ask "what depends on X?".** Call the open form with `limit = 1`:
`incoming_dependencies(fromId = 4720, limit = 1)`. `total` is the number of using
edges, `by_source_type` names the main users and `by_target` the most-used types of
the package.

**Check for subclasses.** Read `by_attribute.is_extends` and `is_implements` in the
same summary. A non-zero count means some users inherit from the anchor and will
break on any signature change.

**Narrow to one user.** Add `toId`:
`incoming_dependencies(fromId = 4720, toId = 4702)` lists only what `billing` uses of
`money`.

**Zoom to members.** Repeat with `detailLevel = "detail"` to see the methods of the
user that call into the anchor, with lines. Add `relationship = "calls"` or a smaller
`toId` before paging through many rows.

**Assess the wider impact.** For users of users, call
[`affected_by`](hierograph-tool-affected-by.md) with `direction = "incoming"`.

**Page.** Pass `next_cursor` back with the same parameters. A cursor from
`outgoing_dependencies` is not accepted here.

---

## 7. Guarantees

1. **Mirror of outgoing.** The type-level edges of
   `incoming_dependencies(fromId = A, toId = B)` are the edges of
   `outgoing_dependencies(fromId = B, toId = A)`, in the same order; at the detail
   level the rows are identical.
2. **Edges keep their direction.** `from` is always the depending side and `to` the
   depended-upon side.
3. **Full-result summaries.** Every summary field describes all matching edges or
   rows, independent of `limit` and the page.
4. **Deterministic order.** For the same graph and parameters, a cursor walk returns
   each edge exactly once.
5. **Read-only.** The tool does not change the graph.
