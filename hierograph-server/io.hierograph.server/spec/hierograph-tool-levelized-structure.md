# Hierograph Tool `levelized_structure` — Specification

`levelized_structure` lays the children of one node out in **rows by level**, as a
levelized structure map draws them: a child sits one row above the highest child it
depends on, the children of a cycle (a **tangle**) share a row, and the only
dependencies that point up are the **feedback dependencies** inside the tangles. It
answers "how are the packages of X layered?", "where are the cycles in X?" and "which
dependencies untangle this cycle?" in one call.

Concepts shared by all tools (node kinds, node references, errors, views) are defined in
the [MCP tools specification](hierograph-mcp-tools.md). The levelization itself is
defined in the
[algorithms specification](../../../hierograph-hierarchicalgraph/io.hierograph.hierarchicalgraph.algorithms/spec/hierarchicalgraph-algorithms.md#73-levelization);
GraphQL serves the same result as `NodeSet.levelization`.

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

A dependency matrix (`pairwise_dependencies`) says whether a set is layered; a
levelized layout says *how*: which children form the base, which sit on top, and how
many layers lie between. Position carries the direction, so ordinary dependencies need
no listing at all. Every dependency the tool does list, a feedback dependency, is a
finding: it closes a cycle, and removing the listed ones untangles it.

**One container per call.** The tool levelizes the direct children of one node, as a
map that is expanded one box at a time does. To look inside a child, call it again on
that child. Each call stays small: a container's children, its tangles and the
dependencies that break them.

## 2. Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `nodeId` | integer | yes | The node whose direct children are levelized: a module, a package, a view group, or the root (whose children are the modules). |
| `limit` | integer | no | Max feedback dependencies returned, heaviest first. Default 100, clamped to 1–500. The summary counts all of them. |
| `compact` | boolean | no | Fold chains of lone packages, default `true` ([MCP tools specification, §3.1](hierograph-mcp-tools.md#31-nodes-and-the-containment-tree)): the children levelized are the display children. `false` levelizes the children as they are. |
| `view` | string | no | The view to answer in (shared parameter); a view's groups are containers like any node. |

## 3. Behaviour

1. **Sibling dependencies.** The dependencies between two children are those from
   anything in one child's subtree to anything in the other's, summed by weight.
   Dependencies inside one child's subtree never lift it.
2. **Tangles** are the strongly connected components of two or more children. Their
   members are ordered dependents first, so that the heaviest dependencies point down
   (the DSM's order: weighted Eades–Lin–Smyth, exact for up to 12 members).
3. **Levels.** With every tangle condensed to one node, a child's level is the length
   of the longest chain of dependencies beneath it; level `0` holds the children that
   depend on no other child. A tangle's members share its level.
4. **Feedback dependencies** are the dependencies inside a tangle from a member to an
   earlier one: the cheapest set whose removal untangles the tangle, listed heaviest
   first.
5. **Rows inside a tangle.** Ignoring the feedback dependencies, a member sits above
   the later members it depends on.

**Folding.** With `compact` (the default) the tool levelizes the display children:
each chain of lone packages is represented by its last package. Folding changes no
weight, so the rows, tangles and feedback dependencies are those of the chains, and
they name the chain ends: below `io.acme`, the chain `io.acme.server` ›
`io.acme.server.mcp` is levelized as `io.acme.server.mcp`, named `server.mcp`. In
`nodes`, a chain end carries its folded `name` and `compacted_from`.

External Types is a child of the root like any module; levelizing the root places it
in the lowest row, as everything depends on it.

## 4. Response

```json
{
  "parent": { "id": 7802, "name": "io.hierograph.server", "kind": "java.module", "…": "…" },
  "summary": {
    "children": 4, "levels": 3, "acyclic": false, "tangles": 1,
    "feedback_dependencies": 1, "feedback_weight": 2, "returned": 1, "truncated": false
  },
  "levels": [
    { "level": 0, "node_ids": [9004] },
    { "level": 1, "node_ids": [9002, 9003] },
    { "level": 2, "node_ids": [9001] }
  ],
  "tangles": [
    { "level": 1, "member_ids": [9002, 9003], "rows": [[9003], [9002]], "feedback_weight": 2 }
  ],
  "feedback_dependencies": [
    { "from": 9003, "to": 9002, "weight": 2, "type_dependency_count": 1 }
  ],
  "nodes": {
    "9001": { "name": "app", "qualified_name": "com.example.app", "kind": "java.package" },
    "…": "…"
  }
}
```

| Field | Description |
|-------|-------------|
| `parent` | The levelized node, as a minimal node reference. |
| `summary` | `children`; `levels`, the number of rows; `acyclic`; `tangles`; `feedback_dependencies` and `feedback_weight` over all of them; `returned` and `truncated` for the list below. |
| `levels` | The rows, bottom first. Each lists its children's ids in DSM order; the order within a row carries no other meaning. |
| `tangles` | Per tangle: its `level`, its `member_ids` dependents first, its `rows` inside the tangle bottom first, and the summed weight of its feedback dependencies. |
| `feedback_dependencies` | Heaviest first, at most `limit`: the depending child (`from`), the child it depends on (`to`), the summed `weight`, and how many type-to-type dependencies it is made of. |
| `nodes` | Every child once, by id, as a slim node: `name`, `qualified_name` (when it differs from the name), `kind`, and `virtual` for a view group or `is_test` for a test module. |

## 5. Errors

| Code | When | Recovery |
|------|------|----------|
| `NODE_NOT_FOUND` | No node has the id. | Look the id up with `find_node`. |

A node without children answers with no levels, no tangles and no feedback
dependencies.

## 6. Working with the tool

**How are the packages of a module layered?** `levelized_structure(nodeId = <module>)`.
Read `summary` first: `levels` is the depth of the layering, `acyclic` and `tangles`
say whether it is clean. A container whose children all sit on one row has
independent children, or children that are never used.

**Where are the cycles, and what untangles them?** Each tangle names its members; the
`feedback_dependencies` are the refactoring candidates, heaviest first. For the types
behind one, call `outgoing_dependencies(sourceIds = [from], targetIds = [to])`.

**Down the tree.** Call the tool again on a child that holds a tangle or many levels.
In a view, pass `view` on every call: the groups are the containers.

**Drawing a map.** The rows bottom-up, the tangles boxed, and only the feedback
dependencies as arrows: everything else points down by position. The web UI's
levelized map and the `hierograph-structure` skill use the same result.

## 7. Guarantees

1. **Every child once.** `levels` contains every child of the node exactly once.
2. **Levels point down.** Every dependency between two children goes to a lower row,
   except those between members of one tangle; of those, only the feedback
   dependencies point against the tangle's order.
3. **Same as GraphQL.** For the same node, view and graph, the levels, tangles and
   feedback dependencies equal `node(id) { children { levelization } }`.
4. **Deterministic.** The same node over the same graph gives the same response, in
   the same order.
