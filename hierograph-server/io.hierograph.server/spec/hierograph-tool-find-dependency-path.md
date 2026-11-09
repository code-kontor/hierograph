# Hierograph Tool `find_dependency_path` — Specification

`find_dependency_path` is Hierograph's **dependency-chain finder**: given a source and
a target (each a module, package or type), it returns the shortest chains of
type-level dependencies that lead from the source to the target, step by step and with
weights. It answers "why does this depend on that?" when the answer is not a direct
edge.

This document explains why the tool exists, its parameters, how it searches, what it
returns, and how to work with it. Concepts shared by all tools (kinds, node
references, errors) are defined in the
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

An aggregated dependency says *that* the package `com.example.billing` depends on the
module `External Types`, and how strongly. It does not say *through what*. When the
dependency is indirect, `Invoice` uses `Money` and `Money` uses
`java.math.BigDecimal`, no single edge list shows it. `find_dependency_path` searches
from one end to the other and returns the chains themselves.

**Explanations, not enumerations.** The tool returns a few short paths, at most 20,
shortest first. A model that needs to explain a dependency, or decide where to cut it,
needs the most direct chains, not every route through the graph.

**Absence is an answer.** When no chain exists, the tool says so with `exists: false`.
"Does `Money` depend on `Invoice` in any way?" has a definite answer, and the tool
gives it without an error.

**Scopes at both ends.** Source and target can be modules, packages or types. Each is
expanded to the types it contains, and every returned path names its concrete
endpoints.

### `find_dependency_path` and its neighbours

| Tool | Starts from | Returns | Use it for |
|------|-------------|---------|------------|
| `outgoing_dependencies` / `incoming_dependencies` | one scope | direct type-level edges with weights and flags | how a scope is coupled one hop away |
| `affected_by` | one scope | every type reachable, with distance and one path each | how far a change can ripple |
| `find_dependency_path` | two scopes | the shortest chains between them | why one scope depends on the other |

The tools complement each other:

1. The direct-dependency tools cannot show an indirect connection without one call
   per hop.
2. `affected_by` would find the target among everything reachable, but returns one
   path per type and buries the answer among unrelated types.
3. `find_dependency_path` searches toward a known target and returns several
   alternative chains, which is what a refactoring decision needs.

---

## 2. Parameters

Parameter names are camelCase, as for all tools (see
[Parameters](hierograph-mcp-tools.md#5-parameters)). The tool is not paginated.

| Parameter | Type | Required | Default | Meaning |
|-----------|------|----------|---------|---------|
| `fromId` | integer | yes | — | the source scope: module, package or type |
| `toId` | integer | yes | — | the target scope: module, package or type |
| `maxPaths` | integer | no | `5` | the number of paths to find, clamped to 1–20 |
| `maxLength` | integer | no | unbounded | the longest path to consider, in hops |

**`fromId`** and **`toId`** accept any node whose dependencies are type-level: a
module, a package, a type or an external type. Methods and fields are rejected (§5),
because the search runs on type-level dependencies. Paths always run *from* the
source *to* the target, following dependencies in their direction; to test the
opposite direction, swap the two.

**`maxPaths`** bounds the answer. A few alternative chains show whether a dependency
hangs on one type or is spread out; twenty is the most a model can usefully compare.
Values outside 1–20 are clamped.

**`maxLength`** answers "are these within *n* hops of each other?" and stops the
search from wandering through long chains nobody would act on. A `maxLength` below `1`
is rejected with `INVALID_PARAMETER` (§5): no path is shorter than one dependency.

---

## 3. Behaviour

### 3.1 Scope expansion

The tool expands each scope to its **source types** and **target types**: the node
itself when it is a type or an external type, plus every type and external type in
its subtree. Nested types sit beside their enclosing type in the package (see
[Nodes and the containment tree](hierograph-mcp-tools.md#31-nodes-and-the-containment-tree)),
but they are compiled from the enclosing type's source, so a type's scope includes
them: the scope of `Invoice` contains `Invoice$Line`, `Invoice$Line$Tax` and the
anonymous and local classes `Invoice$1`, `Invoice$1Draft`, all the types of the
package named `Invoice$…`. A path into `Invoice` therefore also ends at a use of
`Invoice$Line`.

The tool returns no path, without searching, when either scope contains no types.
Overlapping scopes need no special case: a path ends at a target type it reaches over
at least one dependency, and no type depends on itself. A scope searched against
itself therefore yields the dependencies between its types and the cycles through them
(`A → B → A`), and nothing when it has neither (§3.4).

### 3.2 Search

The tool runs a breadth-first search along outgoing type-level dependencies, one
layer of hops at a time, starting from all source types at once.

1. **Layer by layer.** Layer *k* holds the types first reached in *k* hops. Each
   type in it carries its heaviest partial paths from a source type.
2. **Target reached.** When a dependency leads to a target type, the extended paths
   are candidates of length *k* + 1. The search does not continue past a target type.
3. **Not yet reached.** When a dependency leads to a type that no earlier layer has
   reached, the type joins the next layer, and its partial paths are merged from all
   the types of this layer that lead to it.
4. **Already reached.** A type reached in an earlier layer, and every source type
   that is not a target type, is not entered again. Paths therefore never pass
   through another source type, and a type appears in paths only at the hop at which
   the search first reached it.

After each layer, the candidates of that length are ranked by total weight, heaviest
first, and as many are kept as paths are still wanted. The search goes on into longer
layers until `maxPaths` paths are kept, no type is left to extend, or the paths have
reached `maxLength` hops.

A type keeps only as many partial paths as paths are still wanted. That loses no
result: when two partial paths reach the same type, the heavier one followed by any
continuation outweighs the lighter one with the same continuation, so a returned path
never needs a prefix that was dropped. Together with rule 4, this keeps the work
proportional to the number of dependencies times `maxPaths`, however many routes the
graph holds.
The result is not every simple path: a longer route through a type that a shorter path
already passed is not returned.

### 3.3 Ordering

The paths are sorted by length (ascending), then total weight (descending). Because
the search ranks each length's candidates by weight before it keeps any, the paths
returned for a length are the heaviest of that length, also when `maxPaths` cuts the
result off inside it. Paths of equal length and weight keep the order in which the
search met them: source types in pre-order of the containment tree, then each type's
dependencies in the order the graph holds them.

### 3.4 Worked example

The examples use this graph; each arrow points from the depending type, and the number
is the weight.

```
Invoice ──────────3──────────▶ Money ─5─▶ BigDecimal
 │ │ │                          ▲ ▲
 │ │ └─4─▶ Invoice$Line ───2────┘ │
 │ └───2─▶ Ledger ─────────1──────┘
 └─────1─▶ Logger
```

| Node | id | Kind | In |
|------|----|------|----|
| `com.example.billing` | `4702` | `java.package` | `billing-core` |
| `Invoice` | `4711` | `java.class` | `com.example.billing` |
| `Invoice$Line` | `4712` | `java.class` | `com.example.billing` |
| `Ledger` | `4713` | `java.interface` | `com.example.billing` |
| `Money` | `4730` | `java.record` | `com.example.money` |
| `External Types` | `4800` | `java.module` | root |
| `BigDecimal` | `4803` | `external.type` | `java.math` |
| `Logger` | `4806` | `external.type` | `org.slf4j` |

**From a package to a module.** `find_dependency_path(fromId = 4702, toId = 4800)` has
the source types `Invoice`, `Invoice$Line` and `Ledger`, and the target types
`BigDecimal` and `Logger`.

- Layer 1: `Invoice → Logger` reaches a target and is recorded. `Invoice → Ledger` and
  `Invoice → Invoice$Line` lead to source types and are dropped. `Money` is reached
  three times, from `Invoice`, `Invoice$Line` and `Ledger`.
- Layer 2: each of the three paths to `Money` extends to `BigDecimal` and is recorded.
- Layer 3 is empty, and the search ends with four paths.

| Path | `length` | `total_weight` |
|------|----------|----------------|
| `Invoice → Logger` | 1 | 1 |
| `Invoice → Money → BigDecimal` | 2 | 3 + 5 = 8 |
| `Invoice$Line → Money → BigDecimal` | 2 | 2 + 5 = 7 |
| `Ledger → Money → BigDecimal` | 2 | 1 + 5 = 6 |

The one-hop path comes first although it is the lightest, because length sorts before
weight. With `maxPaths = 2` the tool returns the first two rows: the one-hop path,
then the heaviest of the three two-hop paths. With `maxLength = 1` it returns only the first row.

**Only first arrivals.** `find_dependency_path(fromId = 4711, toId = 4803)` returns one
path, `Invoice → Money → BigDecimal`. The routes through `Invoice$Line` and `Ledger`
reach `Money` in layer 2, after `Invoice` reached it directly in layer 1, so they are
not extended (rule 4).

**Within one package.** `find_dependency_path(fromId = 4702, toId = 4702)` makes every
type both source and target. It returns the two internal edges, `Invoice →
Invoice$Line` (weight 4) and `Invoice → Ledger` (weight 2), each a path of length `1`.
`find_dependency_path(fromId = 4711, toId = 4711)` returns `Invoice → Invoice$Line`:
the scope of `Invoice` includes its nested type (§3.1).

---

## 4. Response

### 4.1 Example

The response to `find_dependency_path(fromId = 4702, toId = 4800)` on the graph of
§3.4:

```json
{
  "nodes": {
    "4702": { "name": "billing",        "qualified_name": "com.example.billing",               "kind": "java.package" },
    "4800": { "name": "External Types", "qualified_name": "External Types",                    "kind": "java.module" },
    "4711": { "name": "Invoice",        "qualified_name": "com.example.billing.Invoice",       "kind": "java.class" },
    "4806": { "name": "Logger",         "qualified_name": "org.slf4j.Logger",                  "kind": "external.type" },
    "4730": { "name": "Money",          "qualified_name": "com.example.money.Money",           "kind": "java.record" },
    "4803": { "name": "BigDecimal",     "qualified_name": "java.math.BigDecimal",              "kind": "external.type" },
    "4712": { "name": "Invoice$Line",   "qualified_name": "com.example.billing.Invoice$Line",  "kind": "java.class" },
    "4713": { "name": "Ledger",         "qualified_name": "com.example.billing.Ledger",        "kind": "java.interface" }
  },
  "paths": [
    { "from": 4711, "to": 4806, "length": 1, "total_weight": 1,
      "steps": [ { "from": 4711, "to": 4806, "weight": 1 } ] },
    { "from": 4711, "to": 4803, "length": 2, "total_weight": 8,
      "steps": [ { "from": 4711, "to": 4730, "weight": 3 }, { "from": 4730, "to": 4803, "weight": 5 } ] },
    { "from": 4712, "to": 4803, "length": 2, "total_weight": 7,
      "steps": [ { "from": 4712, "to": 4730, "weight": 2 }, { "from": 4730, "to": 4803, "weight": 5 } ] },
    { "from": 4713, "to": 4803, "length": 2, "total_weight": 6,
      "steps": [ { "from": 4713, "to": 4730, "weight": 1 }, { "from": 4730, "to": 4803, "weight": 5 } ] }
  ],
  "summary": {
    "from_scope": 4702,
    "to_scope": 4800,
    "path_count": 4,
    "shortest_length": 1,
    "exists": true
  }
}
```

### 4.2 Top-level fields

| Field | Type | Meaning | Why |
|-------|------|---------|-----|
| `nodes` | slim node map | every node the response names, keyed by id | paths share types; each is described once |
| `paths` | array | the paths found, sorted (§3.3) | the chains themselves |
| `summary` | object | the outcome in a few scalars | answers "is there a chain, and how short" without reading paths |

**`nodes`** follows [Slim node map](hierograph-mcp-tools.md#63-slim-node-map). It lists
the source scope first, then the target scope, then the types of each path in path
order. The scopes are listed even when no path is found, so a caller can confirm what
it asked about.

### 4.3 Path fields

| Field | Type | Meaning | Why |
|-------|------|---------|-----|
| `from` | integer | the source type the path starts at | the endpoint, without reading the steps |
| `to` | integer | the target type the path ends at | same |
| `length` | integer | number of steps | the primary sort key; shorter chains are more direct |
| `total_weight` | integer | sum of the step weights | ranks equal-length chains by coupling strength |
| `steps` | array of `{from, to, weight}` | the dependencies in path order | shows each hop, ready to inspect with the dependency tools |

Each step is a type-level dependency: `from` depends on `to`, with the weight of that
dependency. Steps follow the dependency direction, so the first step's `from` is a
source type, each step's `to` is the next step's `from`, and the last step's `to` is a
target type.

### 4.4 Summary fields

| Field | Type | Meaning | Why |
|-------|------|---------|-----|
| `from_scope` | integer | the `fromId` passed in | ties the answer to the question |
| `to_scope` | integer | the `toId` passed in | same |
| `path_count` | integer | number of entries in `paths` | at most `maxPaths` |
| `shortest_length` | integer or `null` | length of the first path; `null` when there is none | "how directly are they connected?" in one field |
| `exists` | boolean | `true` when at least one path was found | the yes/no answer |

`from_scope` and `to_scope` echo the input ids, which may be packages or modules; the
types a path actually connects are its own `from` and `to`. The names match the
detail level of the dependency tools, where `from_scope` and `to_scope` echo the
scopes the same way.

When no path is found, `paths` is `[]`, `path_count` is `0`, `shortest_length` is
`null` and `exists` is `false`. Within `maxLength`, this is definitive: no dependency
chain leads from any source type to any target type.

---

## 5. Errors

Errors use the nested shape of [Error shapes](hierograph-mcp-tools.md#81-error-shapes).
The tool checks `fromId` (that it exists, then its kind), then `toId` the same way, then
`maxLength`, and reports the first failure.

| Code | Situation | Extra fields | `recovery` |
|------|-----------|--------------|------------|
| `NODE_NOT_FOUND` | `fromId` or `toId` names no node | — | `Use find_node to look up the correct node ID.` |
| `INVALID_NODE_KIND` | `fromId` or `toId` names a method or field | `actual_kind`, `declaring_type` (minimal NodeRef) | `Pass the declaring type's id (<id>) instead.` |
| `INVALID_PARAMETER` | `maxLength` is below `1` | — | `Use maxLength=1 for direct dependencies only, or omit maxLength.` |

For the method `Money total()` (id `4715`) of `Invoice` passed as `fromId`:

```json
{ "error": {
    "code": "INVALID_NODE_KIND",
    "message": "This tool operates on type-level dependencies. The node is a java.method, not a type.",
    "actual_kind": "java.method",
    "declaring_type": { "id": 4711, "name": "Invoice", "qualified_name": "com.example.billing.Invoice",
                        "kind": "java.class", "parent_id": 4702, "parent_kind": "java.package" },
    "recovery": "Pass the declaring type's id (4711) instead." } }
```

The `message` of `NODE_NOT_FOUND` names the missing id, which tells the caller which
of the two was wrong. A missing path is not an error (§4.4). While no graph is loaded,
the tool returns the response of
[No graph loaded](hierograph-mcp-tools.md#83-no-graph-loaded).

---

## 6. Working with the tool

**Check whether a dependency exists at all.** Ask for one path:

```json
{ "fromId": 4730, "toId": 4711, "maxPaths": 1 }
```

`exists: false` means `Money` does not depend on `Invoice`, directly or indirectly.

**Explain a module dependency.** Pass two modules or packages and read the endpoint
types of each path. Several short paths that share one middle type (here `Money`)
show where a single change would cut the dependency.

**Look for cycles.** Call the tool twice with the scopes swapped. If both directions
return paths, the scopes depend on each other, and the paths show where.

**Keep it local.** With `maxLength = 2` the tool answers "are these within two hops?"
and ignores longer chains.

**Drill into a step.** Each step is a type-level dependency. To see the member
references behind it, call `outgoing_dependencies` with the step's `from` and `to`
and `detailLevel = "detail"`.

**See everything reachable instead.** When the target is unknown, use `affected_by`
with `direction = "outgoing"` from the source.

---

## 7. Guarantees

1. **Real edges.** Every step is a type-level dependency of the graph, with `from`
   depending on `to`, and consecutive steps share a type.
2. **Correct endpoints.** Every path starts at a source type and ends at a target
   type, and no type between its first and its last is a target type.
3. **No detour through the source.** No path passes through a source type other than
   its first, unless that type is also a target type and ends the path.
4. **Bounded.** `paths` has at most `maxPaths` entries (at most 20), each of at most
   `maxLength` steps.
5. **Shortest first.** The first path has the smallest length of any path from source
   to target; `shortest_length` is that length.
6. **Heaviest within a length.** Among the paths the search considers (§3.2), no
   unreturned path of a returned length is heavier than a returned one of that length.
7. **Definite absence.** `exists: false` means no dependency chain of at most
   `maxLength` steps leads from any source type to any target type.
8. **Deterministic.** For the same graph and parameters, the tool returns the same
   paths in the same order.
