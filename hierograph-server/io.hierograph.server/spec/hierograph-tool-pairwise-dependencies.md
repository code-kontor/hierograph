# Hierograph Tool `pairwise_dependencies` — Specification

`pairwise_dependencies` is Hierograph's **dependency structure matrix tool**. Given a
set of nodes (typically modules or packages), it computes every aggregated dependency
between them and returns two things: a short structural summary of the whole set
(density, cycles, strongly connected components, a layering order) and a paginated
list of the matrix's non-empty cells. It is the tool for "is this set of modules
layered, and where are the cycles?".

This document explains why the tool exists, what it accepts, how it builds the
matrix, what it returns and how to work with it. The concepts all tools share (node
ids, the slim node map, pagination, error shapes) are defined in the
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

A **dependency structure matrix** (DSM) lists a set of nodes as rows and columns; the
cell in row `i`, column `j` holds the weight with which node `i` depends on node `j`.
Ordered well, a layered system shows all its weight on one side of the diagonal, and
every cell on the other side is a dependency against the layering. The matrix is the
standard instrument for judging the architecture of a set of modules.

`pairwise_dependencies` computes that matrix over the
[aggregated dependencies](../../../hierograph-hierarchicalgraph/io.hierograph.hierarchicalgraph.model/spec/hierarchicalgraph-model.md#62-aggregated-dependencies)
of the hierarchical graph. Its design goals:

**Structure before cells.** Most questions about a node set are structural: is it
acyclic, which nodes form cycles, what is the layering. The summary answers them in a
few hundred bytes, computed over the whole set, so a caller rarely needs the cells
at all.

**Cells on demand.** A matrix over many modules has hundreds of non-empty cells. The
tool pages them, can drop light cells with `minWeight`, and can order them heaviest
first, so the caller fetches only the cells that matter.

**The order is the answer.** The tool orders the nodes so that dependers come before
the nodes they depend on. In an acyclic set that order is a valid layering, and the
cell list follows it.

### `pairwise_dependencies` and its neighbours

| Tool | Input | Answers |
|------|-------|---------|
| `pairwise_dependencies` | one node set | the full matrix within the set, with cycles and a layering order |
| [`aggregated_dependencies`](hierograph-tool-aggregated-dependencies.md) | any sources × any targets | how strongly each source depends on each target |
| [`outgoing_dependencies`](hierograph-tool-outgoing-dependencies.md) | one source, optional target | which type-level edges or member references make up one dependency |

`pairwise_dependencies` is needed beside `aggregated_dependencies` because:

1. It analyses the set as a whole. Cycles and a layering order are properties of the
   set, not of any single pair, and only a tool that sees every pair can compute them.
2. It scales to large sets. The input may hold up to 1,000 nodes and the cell list is
   paginated, whereas `aggregated_dependencies` returns its whole cross product at
   once and caps it at 2,500 pairs.

---

## 2. Parameters

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `nodeIds` | array of node ids | yes | — | The nodes of the matrix, 2 to 1,000 |
| `edgeSort` | string | no | `"dsm"` | Order of the edge list: `"dsm"` or `"weight_desc"` |
| `minWeight` | integer | no | `1` | Smallest weight an edge must have to be listed |
| `limit` | integer | no | `25` | Edges per page, clamped to 1–200 |
| `cursor` | string | no | — | `next_cursor` of the previous page |
| `view` | string | no | none | the view to answer in |

**`nodeIds`** names the subtrees that become the rows and columns. Each id stands for
the node and everything below it. Any node except a method or a field is accepted,
including types, external types and the root. The nodes must be disjoint: each row
stands for a whole subtree, so an id listed twice, or a node inside another listed
node, would count the same dependencies in two rows. Which row got them would depend
on the input order, and a package listed together with its module can even look
cyclic with it. The tool therefore rejects such a set with `INVALID_PARAMETER` (§5).
At least two nodes are needed for a matrix; more than 1,000 are refused, because the
cycle analysis and the summary grow with the node count.

**`edgeSort`** chooses the order of the edge list. `"dsm"` follows the matrix: by row,
then by column, in the node order of §3.2, so an acyclic set lists only cells on one
side of the diagonal. `"weight_desc"` puts the heaviest edges first, so a first page
of a few edges already holds the strongest couplings. Values outside the two are
rejected.

**`minWeight`** drops edges lighter than the threshold from the edge list. On a large
set most cells are light, and a threshold leaves only the couplings worth
discussing. A value below 1 is raised to 1. The threshold does not affect the summary:
density, cycles and the node order always describe the full matrix, so filtering the
list never hides a cycle.

**`limit`** is the page size of the edge list. The default of 25 keeps a first page
small. Values are clamped as described in
[Parameters](hierograph-mcp-tools.md#5-parameters).

**`cursor`** continues an edge list. It is bound to this tool, to `nodeIds` (in their
given order), `edgeSort` and `minWeight`, and to the loaded graph; see
[Cursors](hierograph-mcp-tools.md#72-cursors). `limit` may change between pages.

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

### 3.1 Building the matrix

The tool resolves every id, then walks each node's accumulated outgoing dependencies
once and charges each dependency to the cell of the node that contains its target.
For every non-empty cell `(i, j)` with `i ≠ j` it records:

| Value | Computed as |
|-------|-------------|
| weight | the summed weight of the type-level dependencies from subtree `i` into subtree `j` |
| type pair count | the number of distinct (depending type, depended-upon type) pairs among them |
| attributes | the union of their four attribute flags |

The weight of a type-level dependency is the number of individual references the
depending type makes to the target type (see
[`refs.parquet`](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#44-refsparquet)).
The diagonal, the dependencies inside one node, is left out: a node depending on
itself says nothing about the structure of the set. Dependencies to anything outside
the set are ignored.

### 3.2 Node order, cycles and layering

The tool then analyses the matrix:

1. It finds the **strongly connected components** (SCCs): maximal groups of nodes in
   which every node reaches every other through dependencies. A component with two or
   more nodes is a **cycle**.
2. It orders the nodes inside each component with a feedback-arc-set heuristic, so
   that few dependencies point backwards.
3. It orders the components so that every node comes before the nodes it depends on:
   **dependers first, foundations last**.

This node order is the order of the matrix's rows and columns, and the `"dsm"` edge
order follows it. When the set has no cycle, the order is a topological order of the
dependency graph and is returned as `topological_order`: reading it top to bottom goes
from the top layer down to the foundation, and every dependency points forward in it. When
the set has a cycle, no such order exists, and `topological_order` is left out.

### 3.3 Filtering, ordering and paging

The complete edge list is built first. `edge_count` and `density` are taken from it.
Then `minWeight` removes light edges, `edgeSort` orders the rest, and the result is
paged. With `"weight_desc"`, edges of equal weight follow the matrix order, so both
orders are total and pages never overlap.

### 3.4 Worked example

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

The call `pairwise_dependencies(nodeIds = [4720, 4702, 4803])` builds this matrix. The
rows and columns are in node order; `·` is an empty cell, `■` the excluded diagonal:

```
                      billing   money   BigDecimal
billing   (4702)         ■        6         1
money     (4720)         ·        ■         4
BigDecimal(4803)         ·        ·         ■
```

- billing → money: `Invoice → Money` 3 + `Invoice$Line → Money` 2 +
  `Ledger → Money` 1 = 6, from 3 type pairs.
- billing → `BigDecimal`: `Invoice$Line → BigDecimal` 1, from 1 type pair.
- money → `BigDecimal`: `Money → BigDecimal` 4, from 1 type pair.
- The dependencies inside billing (`Invoice → Ledger`, `Ledger → Invoice`, …) are on
  the diagonal and left out. `Invoice → Logger` leaves the set and is ignored.

Three cells out of `3 × 2 = 6` possible are filled, so the density is 0.5. The set is
acyclic, and the topological order is `[4702, 4720, 4803]`: billing on top, money in
the middle, `BigDecimal` as the foundation, although the input listed money first.

If `Money` also depended on `Ledger`, billing and money would form a cycle: the
summary would report `has_cycles: true`, the component `[4702, 4720]` (in some order),
and no `topological_order`. The money → billing cell would then lie below the
diagonal.

---

## 4. Response

### 4.1 Example

The first page of the call in §3.4, with `limit = 2`:

```json
{
  "nodes": {
    "4702": { "name": "billing", "qualified_name": "com.example.billing", "kind": "java.package" },
    "4720": { "name": "money", "qualified_name": "com.example.money", "kind": "java.package" },
    "4803": { "name": "BigDecimal", "qualified_name": "java.math.BigDecimal", "kind": "external.type" }
  },
  "summary": {
    "total": 3,
    "returned": 2,
    "truncated": true,
    "node_count": 3,
    "edge_count": 3,
    "possible_edges": 6,
    "density": 0.5,
    "has_cycles": false,
    "strongly_connected_components": [],
    "topological_order": [4702, 4720, 4803]
  },
  "edges": [
    { "from": 4702, "to": 4720, "weight": 6, "type_pair_count": 3,
      "attributes": { "is_extends": false, "is_implements": false,
                      "is_annotated_by": false, "is_depends_on_other": true } },
    { "from": 4702, "to": 4803, "weight": 1, "type_pair_count": 1,
      "attributes": { "is_extends": false, "is_implements": false,
                      "is_annotated_by": false, "is_depends_on_other": true } }
  ],
  "next_cursor": "eyJ2IjoxLCJ0b29sIjoicGFpcndpc2VfZGVwZW5kZW5jaWVzIiwi…"
}
```

The second page, called with the cursor, holds the page counts and the edge list:

```json
{
  "summary": { "total": 3, "returned": 1, "truncated": true },
  "edges": [
    { "from": 4720, "to": 4803, "weight": 4, "type_pair_count": 1,
      "attributes": { "is_extends": false, "is_implements": false,
                      "is_annotated_by": false, "is_depends_on_other": true } }
  ]
}
```

### 4.2 Top-level fields

| Field | Type | Present | Description |
|-------|------|---------|-------------|
| `nodes` | object | first page | Slim node map of every node in the set, in node order |
| `summary` | object | always | Page counts; on the first page also the structural summary of the full matrix |
| `edges` | array | always | This page of the edge list |
| `next_cursor` | string | when more edges follow | Cursor for the next page |

**`nodes`** is the [slim node map](hierograph-mcp-tools.md#63-slim-node-map) of all
nodes in the set, whether connected or not, in the node order of §3.2. It names every
id the summary and the edges use.

**`nodes` and the matrix analysis in `summary` appear on the first page only**, that
is, on a call without a `cursor`. They describe the whole set and do not change from
page to page; repeating them on every page would cost bytes the edge list needs. The
page counts `total`, `returned` and `truncated` appear on every page, as in every
paginated tool ([Pages and summaries](hierograph-mcp-tools.md#71-pages-and-summaries)).

**`next_cursor`** is present when more edges follow and absent on the last page. Its
presence is the signal that the list continues; `truncated` stays `true` on the last
page of a multi-page walk.

### 4.3 Summary fields

| Field | Type | Description |
|-------|------|-------------|
| `total` | integer | Number of edges after `minWeight`: the length of the paged list; every page |
| `returned` | integer | Number of edges on this page; every page |
| `truncated` | boolean | `true` when `total > returned`; every page |
| `node_count` | integer | Number of distinct nodes in the matrix |
| `edge_count` | integer | Number of non-empty off-diagonal cells, before `minWeight` |
| `possible_edges` | integer | `node_count × (node_count − 1)` |
| `density` | number | `edge_count / possible_edges`, rounded to two decimals |
| `has_cycles` | boolean | `true` when at least one cycle exists |
| `strongly_connected_components` | array of arrays of node ids | The cycles: every component with two or more nodes |
| `topological_order` | array of node ids | The node order, dependers first; only when `has_cycles` is `false` |

**`edge_count`** and **`total`** are both needed because `minWeight` separates the
structure from the listing. `edge_count` describes the matrix and drives the density;
`total` tells the caller how long the filtered list is and how many pages it will take.

**`density`** measures how entangled the set is. A low density on many modules is a
sign of clear separation; a density near 1 means almost every node depends on almost
every other.

**`has_cycles`** and **`strongly_connected_components`** answer the most common
layering question directly. Components of a single node are not listed, since a node
alone is not a cycle; the list is empty for an acyclic set.

**`topological_order`** is the layering, top first. It is omitted rather than
approximated when cycles exist, because an order that violates some dependencies is
not a layering.

### 4.4 Edge fields

| Field | Type | Description |
|-------|------|-------------|
| `from` | node id | The depending node |
| `to` | node id | The depended-upon node |
| `weight` | integer | Summed weight of the type-level dependencies in the cell |
| `type_pair_count` | integer | Number of distinct type pairs in the cell |
| `attributes` | object | The four attribute flags, each `true` or `false` |

**`weight`**, **`type_pair_count`** and **`attributes`** have the meaning they have in
[`aggregated_dependencies`](hierograph-tool-aggregated-dependencies.md#43-edge-fields):
the strength, the breadth and the kinds of the coupling. The attribute flags are
defined in
[Two levels of dependency](hierograph-mcp-tools.md#33-two-levels-of-dependency). The
same pair of nodes gives the same three values in both tools.

---

## 5. Errors

Errors use the nested shape described in
[Error shapes](hierograph-mcp-tools.md#81-error-shapes): an `error` object with
`code`, `message` and `recovery`. The checks run in this order, and the first failure
is returned.

| Code | Situation | Extra fields |
|------|-----------|--------------|
| `INPUT_TOO_SMALL` | fewer than 2 ids in `nodeIds` | — |
| `INPUT_TOO_LARGE` | more than 1,000 ids in `nodeIds` | `node_count`, `max_nodes` |
| `INVALID_PARAMETER` | `edgeSort` outside its value set | — |
| `NODE_NOT_FOUND` | an id names no node | — |
| `INVALID_NODE_KIND` | an id names a method or a field | `actual_kind`, `declaring_type` |
| `INVALID_PARAMETER` | `nodeIds` lists an id twice, or a node inside another listed node | `duplicates` (ids), `nested` (`{node, inside}` pairs) |
| cursor codes | the cursor is malformed, from another tool, or stale | see [Cursors](hierograph-mcp-tools.md#72-cursors) |

The size checks run before any id is looked up, so an empty or oversized list fails
at once. `INVALID_NODE_KIND` carries the member's declaring type as a
[minimal NodeRef](hierograph-mcp-tools.md#61-minimal-noderef), with a recovery that
says to pass that id instead.

The cap of 200 edges at 250 bytes each is the largest page that fits the
[response budget](hierograph-mcp-tools.md#73-the-response-budget).

While no graph is loaded the tool returns the
[no-graph response](hierograph-mcp-tools.md#83-no-graph-loaded).

---

## 6. Working with the tool

**Check layering.** Call with the module ids and `limit = 1`, and read the summary.
`has_cycles: false` and `topological_order` give the layering; the single edge is a
by-product.

**Find the cycles.** When `has_cycles` is `true`, each entry of
`strongly_connected_components` is one cycle. To find the edges that close it, call
[`aggregated_dependencies`](hierograph-tool-aggregated-dependencies.md) with the
component's nodes as both sources and targets.

**Show the strongest couplings.** Use `edgeSort = "weight_desc"` with a small `limit`.
The first page holds the heaviest edges.

**Thin a large matrix.** Raise `minWeight` until `summary.total` is small
enough to read. The summary still describes the full matrix.

**Draw the full matrix.** Walk all pages with `edgeSort = "dsm"`, keeping `nodeIds`,
`edgeSort` and `minWeight` unchanged, and place each edge in the row of
`from` and the column of `to`, using the order of `nodes`.

**Explain one cell.** Pass its two ids to
[`outgoing_dependencies`](hierograph-tool-outgoing-dependencies.md):
`outgoing_dependencies(fromId = 4702, toId = 4720)` lists the three type-level edges
behind the weight 6.

---

## 7. Guarantees

1. **Full-set summary.** `node_count`, `edge_count`, `density`, the components and
   `topological_order` describe the full matrix, independent of `minWeight`, `limit`
   and the page.
2. **Topological order.** When `topological_order` is present, every edge goes from an
   earlier node in it (`from`) to a later one (`to`).
3. **One reading.** Every edge reads depending node (`from`) → depended-upon node
   (`to`), as in every other dependency tool. A caller that reads the matrix by
   column swaps the two itself.
4. **No self-edges.** No edge has the same node as `from` and `to`.
5. **Deterministic pages.** For the same graph and parameters, the edge list has the
   same order, so a cursor walk returns every matching edge exactly once.
6. **Read-only.** The tool does not change the graph.
