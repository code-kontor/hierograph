# Hierograph Tool `aggregated_dependencies` — Specification

`aggregated_dependencies` is Hierograph's **coupling-strength query between chosen
subtrees**. Given a list of source nodes and a list of target nodes (modules, packages
or types), it returns one aggregated edge for every source–target pair that is
connected, with the summed weight, the number of type pairs behind it and the kinds of
dependency involved. It is the cheapest way to answer "how strongly does A depend on
B?" for any A and B in the tree.

This document explains why the tool exists, what it accepts, how it computes its
edges, what it returns and how to work with it. The concepts all tools share (node
ids, kinds, the slim node map, error shapes) are defined in the
[MCP tools specification](hierograph-mcp-tools.md).

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

Architecture questions are asked about groups: does the billing package use the money
package, how much does the module lean on an external library. The hierarchical graph
answers them with **aggregated dependencies**: one virtual edge between two subtrees
that summarizes every type-level dependency from somewhere in the first to somewhere
in the second (see
[aggregated dependencies](../../../hierograph-hierarchicalgraph/io.hierograph.hierarchicalgraph.model/spec/hierarchicalgraph-model.md#62-aggregated-dependencies)
in the model specification). `aggregated_dependencies` exposes exactly that
computation, for many pairs at once.

**Direction by placement.** The tool has no direction parameter. The depending side
goes into `sourceIds`, the depended-upon side into `targetIds`. "What does A use of B"
and "what does B use of A" are the same call with the lists swapped, so one tool covers
both questions.

**Bounded by its input.** The result has at most `|sourceIds| × |targetIds|` edges, so
the tool needs no pagination. The caller controls the response size by choosing the
input, and a cap on the cross product keeps a careless call from producing a huge
answer.

**Weights, not booleans.** Every edge carries the summed weight of the underlying
dependencies. A weight of 1 and a weight of 400 are very different architectural
facts; a yes/no answer would hide that difference.

### `aggregated_dependencies` and its neighbours

Three other tools also report dependencies. They answer different questions:

| Tool | Input | Answers |
|------|-------|---------|
| `aggregated_dependencies` | any sources × any targets | how strongly each source depends on each target |
| [`pairwise_dependencies`](hierograph-tool-pairwise-dependencies.md) | one node set | the full matrix within the set, with cycles and a layering order |
| [`outgoing_dependencies`](hierograph-tool-outgoing-dependencies.md) | one source, optional target | which type-level edges, or which member references, make up a dependency |
| [`incoming_dependencies`](hierograph-tool-incoming-dependencies.md) | one depended-upon node, optional depender | the same, read from the depended-upon side |

`aggregated_dependencies` is needed beside `pairwise_dependencies` because:

1. Its question is asymmetric. "How do these five packages use these two libraries"
   has no use for the reverse direction or for pairs within one side, both of which a
   matrix computes.
2. Its sources and targets may overlap or nest. A matrix is defined over disjoint
   subtrees; here, the source `billing-core` and the target `com.example.money` (a
   package inside it) form a valid pair.
3. It returns everything in one response. There is no cursor and no first-page
   summary to keep track of.

It is needed beside `outgoing_dependencies` because it stops at the aggregate. It says
*how much*; the evidence tools say *which types* and *which members*.

---

## 2. Parameters

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `sourceIds` | array of node ids | yes | — | The depending subtrees: modules, packages or types |
| `targetIds` | array of node ids | yes | — | The depended-upon subtrees: modules, packages or types |
| `view` | string | no | none | the view to answer in |

Parameter names follow the conventions in
[Parameters](hierograph-mcp-tools.md#5-parameters).

**`sourceIds`** names the subtrees whose outgoing dependencies are measured. Each id
stands for the node *and everything below it*. A module id covers all its packages and
types; a type id covers the type and its members. Any node except a method or a field
is accepted, including the root (the whole graph) and external types. Methods and
fields are rejected (§5) because the aggregated graph is built from type-level edges:
a member has no edges of its own at that level.

**`targetIds`** names the subtrees the sources may depend on, with the same rules. A
list of ids rather than a single id lets one call measure a source against every
candidate target, for example every module of a project.

Each list needs at least one id; an empty list fails with `INPUT_TOO_SMALL` (§5),
because a dependency needs two ends. A repeated id adds nothing and is ignored after
its first occurrence. The order of each list is kept: it determines the order of the
edges (§3.3).

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

### 3.1 Computing one edge

For each pair `(s, t)` the tool asks the hierarchy for the aggregated dependency from
`s` to `t`. That dependency collects every type-level dependency whose depending type
lies in the subtree of `s` and whose depended-upon type lies in the subtree of `t`.
Each such type-level dependency has a **weight**: the number of individual references
the depending type's bytecode makes to the target type, summed over all kinds of
reference (calls, field types, parameter types, inheritance, …; see the
[type-level aggregate](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#64-the-type-level-aggregate)
in the format specification).

From the collected dependencies the tool builds the edge:

| Field | Computed as |
|-------|-------------|
| `weight` | the sum of the collected weights |
| `type_pair_count` | the number of distinct (depending type, depended-upon type) pairs collected |
| `attributes` | the union of the four attribute flags of the collected dependencies |

A pair whose aggregated weight is 0 has no edge and is left out of `edges`. The summary
still counts it (§4.3), so the caller can tell "no dependency" apart from "not asked".

The definition is literal for overlapping inputs. A pair `(s, s)` collects the
dependencies internal to `s`. A pair where `t` lies inside `s` collects the
dependencies from all of `s`, including `t` itself, into `t`.

### 3.2 Worked example

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

The weight 3 from `Invoice` to `Money` is two calls (`Money.zero`, `Money.add`) plus
one return type.

The call `aggregated_dependencies(sourceIds = [4702, 4720], targetIds = [4720, 4803])`
asks for four pairs:

| Pair | Collected dependencies | `weight` | `type_pair_count` |
|------|------------------------|---------:|------------------:|
| billing → money | `Invoice → Money` 3, `Invoice$Line → Money` 2, `Ledger → Money` 1 | 6 | 3 |
| billing → `BigDecimal` | `Invoice$Line → BigDecimal` 1 | 1 | 1 |
| money → money | none (`Money` has no internal dependencies) | 0 | — |
| money → `BigDecimal` | `Money → BigDecimal` 4 | 4 | 1 |

Three edges are returned. The pair money → money is left out and counted in
`pairs_without_dependency`. The dependencies inside `billing`, such as
`Invoice → Ledger`, belong to no requested pair and are not counted anywhere.

### 3.3 Ordering

Edges appear in input order: for each source in the order of `sourceIds`, the targets
in the order of `targetIds`. The caller who lists sources by priority reads the
answer in the same order.

### 3.4 Limits

The cross product `|sourceIds| × |targetIds|` may be at most **2,500**. The check
happens before any id is looked up, so an oversized call fails at once, whatever the
ids are. The cap exists to stop degenerate calls (every type against every type);
realistic questions stay far below it. For all pairs within one set of nodes,
`pairwise_dependencies` is the better tool: it is paginated and reports structure.

---

## 4. Response

### 4.1 Example

The response to the call in §3.2:

```json
{
  "nodes": {
    "4702": { "name": "billing", "qualified_name": "com.example.billing", "kind": "java.package" },
    "4720": { "name": "money", "qualified_name": "com.example.money", "kind": "java.package" },
    "4803": { "name": "BigDecimal", "qualified_name": "java.math.BigDecimal", "kind": "external.type" }
  },
  "edges": [
    { "from": 4702, "to": 4720, "weight": 6, "type_pair_count": 3,
      "attributes": { "is_extends": false, "is_implements": false,
                      "is_annotated_by": false, "is_depends_on_other": true } },
    { "from": 4702, "to": 4803, "weight": 1, "type_pair_count": 1,
      "attributes": { "is_extends": false, "is_implements": false,
                      "is_annotated_by": false, "is_depends_on_other": true } },
    { "from": 4720, "to": 4803, "weight": 4, "type_pair_count": 1,
      "attributes": { "is_extends": false, "is_implements": false,
                      "is_annotated_by": false, "is_depends_on_other": true } }
  ],
  "summary": {
    "total_pairs_requested": 4,
    "pairs_with_dependency": 3,
    "pairs_without_dependency": 1
  }
}
```

### 4.2 Top-level fields

| Field | Type | Description |
|-------|------|-------------|
| `nodes` | object | Slim node map of the nodes that appear in `edges` |
| `edges` | array | One entry per connected pair, in input order |
| `summary` | object | Counts over all requested pairs |

**`nodes`** is the [slim node map](hierograph-mcp-tools.md#63-slim-node-map). It holds
only the nodes that occur in an edge, so an input id whose pairs are all unconnected
does not appear. The caller already knows its own input ids; the map exists to name
the endpoints of the edges without repeating the names in every edge.

### 4.3 Edge fields

| Field | Type | Description |
|-------|------|-------------|
| `from` | node id | The source node, as given in `sourceIds` |
| `to` | node id | The target node, as given in `targetIds` |
| `weight` | integer | Summed weight of the collected type-level dependencies |
| `type_pair_count` | integer | Number of distinct type pairs behind the edge |
| `attributes` | object | The four attribute flags, each `true` or `false` |

**`from`** and **`to`** are the input ids, not the types inside them. An edge says
"something in this subtree depends on something in that one"; the evidence tools
name the types.

**`weight`** measures the strength of the coupling. Two pairs with the same
`type_pair_count` can differ widely in weight, and the weight is what a dependency
matrix shows in its cells.

**`type_pair_count`** measures the breadth of the coupling. A weight of 40 from one
type pair is a single heavy user; a weight of 40 spread over 30 type pairs is a
pervasive dependency that is much harder to remove. Weight alone cannot tell the two
apart.

**`attributes`** says *what kind* of dependency the edge contains, using the flags
`is_extends`, `is_implements`, `is_annotated_by` and `is_depends_on_other` (defined in
[Two levels of dependency](hierograph-mcp-tools.md#33-two-levels-of-dependency)). A
flag is `true` when at least one collected dependency carries it. Inheritance is a far
stronger tie than a method call, and the flags let a caller see that without asking
for the type-level edges.

### 4.4 Summary fields

| Field | Type | Description |
|-------|------|-------------|
| `total_pairs_requested` | integer | Number of distinct `sourceIds` × number of distinct `targetIds` |
| `pairs_with_dependency` | integer | Number of returned edges |
| `pairs_without_dependency` | integer | Requested pairs with no dependency |

**The summary** accounts for the pairs that `edges` leaves out. Without it, a caller
could not tell from a short edge list whether a pair was unconnected or never
requested.

---

## 5. Errors

Errors use the nested shape described in
[Error shapes](hierograph-mcp-tools.md#81-error-shapes): an `error` object with
`code`, `message` and `recovery`. The checks run in this order, and the first failure
is returned.

| Code | Situation | Extra fields |
|------|-----------|--------------|
| `INPUT_TOO_SMALL` | `sourceIds` or `targetIds` is empty | — |
| `INPUT_TOO_LARGE` | number of distinct `sourceIds` × distinct `targetIds` exceeds 2,500 | `source_count`, `target_count`, `cross_product`, `max_cross_product` |
| `NODE_NOT_FOUND` | an id names no node (sources are checked before targets) | — |
| `INVALID_NODE_KIND` | an id names a method or a field | `actual_kind`, `declaring_type` |

```json
{ "error": {
    "code": "INVALID_NODE_KIND",
    "message": "This tool operates on type-level dependencies. The node is a java.method, not a type.",
    "actual_kind": "java.method",
    "declaring_type": { "id": 4711, "name": "Invoice", "qualified_name": "com.example.billing.Invoice",
                        "kind": "java.class", "parent_id": 4702, "parent_kind": "java.package" },
    "recovery": "To query dependencies involving this java.method's declaring type, pass its id (4711). For the member itself, call outgoing_dependencies or incoming_dependencies with detailLevel='detail', which accept member ids." } }
```

**`declaring_type`** is a [minimal NodeRef](hierograph-mcp-tools.md#61-minimal-noderef)
of the member's type. A caller that passed a method by mistake can retry with that
id at once, without a lookup.

While no graph is loaded the tool returns the
[no-graph response](hierograph-mcp-tools.md#83-no-graph-loaded).

---

## 6. Working with the tool

**Measure one dependency.** Pass one id in each list:
`aggregated_dependencies(sourceIds = [4702], targetIds = [4720])` returns one edge with
weight 6, or no edge when the packages are unconnected.

**Check both directions.** Run the call twice with the lists swapped. A non-empty
answer in both directions is a cycle between the two subtrees.

**Rank candidates.** Pass one source and many targets, for example a package against
every module, and sort the edges by `weight`. Pairs without an edge are the targets
the package does not use.

**Drill down.** An edge of interest becomes the input of
[`outgoing_dependencies`](hierograph-tool-outgoing-dependencies.md):
`outgoing_dependencies(fromId = 4702, toId = 4720)` lists the three type-level edges
behind the weight 6.

**Stay under the cap.** For many nodes against each other, use
[`pairwise_dependencies`](hierograph-tool-pairwise-dependencies.md) instead of a
large cross product.

---

## 7. Guarantees

1. **Literal aggregation.** An edge's `weight` equals the sum of the weights of the
   type-level dependencies from the source subtree into the target subtree, as the
   hierarchy defines them.
2. **No zero edges.** Every returned edge has a weight of at least 1, and every
   requested pair is either an edge or counted in `pairs_without_dependency`.
3. **Input order.** Edges appear in source order, then target order, for every call.
4. **Bounded size.** A successful response has at most 2,500 edges.
5. **Read-only.** The tool does not change the graph.
