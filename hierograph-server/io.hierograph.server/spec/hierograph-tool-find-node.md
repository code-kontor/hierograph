# Hierograph Tool `find_node` — Specification

`find_node` is Hierograph's **name lookup**: it turns a name or a fragment of one into
node ids. Almost every other tool takes a node id, so whenever a user names a class,
package or method, `find_node` is the call that connects the name to the graph.

This document explains why the tool exists, how it matches and ranks, what it returns,
and how to use it. Concepts shared by all tools (node kinds, kind filters, node
references, errors) are defined in the [MCP tools specification](hierograph-mcp-tools.md).

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

Users speak in names ("the `Invoice` class", "the money package"); the tools speak in
ids. `find_node` searches the whole graph for a name and returns the best candidates
as enriched node references (see
[§6.2 of the MCP tools specification](hierograph-mcp-tools.md#62-enriched-noderef)),
each with its id, its kind and its parent, so the caller can tell the record `Money` from
the package `money` before it takes an id.

**Forgiving input.** The match is a case-insensitive substring match on both the name
and the qualified name. `invoice`, `Invoice`, `billing.Inv` and
`com.example.billing.Invoice` all find the class.

**Best match first.** Results are ranked by how well they match, so the exact hit for a
short name is not buried under the many nodes that merely contain it.

**Structure by default.** Without a kind filter the search skips methods and fields. A
search for `total` should find the type `Total`, not every `total()` method in the
codebase; members are searched only when the caller asks for them.

**`find_node` and `list_descendants`.** Both can find nodes whose name contains a
fragment. They differ in scope and purpose:

| | `find_node` | `list_descendants` with `namePattern` |
|---|---|---|
| Scope | the whole graph | one subtree |
| Matches against | name and qualified name | name only |
| Ordering | by match quality | containment-tree order |
| Methods and fields without a filter | skipped | included |
| Result size | at most 50, no paging | paged, complete |

1. `find_node` answers "which node is this?" when the caller does not know where it is.
2. `list_descendants` answers "what is in here?" when the caller knows the subtree and
   needs every match.

---

## 2. Parameters

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `name` | string | yes | — | the name or fragment to search for |
| `kindFilter` | string[] | no | none | kinds and group aliases to restrict the search to |

**`name`** is matched case-insensitively as a substring of each node's name and
qualified name. Matching both lets a caller type a simple name (`Money`) or a dotted
path fragment (`billing.Invoice`), and the second disambiguates when a simple name
occurs in several packages. A member's name is its signature, such as
`com.example.money.Money total()`, so `total` finds the method once members are
searched. An empty `name` matches every searched node.

**`kindFilter`** accepts the kind values and group aliases of
[§3.2 of the MCP tools specification](hierograph-mcp-tools.md#32-kinds). Aliases expand,
and mixing is allowed: `["java.interface", "packages"]`. It exists because names are
ambiguous across kinds; `["types"]` keeps a search for `money` from returning the
package. Omitting it, or passing `[]`, searches modules, packages, types and external
types but not methods or fields. Naming `java.method`, `java.field` or `members` is
the only way to search members.

---

## 3. Behaviour

### 3.1 What is searched

The search runs over the node search index of the loaded graph, which the
[hierarchical graph service](../../io.hierograph.server.graphservice/spec/hierarchical-graph-service.md)
builds once per loaded graph. The index holds every node except the synthetic root:
modules, packages, types, members and external types. A module matches by its artifact
id (`billing-core`) and by its Maven coordinate (`com.example:billing-core:1.4.0`);
`kindFilter = ["java.module"]` restricts a search to modules.

A node is a candidate when its kind passes the filter and the lower-cased `name`
occurs in its lower-cased name or qualified name.

### 3.2 Ranking

Each candidate gets a rank:

| Rank | Condition |
|------|-----------|
| 0 | the name equals `name` |
| 1 | the qualified name equals `name` |
| 2 | the name starts with `name` |
| 3 | `name` occurs anywhere else in the name or qualified name |

All comparisons ignore case. Candidates are ordered by rank, then by the length of the
qualified name, shortest first. Candidates that tie on both keep the order of the index,
which is the containment tree in pre-order. The first 50 are returned.

**Why the shorter qualified name wins.** Among equally good matches, the shorter path is
usually the one the user means: the package `com.example.money` before the type
`com.example.money.Money`, a top-level type before a nested one.

**Example.** A search for `money` without a filter:

| Candidate | Name | Qualified name | Rank | Length |
|-----------|------|----------------|------|--------|
| package `money` | `money` | `com.example.money` | 0 | 17 |
| record `Money` | `Money` | `com.example.money.Money` | 0 | 23 |

Both names equal `money` once case is ignored, so both have rank 0, and the package
comes first because its qualified name is shorter. The method
`com.example.money.Money total()` of `Invoice` also contains `money`, but it is a member
and the search skips it.
With `kindFilter = ["types"]` only the record remains.

---

## 4. Response

```json
{
  "results": [
    { "id": 4720, "name": "money", "qualified_name": "com.example.money",
      "kind": "java.package", "parent_id": 4701, "parent_kind": "java.package",
      "child_count": 1, "descendant_type_count": 1, "direct_type_count": 1 },
    { "id": 4730, "name": "Money", "qualified_name": "com.example.money.Money",
      "kind": "java.record", "parent_id": 4720, "parent_kind": "java.package",
      "modifiers": ["public", "final"], "is_abstract": false,
      "member_count": 4, "method_count": 3, "field_count": 1 }
  ],
  "summary": { "total": 2, "returned": 2, "truncated": false }
}
```

| Field | Content | Why |
|-------|---------|-----|
| `results` | the ranked matches as enriched node references | ids to pass on, plus the kind, parent and size needed to pick the right one |
| `summary.total` | the number of all matches, before the cap | how ambiguous the name is |
| `summary.returned` | the number of results returned, at most 50 | the size of this answer |
| `summary.truncated` | `true` when `total > returned` | tells the caller that matches were left out and the name is too broad |

Methods and fields carry their `modifiers` and no counts; external types carry the
minimal fields only ([§6.2](hierograph-mcp-tools.md#62-enriched-noderef)).

**The cap is visible.** `summary.total` counts every match, so a response that was cut
says so: `total` 312, `returned` 50, `truncated` `true`. The 50 returned are the best
ranked, so an exact name match is never cut. A caller that sees `truncated` narrows
the search with a longer name or a `kindFilter` rather than guessing among the
returned ones.

---

## 5. Errors

| Situation | Code | Message | Recovery |
|-----------|------|---------|----------|
| `kindFilter` contains a value that is neither a kind nor an alias | `INVALID_KIND` | `Unknown kind: 'classes'. Valid kinds: java.module, java.package, …. Group aliases: types, members, packages.` | `Use values from valid_kinds or valid_aliases, or omit kindFilter.` |
| No graph is loaded | — | `{"status": "no_graph_available", …}` ([§8.3](hierograph-mcp-tools.md#83-no-graph-loaded)) | build the project, then call `reload_graph` |

`INVALID_KIND` is the shared kind-filter error of
[§3.2 of the MCP tools specification](hierograph-mcp-tools.md#32-kinds): it carries
`invalid_values`, `valid_kinds` and `valid_aliases` besides the `recovery`.
`external.type` is not a valid filter value. A search for a name that matches nothing
is not an error: it returns empty `results` and a `total` of `0`.

---

## 6. Working with the tool

**Resolve a type the user named.** `find_node(name = "Invoice", kindFilter = ["types"])`.
Take the `id` of the result whose `qualified_name` is the one meant.

**Disambiguate a common name.** Add a package fragment:
`find_node(name = "billing.Invoice")` matches only qualified names that contain it.

**Find a method.** `find_node(name = "total", kindFilter = ["members"])`. The result's
`parent_id` is the declaring type. For overloads, read the signature in `name`.

**Find a module.** `find_node(name = "billing-core", kindFilter = ["java.module"])`. To
see every module at once, use `graph_overview`.

**Too many results.** If `truncated` is `true`, lengthen `name` or add a `kindFilter`;
`total` says how far the search has to narrow.

---

## 7. Guarantees

1. **Deterministic ranking.** For one loaded graph, the same `name` and `kindFilter`
   return the same results in the same order.
2. **Bounded response.** At most 50 results are returned.
3. **No members unless asked.** Without a filter naming `java.method`, `java.field` or
   `members`, no method or field is returned.
4. **No root.** The tool never returns the synthetic root.
5. **Valid ids.** Every returned `id` is accepted by every tool that takes a node of that
   kind.
6. **Read-only.** The tool changes no server state.
