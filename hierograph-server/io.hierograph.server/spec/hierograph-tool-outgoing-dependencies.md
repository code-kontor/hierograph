# Hierograph Tool `outgoing_dependencies` — Specification

`outgoing_dependencies` is Hierograph's **evidence tool for what a subtree depends
on**. Given a source node and, optionally, a target node, it lists the dependencies
that lead out of the source: at the **type level** as weighted type-to-type edges, at
the **detail level** as individual member references with source lines. It is the
tool that turns an aggregated weight into the concrete types and calls behind it.

This document explains why the tool exists, what it accepts, how it collects and
orders its edges at both levels, what it returns and how to work with it. The
concepts all tools share (node ids, kinds, the slim node map, pagination, error
shapes) are defined in the [MCP tools specification](hierograph-mcp-tools.md).
[`incoming_dependencies`](hierograph-tool-incoming-dependencies.md) is its mirror.

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

An aggregated dependency says that `com.example.billing` depends on
`com.example.money` with weight 6. To act on that fact (remove the dependency, move a
type, judge whether it is legitimate) a caller needs to know which types carry it, and
often which methods. `outgoing_dependencies` answers both questions, one level of
detail at a time.

**Two levels, one tool.** The two levels are views of the same dependency, at
different granularity (see
[Two levels of dependency](hierograph-mcp-tools.md#33-two-levels-of-dependency)).
The **type level** (`detailLevel = "type"`) lists one weighted edge per pair of
types. It is computed from the in-memory graph and is fast. The **detail level**
(`detailLevel = "detail"`) lists one row per member reference, such as a call or a
field read, with its source line. It reads the snapshot's member-level data and costs
more. Keeping both in one tool with the same scope parameters lets a caller zoom in
without rephrasing the question.

**Summaries over the full result.** Every response carries a summary computed over
all matching edges, not just the page: the most-used targets, the busiest source
types, the kinds of dependency. Most questions ("what does `Invoice` lean on most?")
are answered by the summary of a one-edge page.

**An open form for "what does X use?".** At the type level the target is optional.
Without it, the tool lists every dependency of the source, to anywhere in the graph,
including external types.

### `outgoing_dependencies` and its neighbours

| Tool | Answers |
|------|---------|
| [`aggregated_dependencies`](hierograph-tool-aggregated-dependencies.md) | how strongly each source depends on each target, as one number per pair |
| [`pairwise_dependencies`](hierograph-tool-pairwise-dependencies.md) | the full matrix within a node set, with cycles and a layering order |
| `outgoing_dependencies` | which type edges or member references make up the dependencies of a source |
| [`incoming_dependencies`](hierograph-tool-incoming-dependencies.md) | the same, read from the depended-upon side: what uses this node |

`outgoing_dependencies` is needed beside the aggregate tools because they stop at one
number per pair; this tool lists what the number is made of. It is needed beside
`incoming_dependencies` because the two tools anchor on different sides: this tool
takes the depender as `fromId` and is paged and summarized from the depender's point
of view.

---

## 2. Parameters

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `fromId` | node id | yes | — | The depending subtree |
| `toId` | node id | at detail level | — | The depended-upon subtree |
| `detailLevel` | string | no | `"type"` | `"type"` for type edges, `"detail"` for member references |
| `relationship` | string | no | — | Detail level only: keep references of this relationship |
| `limit` | integer | no | 100 (type), 80 (detail) | Edges per page; capped at 400 (type), 250 (detail) |
| `cursor` | string | no | — | `next_cursor` of the previous page |

**`fromId`** names the subtree whose outgoing dependencies are listed. It stands for
the node and everything below it: a module, a package, a type, the root (the whole
graph) or an external type. A type covers itself and its members, not its nested
types: `Invoice$Line` is a sibling of `Invoice` in its package (see
[Nodes and the containment tree](hierograph-mcp-tools.md#31-nodes-and-the-containment-tree)).
At the type level, methods and fields are rejected (§5), because type edges start at
types. At the detail level a method or field id is accepted and narrows the scope to that
member: the tool reads the declaring type's scope and keeps the rows whose member on
that side is the given one. `fromId = Invoice#net()` lists what `net()` calls, returns
and reads; `toId = Money#plus(Money)` lists who calls `plus`. This answers member
questions without listing the whole type.

**`toId`** restricts the result to dependencies whose target lies in this subtree. It
is what turns "everything `billing` uses" into "what `billing` uses of `money`". At
the type level it is optional: omitted, the target side is unconstrained (the
**open form**). At the detail level it is required, because an unconstrained member
listing of a package is too large to be useful and the type level already answers the
open question. `toId` may lie inside `fromId` or equal it; the result is then the
internal dependencies.

**`detailLevel`** chooses the level. Only `"type"` and `"detail"` are accepted, case
sensitive. The type level is the default because it is cheap and usually enough;
the detail level is the step after it.

**`relationship`** narrows a detail-level result to one kind of member reference,
such as `calls` or `reads_field` (vocabulary in §3.2). A type edge has no single
relationship, so passing a `relationship` at the type level is an error rather than
being silently ignored. An empty string counts as absent.

**`limit`** is the page size. The defaults differ per level because a detail row is
larger than a type edge. Values are clamped as described in
[Parameters](hierograph-mcp-tools.md#5-parameters). The response budget allows at
most 142 type edges or 90 detail rows per page
([The response budget](hierograph-mcp-tools.md#73-the-response-budget)); a larger
`limit` succeeds only when fewer edges remain.

**`cursor`** continues a listing. It is bound to `outgoing_dependencies`, to `fromId`,
`toId`, `detailLevel` and (at the detail level) `relationship`, and to the loaded
graph; see [Cursors](hierograph-mcp-tools.md#72-cursors). A type-level cursor passed
to a detail-level call fails with `STALE_CURSOR_QUERY`. `limit` may change between
pages.

---

## 3. Behaviour

### 3.1 Type level

**Scope.** The tool collects the **anchor types**: every type in the `fromId`
subtree, including `fromId` itself when it is a type. With `toId`, it also collects
the types of the `toId` subtree.

**Edges.** For each anchor type, in tree order, the tool takes the type's outgoing
type-level dependencies. Without `toId` it keeps all of them; with `toId` it keeps
those whose target is a type of the `toId` subtree. Each kept dependency becomes one
edge:

| Field | Value |
|-------|-------|
| `from` | the anchor type |
| `to` | the depended-upon type; may be an external type |
| `weight` | the number of individual references from `from` to `to`, over all reference kinds |
| `attributes` | the dependency's four attribute flags |

The weight comes from the snapshot's type-level references (see
[`refs.parquet`](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#44-refsparquet)):
two calls to `Money` methods and one `Money` return type give a weight of 3. In the
open form on a package, the edges between types of the package itself are included:
they are outgoing dependencies of the types, and the caller may filter on `to`.

**Ordering.** Edges are sorted by the qualified name of `from`, then of `to`, with the
ids as tiebreakers. The order is total, so pages are stable.

**Summaries.** While collecting, the tool counts over all kept edges:

- `by_target`: the depended-upon types ranked by summed weight, top 10. This is the
  answer to "what does the source lean on most?".
- `by_source_type`: the anchor types ranked by number of edges, top 10. This shows
  which types of a package carry its dependencies.
- `by_attribute`: for each of the four flags, the number of edges that carry it.

Rankings with equal values keep the order in which the edges were found.

### 3.2 Detail level

**Scope.** The tool collects the types of the `fromId` subtree and of the `toId`
subtree, as at the type level, and then adds to each side the transitive supertypes
of its types: everything reached through `extends` and `implements`, scanned or
external. The scope is the **inheritance closure** of each side. The root is accepted
on either side and stands for every type.

The closure exists because a type's behaviour includes code it inherits and calls
compiled against its supertypes. A reference from a method that `Invoice` inherits
from a base class belongs to what `Invoice` does, and a call compiled against an
interface that `Money` implements is a use of `Money`. The consequence is that a
detail result can contain rows whose `from_parent` lies outside `fromId`, and targets
outside `toId`: a supertype's own references, and references to members of the
target's supertypes. Such a row is **inherited**: it carries `"inherited": true`, and
`summary.inherited` counts them, so a caller can keep or drop them deliberately. A row
whose two types lie in the scopes' own subtrees carries no `inherited` key.

**Edges.** The tool reads every member reference whose depending type lies in the
`from` side and whose target type lies in the `to` side from the snapshot's
[`detail_refs.parquet`](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#45-detail_refsparquet),
then adds the references derived when the graph was loaded: method overrides and the
possible run-time targets of virtual calls. It keeps the references whose two types
are known to the graph. Each reference becomes one row:

| Field | Value |
|-------|-------|
| `from` | the referencing member; the type itself for an annotation on the type |
| `from_parent` | the type that declares `from` |
| `to` | the referenced member, or the referenced type |
| `to_parent` | the type that declares `to`; only when `to` is a member |
| `relationship` | the meaning of the reference |
| `location` | the source line, or `null` |
| `inherited` | `true` when a type of the row lies outside its scope's own subtree; absent otherwise |

**Relationships.** The `relationship` filter accepts these values:

| Value | Row reads | Source |
|-------|-----------|--------|
| `calls` | method → method it invokes | snapshot, plus resolved virtual-call targets |
| `overrides` | method → method it overrides | derived at load |
| `reads_field` | method → field it reads | snapshot |
| `writes_field` | method → field it assigns | snapshot |
| `returns` | method → its return type | snapshot |
| `parameter_type` | method → a parameter type | snapshot |
| `throws` | method → a type in its `throws` clause | snapshot |
| `raises` | method → a type it constructs and throws | snapshot |
| `has_type` | field → its declared type | snapshot |
| `annotated_by` | type, method or field → its annotation | snapshot |
| `read_by` | field → method that reads it | reverse view of `reads_field` |
| `written_by` | field → method that assigns it | reverse view of `writes_field` |
| `parameter_annotated_by` | method → a parameter annotation | accepted; snapshots do not record it, so it matches no rows |

The stored relationships are defined in the format specification's
[member-level relationships](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#53-member-level-relationships-detail_refsrelationship).

`read_by` and `written_by` exist only as filters; an unfiltered result never contains
them. They turn the stored field access around: the rows read field → method, and
the scopes swap roles. The `fromId` side holds the fields and the `toId` side the
methods that access them. `outgoing_dependencies(fromId = A, toId = B, relationship =
"read_by")` lists the fields of `A` that methods of `B` read. This answers "who
touches these fields?" without switching tools.

**Endpoint ids.** Types and the members of scanned types are graph nodes, and their
ids work with every other tool. A member of an external type (a call to
`org.slf4j.Logger.info`, say) is not a graph node. It gets a stable id that is unique
for the member but is not accepted by other tools; the `nodes` map still names it.

**Ordering.** All rows, the snapshot's and the load-derived ones together, are sorted
by depending type name, depending member key, target type name, target key,
relationship and line (a row without a line first). A derived `overrides` row of
`Invoice#render()` therefore sits between the rows of `Invoice#net()` and
`Invoice#validate()`, not after them. The order is the same on every call, so pages
are stable.

**Summaries.** Over all kept rows, the tool computes:

- `by_relationship`: the number of rows per relationship. When `relationship` is
  given, its key is present even with a count of 0.
- `by_source_type`: the depending types (`from_parent`) ranked by number of rows, top
  10, with `others_count` when more types exist.
- `by_source_nodes` and `by_target_nodes`: a drill-down of each scope into the next,
  narrower scopes. From the scope node, the tool descends while a module or package
  has exactly one child that is itself a module or package, then weighs each package
  or type below that point by its type-level aggregated weight to the other scope,
  highest first. Children with weight 0 are left out. The descent stops at types,
  because a type's members carry no type-level weight: a package holding a single
  type lists that type, and a type scope has no narrower type-level scope, so its
  drill-down is empty. These weights come from the type level, without the
  inheritance closure, so they need not match the row counts.

### 3.3 Worked example: type level

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

`outgoing_dependencies(fromId = 4711)` is the open form on `Invoice`. The anchor types
are `Invoice` alone. It has four outgoing dependencies, sorted by target name:

| `from` | `to` | `weight` |
|--------|------|---------:|
| `Invoice` | `com.example.billing.Invoice$Line` | 4 |
| `Invoice` | `com.example.billing.Ledger` | 1 |
| `Invoice` | `com.example.money.Money` | 3 |
| `Invoice` | `org.slf4j.Logger` | 2 |

`by_target` ranks them `Invoice$Line` 4, `Money` 3, `Logger` 2, `Ledger` 1; the total
weight is 10.

`outgoing_dependencies(fromId = 4702, toId = 4720)` is the constrained form from
`billing` to `money`. The anchor types are `Invoice`, `Invoice$Line` and `Ledger`;
the target types are `Money`. Three edges match: `Invoice → Money` 3,
`Invoice$Line → Money` 2 and `Ledger → Money` 1. `by_target` is `Money` with 6, the
weight [`aggregated_dependencies`](hierograph-tool-aggregated-dependencies.md) reports
for the same pair.

### 3.4 Worked example: detail level

The same pair at the detail level,
`outgoing_dependencies(fromId = 4702, toId = 4720, detailLevel = "detail")`, lists
six rows (member names shortened to simple type names). `Invoice#total` is the method
of the
[format example](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#7-example);
its first line is 13:

| `from` | `relationship` | `to` | line |
|--------|----------------|------|-----:|
| `Invoice#Money total(String)` | `returns` | `Money` | 13 |
| `Invoice#Money total(String)` | `calls` | `Money#Money add(Money)` | 14 |
| `Invoice#Money total(String)` | `calls` | `Money#Money zero(String)` | 13 |
| `Invoice$Line#Money amount` | `has_type` | `Money` | — |
| `Invoice$Line#Money amount()` | `returns` | `Money` | 31 |
| `Ledger#void post(Money)` | `parameter_type` | `Money` | — |

The three `Invoice` rows correspond to the type-level weight 3 (one return type, two
calls). The rows are ordered by depending type, then member key, then target key;
the target `Money` sorts before the keys of its members. `Ledger#post` is abstract and
has no line. None of the types has a supertype in the graph, so the inheritance
closure adds nothing.

The summaries: `by_relationship` is `calls` 2, `has_type` 1, `parameter_type` 1,
`returns` 2 (six rows). `by_source_type` is `Invoice` 3, `Invoice$Line` 2, `Ledger` 1.
`by_source_nodes` descends from `billing`, which has three children, and weighs them
by their type-level weight to `money`: 3, 2 and 1. `by_target_nodes` starts at
`money`, whose only child is the type `Money`; the descent stops there and lists
`Money` with its weight from `billing`, 6 (3 + 2 + 1).

---

## 4. Response

### 4.1 Type level

The open form of §3.3 with `limit = 2`:

```json
{
  "nodes": {
    "4711": { "name": "Invoice", "qualified_name": "com.example.billing.Invoice", "kind": "java.class" },
    "4712": { "name": "Invoice$Line", "qualified_name": "com.example.billing.Invoice$Line", "kind": "java.class" },
    "4713": { "name": "Ledger", "qualified_name": "com.example.billing.Ledger", "kind": "java.interface" }
  },
  "edges": [
    { "from": 4711, "to": 4712, "weight": 4, "type_pair_count": 1,
      "attributes": { "is_extends": false, "is_implements": false,
                      "is_annotated_by": false, "is_depends_on_other": true } },
    { "from": 4711, "to": 4713, "weight": 1, "type_pair_count": 1,
      "attributes": { "is_extends": false, "is_implements": false,
                      "is_annotated_by": false, "is_depends_on_other": true } }
  ],
  "summary": {
    "total": 4,
    "returned": 2,
    "truncated": true,
    "by_attribute": { "is_extends": 0, "is_implements": 0, "is_annotated_by": 0, "is_depends_on_other": 4 },
    "by_source_type": [ { "id": 4711, "count": 4 } ],
    "by_target": [
      { "id": 4712, "weight": 4 },
      { "id": 4730, "weight": 3 },
      { "id": 4806, "weight": 2 },
      { "id": 4713, "weight": 1 }
    ]
  },
  "next_cursor": "eyJ2IjoxLCJ0b29sIjoib3V0Z29pbmdfZGVwZW5kZW5jaWVzIiwi…"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `nodes` | object | Slim node map: `fromId`, `toId` when given, and the endpoints of this page's edges |
| `edges` | array | This page of type edges |
| `summary` | object | Counts and rankings over all matching edges |
| `next_cursor` | string | Cursor for the next page; absent on the last page |

**`nodes`** is the [slim node map](hierograph-mcp-tools.md#63-slim-node-map). It
always names the scope nodes, so a caller can label the answer even when it has no
edges. It names the endpoints of the edges on this page only: ids that occur only in
`by_target` or `by_source_type` (`4730` and `4806` above) are not in the map.

Edge fields:

| Field | Type | Description |
|-------|------|-------------|
| `from` | node id | The depending type |
| `to` | node id | The depended-upon type, possibly external |
| `weight` | integer | Number of references from `from` to `to` |
| `type_pair_count` | integer | Always `1` |
| `attributes` | object | The four attribute flags of the dependency |

**`type_pair_count`** is always 1 because each edge is one type pair. The field
exists so that type edges have the same shape as the aggregated edges of
`aggregated_dependencies` and `pairwise_dependencies`, and a caller can process all
three with the same code.

**`attributes`** carries the flags defined in
[Two levels of dependency](hierograph-mcp-tools.md#33-two-levels-of-dependency):
whether `from` extends, implements or is annotated with `to`, or uses it in any
other way. It tells inheritance apart from use without a detail-level call.

Summary fields:

| Field | Type | Description |
|-------|------|-------------|
| `total` | integer | Number of matching edges |
| `returned` | integer | Number of edges on this page |
| `truncated` | boolean | `true` when `total > returned` |
| `by_attribute` | object | For each of the four flags, the number of matching edges that carry it |
| `by_source_type` | array of `{id, count}` | Depending types by number of edges, top 10 |
| `by_target` | array of `{id, weight}` | Depended-upon types by summed weight, top 10 |

**`total`**, **`returned`** and **`truncated`** follow
[Pages and summaries](hierograph-mcp-tools.md#71-pages-and-summaries).

**`by_attribute`** always lists all four flags, with 0 for absent ones, so a caller
can test "does anything here inherit?" by reading one number.

**`by_source_type`** counts edges, not weight: it shows how many distinct targets each
depending type has, which is the measure of how spread out its dependencies are.

**`by_target`** sums weight: it shows how heavily the source leans on each target,
which is the measure for deciding what is hardest to cut.

### 4.2 Detail level

The detail example of §3.4 with `limit = 3`:

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
    "4730": { "name": "Money", "qualified_name": "com.example.money.Money", "kind": "java.record" },
    "4734": { "name": "com.example.money.Money add(com.example.money.Money)",
              "qualified_name": "com.example.money.Money#com.example.money.Money add(com.example.money.Money)",
              "kind": "java.method" },
    "4735": { "name": "com.example.money.Money zero(java.lang.String)",
              "qualified_name": "com.example.money.Money#com.example.money.Money zero(java.lang.String)",
              "kind": "java.method" }
  },
  "from_scope": 4702,
  "to_scope": 4720,
  "edges": [
    { "from": 4715, "from_parent": 4711, "to": 4730,
      "relationship": "returns", "location": { "line_number": 13 } },
    { "from": 4715, "from_parent": 4711, "to": 4734, "to_parent": 4730,
      "relationship": "calls", "location": { "line_number": 14 } },
    { "from": 4715, "from_parent": 4711, "to": 4735, "to_parent": 4730,
      "relationship": "calls", "location": { "line_number": 13 } }
  ],
  "summary": {
    "total": 6,
    "returned": 3,
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
  "next_cursor": "eyJ2IjoxLCJ0b29sIjoib3V0Z29pbmdfZGVwZW5kZW5jaWVzIiwi…"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `nodes` | object | Slim node map: the scopes, the summary nodes and the endpoints of this page |
| `from_scope` | node id | The depending scope, `fromId` |
| `to_scope` | node id | The depended-upon scope, `toId` |
| `edges` | array | This page of member references |
| `summary` | object | Counts and rankings over all matching rows |
| `next_cursor` | string | Cursor for the next page; absent on the last page |

**`nodes`** names the two scopes, every node in `by_source_nodes`, `by_target_nodes`
and `by_source_type`, and every `from`, `from_parent`, `to` and `to_parent` on the
page. A member of an external type, which is not a graph node, is named from the
snapshot; its kind is `"unknown"` when the snapshot has no record of it.

**`from_scope`** and **`to_scope`** echo the scope ids. They state which side is which,
which matters for
[`incoming_dependencies`](hierograph-tool-incoming-dependencies.md), whose detail
level reports the depender as `from_scope`.

Edge fields:

| Field | Type | Present | Description |
|-------|------|---------|-------------|
| `from` | node id | always | The referencing member, or the type for a type annotation |
| `from_parent` | node id | always | The type that declares `from` |
| `to` | node id | always | The referenced member or type |
| `to_parent` | node id | when `to` is a member | The type that declares `to` |
| `relationship` | string | always | One of the values in §3.2 |
| `location` | object or `null` | always | `{"line_number": n}`, or `null` without a line |
| `inherited` | boolean | when `true` | The row comes from the inheritance closure (§3.2) |

**`from_parent`** and **`to_parent`** let a caller group rows by type without a lookup.
**`inherited`** marks the rows that only the inheritance closure brings in, a
supertype's code or a target's inherited member, so the scope's own rows stay
recognisable; it is absent on all other rows to keep them small.
`to_parent` is absent when `to` is a type, because the type is its own parent.

**`location`** is the line of the instruction for `calls`, `reads_field` and
`writes_field`, and the first line of the method for the signature relationships. It
is `null` for annotations, field types, methods without code, and classes compiled
without line information (see
[Line numbers](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#34-line-numbers)).
The line is what lets a caller open the source at the right place.

Summary fields:

| Field | Type | Present | Description |
|-------|------|---------|-------------|
| `total` | integer | always | Number of matching rows |
| `returned` | integer | always | Number of rows on this page |
| `truncated` | boolean | always | `true` when `total > returned` |
| `inherited` | integer | always | Number of matching rows marked `inherited` |
| `by_relationship` | object | always | Rows per relationship, keys sorted |
| `by_source_type` | array of `{type, edge_count}` | always | Depending types by number of rows, top 10 |
| `others_count` | integer | more than 10 depending types | Number of depending types not in `by_source_type` |
| `by_source_nodes` | array of `{node, aggregated_weight}` | always | Drill-down of the `from` scope (§3.2) |
| `by_target_nodes` | array of `{node, aggregated_weight}` | always | Drill-down of the `to` scope (§3.2) |

**`total`**, **`returned`** and **`truncated`** are the page counts of
[Pages and summaries](hierograph-mcp-tools.md#71-pages-and-summaries), named as at the
type level.

**`by_relationship`** shows the nature of the dependency at a glance: a dependency
made only of `parameter_type` and `returns` rows is an API dependency, one with
`calls` and `writes_field` rows reaches into behaviour.

**`by_source_type`** and **`others_count`** show which types produce the rows, and how
many more there are beyond the top 10.

**`by_source_nodes`** and **`by_target_nodes`** suggest the next, narrower call: the
child of a package with the highest weight is where most of the dependency lives.

---

## 5. Errors

The two levels report errors in different shapes, as described in
[Error shapes](hierograph-mcp-tools.md#81-error-shapes).

**Type level and parameter checks** use the nested shape, with `code`, `message` and
`recovery`. The checks run in this order:

| Code | Situation | Extra fields |
|------|-----------|--------------|
| `INVALID_PARAMETER` | `detailLevel` is not `"type"` or `"detail"` | — |
| `INVALID_PARAMETER` | `relationship` given at the type level | — |
| `INVALID_PARAMETER` | `toId` missing at the detail level | — |
| `NODE_NOT_FOUND` | `fromId`, then `toId`, names no node | — |
| `INVALID_NODE_KIND` | `fromId` or `toId` names a method or a field | `actual_kind`, `declaring_type` |
| cursor codes | the cursor is malformed, from another tool, or stale | see [Cursors](hierograph-mcp-tools.md#72-cursors) |
| `RESULT_TOO_LARGE` | more than 142 edges on the page | see [The response budget](hierograph-mcp-tools.md#73-the-response-budget) |

`INVALID_NODE_KIND` carries the member's declaring type as a
[minimal NodeRef](hierograph-mcp-tools.md#61-minimal-noderef) and a recovery that names
it and points to the detail level, which accepts the member itself.

**Detail level** checks use the same nested shape. They run after the parameter
checks above, in this order:

| Code | Situation | Recovery |
|------|-----------|----------|
| `NODE_NOT_FOUND` | `fromId`, then `toId`, names no node; any node, the root included, is a valid scope | use `find_node` |
| `INVALID_RELATIONSHIP` | `relationship` is not in the vocabulary of §3.2; the message lists the allowed values | use an allowed value, or omit `relationship` |
| `DETAIL_UNAVAILABLE` | the loaded graph has no member-level data | use `detailLevel = "type"` |
| `DETAIL_REBUILDING` | the member-level data is being rewritten after a rescan | retry in a moment |

`NODE_NOT_FOUND` reads as at the type level, naming the missing id:

```json
{ "error": {
    "code": "NODE_NOT_FOUND",
    "message": "No node with id 9999 exists in the graph.",
    "recovery": "Use find_node to look up the correct node ID." } }
```

Cursor errors and `RESULT_TOO_LARGE` (more than 90 rows on a page) come from the
shared pagination.

While no graph is loaded the tool returns the
[no-graph response](hierograph-mcp-tools.md#83-no-graph-loaded).

---

## 6. Working with the tool

**Ask "what does X use?".** Call the open form with `limit = 1`:
`outgoing_dependencies(fromId = 4711, limit = 1)`. Read `by_target` for the
most-used types and `by_attribute` for the kinds of dependency; the one edge is a
by-product. A ranked id that is not on the page is not in the `nodes` map;
[`type_details`](hierograph-tool-type-details.md) names it.

**Explain an aggregated weight.** Pass the pair from
[`aggregated_dependencies`](hierograph-tool-aggregated-dependencies.md) or a cell of
[`pairwise_dependencies`](hierograph-tool-pairwise-dependencies.md):
`outgoing_dependencies(fromId = 4702, toId = 4720)` lists the type edges behind the
weight 6.

**Zoom to members.** Repeat the call with `detailLevel = "detail"`. Narrow it with a
smaller scope (one type from `by_source_type`) or a `relationship` such as `calls`
before paging through a large result.

**Find who touches a type's fields.** Use `relationship = "read_by"` or
`"written_by"` with the fields' type as `fromId` and the suspected users as `toId`.

**Page.** Pass `next_cursor` back with the same `fromId`, `toId`, `detailLevel` and
`relationship`. Stay at or below 142 type edges or 90 detail rows per page.

**Read the other direction.** For "what uses X?", call
[`incoming_dependencies`](hierograph-tool-incoming-dependencies.md) with the same
`fromId`.

---

## 7. Guarantees

1. **Full-result summaries.** Every summary field describes all matching edges or
   rows, independent of `limit` and the page.
2. **Consistent weights.** The `weight` values of the type edges between two subtrees
   add up to the aggregated weight of that pair.
3. **Deterministic order.** For the same graph and parameters, edges and rows come in
   the same order, so a cursor walk returns each exactly once.
4. **Scopes always named.** The `nodes` map always contains the scope nodes, also when
   the result is empty.
5. **No hidden filtering.** A type-level call never drops edges silently: an
   unsupported parameter is an error, and external types are included.
6. **Read-only.** The tool does not change the graph.
