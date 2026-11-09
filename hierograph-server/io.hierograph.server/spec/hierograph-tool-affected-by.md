# Hierograph Tool `affected_by` — Specification

`affected_by` is Hierograph's **blast-radius tool**: given a module, package or type,
it returns every type that transitively depends on it, or every type it transitively
depends on. It answers "what breaks if I change this?" and "what does this rely on?"
in one call, with the hop distance of each type and a summary by distance and by
module.

This document explains why the tool exists, its parameters, how it traverses the
graph, what it returns, and how to work with it. Concepts shared by all tools (kinds,
node references, pagination, errors) are defined in the
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

Before changing a type, a developer wants to know what the change can reach. The
direct dependents are not enough: if `Invoice` depends on `Money` and `InvoiceTest`
depends on `Invoice`, a change to `Money` can break `InvoiceTest` although
`InvoiceTest` never mentions `Money`. Finding that by hand means calling
`incoming_dependencies` once per type, level after level, and merging the results.
`affected_by` does the whole walk on the server and returns the closure as one ranked
list.

**Closure, not edges.** The tool returns *types*, each once, with the smallest number
of hops at which it is reached. It does not return every edge between them. A model
that asks "what is affected" wants a set and its shape, not a graph.

**Shape before detail.** The summary counts the affected types per distance and per
module over the whole result. "Changing `Money` affects 3 types directly and 1 more
at two hops, all in `billing-core` and its tests" is often the complete answer, and it
is available from a one-item page.

**Scopes as input.** A package or module is expanded to the types inside it, so "what
is affected by `com.example.billing`?" is one call. Types inside the input scope are
never reported as affected by it.

### `affected_by` and its neighbours

| Tool | Returns | Depth | Use it for |
|------|---------|-------|------------|
| `incoming_dependencies` / `outgoing_dependencies` | type-level edges (or member references) with weights and attribute flags | one hop | *how* one node is coupled to its direct neighbours |
| `affected_by` | the set of types reached, each with distance and one example path | any number of hops | *how far* a change can ripple, or what a scope needs transitively |
| `find_dependency_path` | the shortest dependency chains between two given scopes | any number of hops | *why* one scope depends on another |

All three are needed:

1. The direct-dependency tools show weights and flags per edge, which a closure
   cannot carry without becoming an edge dump.
2. `affected_by` answers the impact question in one bounded call, which the
   direct-dependency tools can only answer through many calls.
3. `find_dependency_path` starts from both ends and explains one specific
   connection; `affected_by` starts from one end and enumerates everything reachable.

---

## 2. Parameters

Parameter names are camelCase, as for all tools (see
[Parameters](hierograph-mcp-tools.md#5-parameters)).

| Parameter | Type | Required | Default | Meaning |
|-----------|------|----------|---------|---------|
| `nodeId` | integer | yes | — | the module, package or type to start from |
| `direction` | string | no | `"incoming"` | `"incoming"`: what depends on the input; `"outgoing"`: what the input depends on |
| `maxDepth` | integer | no | unbounded | the largest distance to report |
| `kindFilter` | string array | no | no filter | the kinds of type to keep in the result |
| `limit` | integer | no | `100` | items per page, clamped to 1–111 |
| `cursor` | string | no | first page | the `next_cursor` of the previous page |

**`nodeId`** accepts any node whose dependencies are type-level: a module, a package, a
type or an external type. The root's id is also accepted; it makes every type a source,
so the result is empty. A method or field is rejected (§5), because the tool works on
type-level dependencies and a member has none of its own.

**`direction`** exists because impact runs both ways. `"incoming"` follows dependencies
backwards and answers "what breaks if I change this?". `"outgoing"` follows them
forwards and answers "what must be present for this to compile?", the question behind
extracting a package into its own module. The value is case-sensitive.

**`maxDepth`** bounds the walk. On a central type the unbounded closure can contain a
large part of the codebase; "what is within two hops" is often the useful question.
A `maxDepth` below `1` is rejected with `INVALID_PARAMETER`: direct neighbours are the
smallest blast radius there is.

**`kindFilter`** narrows the *result*, not the walk. A type of a filtered-out kind is
still traversed, so a class reached only through an interface keeps its true distance.
The filter accepts the kind values and group aliases of
[Kinds](hierograph-mcp-tools.md#32-kinds) that a result can have: the five type kinds,
`external.type` and the `types` alias. `[]` is no filter. Any other value, an unknown
one or a kind that is never affected (`packages`, `members`, `java.module`), fails the
call with `INVALID_KIND` (§5), whose `valid_kinds` and `valid_aliases` list the
accepted values. `["external.type"]` lists the library types a scope reaches.

**`limit`** and **`cursor`** page through the result (§3.5).

---

## 3. Behaviour

### 3.1 Scope expansion

The tool first collects the **source types**: the input node itself when it is a type
or an external type, plus every type and external type in its subtree. Nested types sit beside their enclosing type in the package (see
[Nodes and the containment tree](hierograph-mcp-tools.md#31-nodes-and-the-containment-tree)),
but they are compiled from the enclosing type's source, so a type's scope includes
them: the scope of `Invoice` contains `Invoice$Line`, `Invoice$Line$Tax` and the
anonymous and local classes `Invoice$1`, `Invoice$1Draft`, all the types of the
package named `Invoice$…`. So `affected_by(Invoice)` never reports `Invoice$Line`; a type that uses
`Invoice$Line` is affected by `Invoice` directly.

Source types are never reported. A dependency between two source types is internal to
the scope and does not count as impact.

### 3.2 Traversal

The tool runs a breadth-first search over type-level dependencies (see
[Two levels of dependency](hierograph-mcp-tools.md#33-two-levels-of-dependency)):

1. **Seeding.** For every source type, in pre-order of the containment tree, each
   direct neighbour in the chosen direction that is not a source type becomes an
   affected type at **distance** `1`.
2. **Expansion.** Each affected type whose distance is below `maxDepth` is expanded in
   turn. Each neighbour that is neither a source type nor already affected becomes
   affected at the expanding type's distance plus one.

External types are traversed like any other type. In the `"outgoing"` direction they
are typical leaves (`Money` → `java.math.BigDecimal`); in the `"incoming"` direction an
external type as input finds every scanned type that uses it.

Because the search is breadth-first, each type is reached first along a shortest path,
and its distance is the smallest number of hops from any source type.

### 3.3 `via` and `source_count`

**`via`** is the path along which the search first reached the type: one shortest
path, not all of them. Its steps are real dependency edges, `{from, to, weight}`, with
`from` the depending type. They are listed starting at the source type:

- For `"outgoing"`, the steps read in dependency direction: source → … → affected type.
- For `"incoming"`, each step still points from dependent to dependee, so the list
  walks against the arrows: the first step ends at the source type, the last step
  starts at the affected type.

Keeping the real edge direction lets a caller pass any step to the dependency tools
without reversing it.

**`source_count`** is the number of source types that reach the type within
`maxDepth`, along paths that stay outside the source subtree, the same paths
`distance` is measured on. It counts source types, not paths or edges: a type reached
from one source type along several paths counts that source once. For a single-type
input, `source_count` is always `1`. The count tells how widely an affected type is
coupled to the scope: a type with `source_count` equal to the scope's type count is
reached from every type in it, while a type with `1` hangs on a single one.

The tool finds the affected types with one breadth-first search from all source types
at once, which yields `distance` and `via` but cannot tell which sources reach a type.
For `source_count` it then walks from each source type separately, bounded by
`maxDepth` and restricted to the types already found. The cost grows with the number
of source types times the size of the result.

### 3.4 Filtering and ordering

The kind filter is applied after the search. The remaining types are sorted by
distance (ascending), then qualified name, then id. Nearest types come first, and the
order is total, which pagination relies on.

`by_distance` and `by_parent_module` are computed over the filtered result, before
pagination, so they describe the full result on every page.

**`by_parent_module`** attributes each type to its module: the ancestor that is a
direct child of the root. External types count towards `External Types`. The list holds
the ten modules with the most affected types, largest first; modules with equal counts
are ordered by name, then id.

### 3.5 Pages and limits

The tool is paginated as described in [Pagination](hierograph-mcp-tools.md#7-pagination):

| Default `limit` | Cap | Bytes per item | Full page |
|-----------------|-----|----------------|------------------------|
| 100 | 111 | 450 | 49,950 bytes |

The cap keeps a full page within the 50,000-byte
[response budget](hierograph-mcp-tools.md#73-the-response-budget). Each item carries a
`via` path, which is why the per-item estimate, and so the cap, differs from the
one-hop dependency tools.

The cursor's query hash covers `nodeId`, `direction`, `maxDepth` and the sorted
`kindFilter`, so `limit` may change between pages.

### 3.6 Worked example

The examples in this document use the following graph.

| Node | id | Kind | In |
|------|----|------|----|
| `billing-core` | `4690` | `java.module` | root |
| `com.example.billing` | `4702` | `java.package` | `billing-core` |
| `Invoice` | `4711` | `java.class` | `com.example.billing` |
| `Invoice$Line` | `4712` | `java.class` | `com.example.billing` |
| `Ledger` | `4713` | `java.interface` | `com.example.billing` |
| `com.example.money` | `4720` | `java.package` | `billing-core` |
| `Money` | `4730` | `java.record` | `com.example.money` |
| `billing-core (test)` | `4680` | `java.module` | root |
| `com.example.billing` | `4681` | `java.package` | `billing-core (test)` |
| `InvoiceTest` | `4682` | `java.class` | `com.example.billing` (`4681`) |
| `External Types` | `4800` | `java.module` | root |
| `java.math` | `4802` | `java.package` | `External Types` |
| `BigDecimal` | `4803` | `external.type` | `java.math` |
| `org.slf4j` | `4805` | `java.package` | `External Types` |
| `Logger` | `4806` | `external.type` | `org.slf4j` |

The type-level dependencies; each arrow points from the depending type to the type it
depends on, and the number is the weight:

```
InvoiceTest ─6─▶ Invoice ──────────3──────────▶ Money ─5─▶ BigDecimal
                  │ │ │                          ▲ ▲
                  │ │ └─4─▶ Invoice$Line ───2────┘ │
                  │ └───2─▶ Ledger ─────────1──────┘
                  └─────1─▶ Logger
```

**Incoming from `Money`.** `affected_by(nodeId = 4730)` seeds with the three types that
depend on `Money` directly: `Invoice`, `Invoice$Line` and `Ledger`, each at distance
`1`. Expanding them, `Invoice$Line` and `Ledger` are depended on only by `Invoice`,
which is already affected; `Invoice` is depended on by `InvoiceTest`, which becomes
affected at distance `2`.

| Type | `distance` | `source_count` | `via` |
|------|------------|----------------|-------|
| `Invoice` | 1 | 1 | `4711→4730 (3)` |
| `Invoice$Line` | 1 | 1 | `4712→4730 (2)` |
| `Ledger` | 1 | 1 | `4713→4730 (1)` |
| `InvoiceTest` | 2 | 1 | `4711→4730 (3)`, `4682→4711 (6)` |

`by_distance` is `{"1": 3, "2": 1}` and `by_parent_module` lists `billing-core` with 3
and `billing-core (test)` with 1. With `maxDepth = 1` only the first three remain. With
`kindFilter = ["java.interface"]` only `Ledger` remains, at distance `1`.

**Outgoing from `com.example.billing`.** `affected_by(nodeId = 4702, direction =
"outgoing")` has the source types `Invoice`, `Invoice$Line` and `Ledger`. The edges
`Invoice → Ledger` and `Invoice → Invoice$Line` are internal and ignored. Seeding
reaches `Money` three times (from `Invoice`, `Invoice$Line` and `Ledger`), so its
`source_count` is `3`, and `Logger` once. Expanding `Money` reaches `BigDecimal` at
distance `2`. All three source types reach `BigDecimal` through `Money`, so its
`source_count` is `3` as well (§3.3). With `maxDepth = 1`, `BigDecimal` is not
affected at all. The response is shown in §4.1.

---

## 4. Response

### 4.1 Example

The response to `affected_by(nodeId = 4702, direction = "outgoing")` on the graph of
§3.6:

```json
{
  "source": {
    "id": 4702, "name": "billing", "qualified_name": "com.example.billing",
    "kind": "java.package", "parent_id": 4701, "parent_kind": "java.package",
    "child_count": 3, "descendant_type_count": 3, "direct_type_count": 3
  },
  "direction": "outgoing",
  "results": [
    {
      "node": {
        "id": 4730, "name": "Money", "qualified_name": "com.example.money.Money",
        "kind": "java.record", "parent_id": 4720, "parent_kind": "java.package",
        "modifiers": ["public", "final"], "is_abstract": false,
        "member_count": 6, "method_count": 4, "field_count": 2
      },
      "distance": 1,
      "source_count": 3,
      "via": [ { "from": 4711, "to": 4730, "weight": 3 } ]
    },
    {
      "node": {
        "id": 4806, "name": "Logger", "qualified_name": "org.slf4j.Logger",
        "kind": "external.type", "parent_id": 4805, "parent_kind": "java.package"
      },
      "distance": 1,
      "source_count": 1,
      "via": [ { "from": 4711, "to": 4806, "weight": 1 } ]
    },
    {
      "node": {
        "id": 4803, "name": "BigDecimal", "qualified_name": "java.math.BigDecimal",
        "kind": "external.type", "parent_id": 4802, "parent_kind": "java.package"
      },
      "distance": 2,
      "source_count": 3,
      "via": [
        { "from": 4711, "to": 4730, "weight": 3 },
        { "from": 4730, "to": 4803, "weight": 5 }
      ]
    }
  ],
  "summary": {
    "total": 3,
    "returned": 3,
    "truncated": false,
    "by_distance": { "1": 2, "2": 1 },
    "by_parent_module": [
      { "id": 4800, "name": "External Types", "count": 2 },
      { "id": 4690, "name": "billing-core", "count": 1 }
    ]
  }
}
```

`Money` is reached first from `Invoice`, the first source type in pre-order, so its
`via` starts there.

### 4.2 Top-level fields

| Field | Type | Meaning | Why |
|-------|------|---------|-----|
| `source` | enriched NodeRef | the input node | confirms which scope was expanded and how large it is |
| `direction` | string | the direction applied | echoes the default when `direction` was omitted |
| `results` | array | this page of affected types | the closure itself |
| `summary` | object | counts over the full result | answers shape questions without paging |
| `next_cursor` | string | token for the next page; absent on the last page | resumes the walk through the result |

NodeRef forms are defined in [Node references](hierograph-mcp-tools.md#6-node-references).
`source` is enriched because a caller checking a package input wants to see how many
types it expanded to.

### 4.3 Result fields

| Field | Type | Meaning | Why |
|-------|------|---------|-----|
| `node` | enriched NodeRef | the affected type | identifies it and shows its size and modifiers; external types carry the minimal fields only |
| `distance` | integer | smallest number of hops from any source type | ranks impact by proximity |
| `source_count` | integer | the number of source types that reach it within `maxDepth` | shows how widely the affected type is coupled to the scope |
| `via` | array of `{from, to, weight}` | one shortest path from a source type, listed source-side first, each step a real edge | shows *how* the type is reached, ready to inspect with the dependency tools |

### 4.4 Summary fields

| Field | Type | Meaning | Why |
|-------|------|---------|-----|
| `total` | integer | number of affected types after filtering | the size of the blast radius |
| `returned` | integer | items on this page | see [Pages and summaries](hierograph-mcp-tools.md#71-pages-and-summaries) |
| `truncated` | boolean | `total > returned` | same |
| `by_distance` | object | distance (as a string key) → count, ascending | shows whether impact is local or spreads far |
| `by_parent_module` | array of `{id, name, count}` | the ten modules with the most affected types | shows which parts of the codebase are hit |

An empty result has `total` `0`, `results` `[]`, `by_distance` `{}` and
`by_parent_module` `[]`. It is an answer, not an error: nothing depends on the input,
or the input depends on nothing outside itself.

---

## 5. Errors

Errors use the nested shape of [Error shapes](hierograph-mcp-tools.md#81-error-shapes).
The checks run in the order of the table; the first failure is reported.

| Code | Situation | Extra fields | `recovery` |
|------|-----------|--------------|------------|
| `NODE_NOT_FOUND` | `nodeId` names no node | — | `Use find_node to look up the correct node ID.` |
| `INVALID_NODE_KIND` | `nodeId` names a method or field | `actual_kind`, `declaring_type` (minimal NodeRef) | `To query dependencies involving this <kind>'s declaring type, pass nodeId=<id>.` |
| `INVALID_PARAMETER` | `direction` is neither `"incoming"` nor `"outgoing"` | — | `Use direction='incoming' for blast radius or direction='outgoing' for transitive dependencies.` |
| `INVALID_PARAMETER` | `maxDepth` is below `1` | — | `Use maxDepth=1 for direct neighbours only, or omit maxDepth.` |
| `INVALID_KIND` | `kindFilter` contains a value that is not a type kind, `external.type` or `types` | `invalid_values`, `valid_kinds`, `valid_aliases` | `Use values from valid_kinds or valid_aliases, or omit kindFilter.` |
| cursor codes | the cursor is malformed or stale | per code | see [Cursors](hierograph-mcp-tools.md#72-cursors) |

For the method `Money total()` (id `4715`) of `Invoice`:

```json
{ "error": {
    "code": "INVALID_NODE_KIND",
    "message": "This tool operates on type-level dependencies. The node is a java.method, not a type.",
    "actual_kind": "java.method",
    "declaring_type": { "id": 4711, "name": "Invoice", "qualified_name": "com.example.billing.Invoice",
                        "kind": "java.class", "parent_id": 4702, "parent_kind": "java.package" },
    "recovery": "To query dependencies involving this java.method's declaring type, pass nodeId=4711." } }
```

An unknown `kindFilter` entry is not an error (§2). While no graph is loaded, the tool
returns the response of [No graph loaded](hierograph-mcp-tools.md#83-no-graph-loaded).

---

## 6. Working with the tool

**Size the impact first.** Ask for one item and read the summary:

```json
{ "nodeId": 4730, "limit": 1 }
```

`total`, `by_distance` and `by_parent_module` describe the whole blast radius. Fetch
items only if the individual types matter.

**Keep it close.** On a central type, ask for the near ring only:

```json
{ "nodeId": 4730, "maxDepth": 2 }
```

**Find what a scope needs before extracting it.** The outgoing closure of a package,
filtered to scanned types, lists what would have to move with it or become a
dependency of the module it moves to:

```json
{ "nodeId": 4702, "direction": "outgoing", "kindFilter": ["types"] }
```

`types` excludes external types, so the result shows only scanned code. Without the
filter, `by_parent_module` also shows how much of the closure is library code.

**Explain one result.** `via` shows one path. For the strongest chains to a specific
type, call `find_dependency_path` with the source and that type; for the weights and
flags of one hop, call `outgoing_dependencies` on a step's `from`.

**Page.** When `next_cursor` is present, pass it back with the same `nodeId`,
`direction`, `maxDepth` and `kindFilter`. Prefer a smaller `maxDepth` or a
`kindFilter` to paging through a large closure.

---

## 7. Guarantees

1. **Inputs are never affected.** No type in the input scope appears in `results`.
2. **Each type once.** Every affected type appears exactly once across all pages.
3. **Shortest distance.** `distance` is the smallest number of hops from any source
   type, within `maxDepth`.
4. **Real edges.** Every `via` step is a type-level dependency of the graph, with
   `from` depending on `to`, and consecutive steps share a type.
5. **Filter does not change distances.** `kindFilter` removes types from the result;
   it never changes the walk, the distances or the paths of the remaining types.
6. **Deterministic order.** For the same graph and parameters, `results` has the same
   order: distance, then qualified name, then id.
7. **Full-result summary.** `total`, `by_distance` and `by_parent_module` describe the
   whole filtered result on every page.
