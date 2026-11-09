# Hierograph Tool `list_descendants` — Specification

`list_descendants` is Hierograph's **subtree query**: it returns every node below one
node that matches a kind and name filter, in a fixed order and in pages, with a summary
of the full result by kind and by parent. It answers "show me all X in Y" in one call,
where walking the tree with `list_children` would take one call per node.

This document explains why the tool exists, how it traverses, filters and pages, what it
returns, and how to use it. Concepts shared by all tools (node kinds, kind filters, node
references, pagination, errors) are defined in the
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

Many questions about a codebase are set questions over a subtree: all interfaces in a
module, all records in a package, every method whose signature mentions `Money`.
`list_descendants` answers them with one traversal, and its summary often answers them
without reading the list at all.

**Filter the result, not the walk.** The whole subtree is always traversed; the filters
decide only which nodes are reported. `kindFilter = ["java.method"]` on a module reaches
the methods through packages and types that are themselves not reported.

**Summary first.** `by_kind` and `by_parent` describe the full result on every page. "How
many records does `billing-core` have, and in which packages?" is answered by a page with
`limit = 1`.

**Complete through paging.** Unlike `find_node` and `list_children`, which cap their
answer, `list_descendants` delivers every match: a large result is split into pages
linked by cursors (see [§7 of the MCP tools specification](hierograph-mcp-tools.md#7-pagination)).

**`list_descendants` and `list_children`.** Both list what is inside a node:

| | `list_descendants` | `list_children` |
|---|---|---|
| Depth | the whole subtree | direct children |
| Order | containment tree in pre-order, siblings sorted by qualified name | the model's child order |
| Size control | cursor paging, response budget | `limit`, no paging |
| Summary | `by_kind` and `by_parent` | `by_kind` |

1. `list_descendants` answers "what is anywhere in here?".
2. `list_children` answers "what is directly in here?" and is cheaper for one step of
   exploration.

---

## 2. Parameters

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `nodeId` | integer | yes | — | the node whose subtree to search; not itself reported |
| `kindFilter` | string[] | no | none | kinds and group aliases to report |
| `namePattern` | string | no | none | case-insensitive substring of the node's name |
| `modifierFilter` | string[] | no | none | modifiers a method or field must all have |
| `limit` | integer | no | `150` | items per page, clamped to 1–500 |
| `cursor` | string | no | none | `next_cursor` of the previous page |

**`nodeId`** is any node id, including the synthetic root's, which searches the whole
graph. The node itself is never part of the result, because the caller already has it.

**`kindFilter`** accepts the kind values and group aliases of
[§3.2 of the MCP tools specification](hierograph-mcp-tools.md#32-kinds), mixed freely.
It selects what is reported, never what is traversed. Omitting it reports every
descendant. An empty list `[]` is rejected with `INVALID_KIND` (§5). External types
cannot be selected by kind; they appear only without a filter.

**`namePattern`** keeps nodes whose name contains the pattern, ignoring case. It matches
the name, not the qualified name: a package's last segment, a type's simple name, a
member's signature. Because member names are signatures, `namePattern = "Money"` with
`kindFilter = ["members"]` finds every member whose signature mentions a type whose name
contains `Money`, such as `com.example.money.Money total()`.

**`modifierFilter`** reports the methods and fields whose modifiers include every
listed value. It exists for questions across a whole subtree, such as every public
static method of a module. Values are matched ignoring case against the member modifier vocabulary:
a visibility (`public`, `protected`, `private`, `package-private`), then `static`,
`final`, `abstract`, `synchronized`, `native` and `default` for methods (constructors
included) and `static`, `final`, `transient` and `volatile` for fields. Every member
has exactly one visibility, so `["package-private"]` finds the members without a
visibility keyword. An unknown value is rejected with `INVALID_PARAMETER` (§5); an
empty list is no filter.
Like `kindFilter`, it selects what is reported, not what is traversed: packages and
types are still walked but, carrying no member modifiers, never reported while the
filter is set.

**`limit`** sets the page size. The default of 150 fits the response budget; values
from 1 to 500 are accepted, but a page of more than 200 items exceeds the budget and
fails with `RESULT_TOO_LARGE` (§3.3).

**`cursor`** resumes a walk. Pass the previous page's `next_cursor` unchanged, with the
same `nodeId`, `kindFilter`, `namePattern` and `modifierFilter`. `limit` may change
between pages.

---

## 3. Behaviour

### 3.1 Traversal and order

The tool walks the subtree depth-first in pre-order: it visits a node, then that node's
subtree, then the next sibling. At every level it sorts the children by qualified name
and, for equal qualified names, by id compared as text. The comparison is a plain,
case-sensitive string comparison, so upper-case letters sort before lower-case ones: in
`com.example.billing` the type `Invoice` comes before a sub-package `internal`.

The result is the list of visited nodes that pass the filters, in visiting order. The
fixed order is what lets a cursor resume at an offset (§3.3): the same query over the
same graph produces the same list.

### 3.2 Summary

While it walks, the tool counts every matching node:

- `by_kind` counts matches per kind, in order of first occurrence;
- `by_parent` counts matches per direct parent and keeps the ten parents with the most
  matches, highest first. Parents with equal counts keep the order in which they were
  first met.

Both describe the full result, on every page. `by_parent` shows where the matches
cluster: the packages that hold most of a module's records, the types with the most
getters.

### 3.3 Pages

The tool pages the result as described in
[§7 of the MCP tools specification](hierograph-mcp-tools.md#7-pagination):

- `summary.total` is the size of the full result; `summary.returned` the size of this
  page; `summary.truncated` is `true` when `total > returned`, which includes the last
  page of a multi-page walk.
- `next_cursor` is present when more items follow and absent on the last page.
- The query hash covers `nodeId`, the sorted `kindFilter`, `namePattern` as given and
  `modifierFilter` in lower case, without duplicates and sorted. Reordering a filter
  list or changing the case of a modifier keeps a cursor valid; changing the case of
  `namePattern` or writing an alias instead of its kinds does not.
- Each item counts as 250 bytes against the 50,000-byte
  [response budget](hierograph-mcp-tools.md#73-the-response-budget), so a page holds at
  most 200 items. A `limit` above 200 succeeds only when 200 or fewer items remain.

### 3.4 Example

The package `com.example` (id `4701`) contains `billing` with `Invoice`, `Invoice$Line`
and `Ledger`, and `money` with `Money`. The call
`list_descendants(nodeId = 4701, kindFilter = ["types"], limit = 2)` walks:

```
billing                         java.package   not reported
  Invoice                       java.class     match 1
    (5 members)                                not reported
  Invoice$Line                  java.class     match 2
    (3 members)                                not reported
  Ledger                        java.interface match 3
    (1 member)                                 not reported
money                           java.package   not reported
  Money                         java.record    match 4
    (4 members)                                not reported
```

The full result has four types. The first page holds `Invoice` and `Invoice$Line`, with
`total` `4`, `returned` `2`, `truncated` `true` and a `next_cursor`. The second page holds
`Ledger` and `Money`, again with `truncated` `true`, and no `next_cursor`.

---

## 4. Response

The first page of the example:

```json
{
  "root": {
    "id": 4701, "name": "example", "qualified_name": "com.example",
    "kind": "java.package", "parent_id": 4700, "parent_kind": "java.package",
    "child_count": 2, "descendant_type_count": 4, "direct_type_count": 0
  },
  "results": [
    { "id": 4711, "name": "Invoice", "qualified_name": "com.example.billing.Invoice",
      "kind": "java.class", "parent_id": 4702, "parent_kind": "java.package",
      "member_count": 5, "method_count": 3, "field_count": 2 },
    { "id": 4712, "name": "Invoice$Line", "qualified_name": "com.example.billing.Invoice$Line",
      "kind": "java.class", "parent_id": 4702, "parent_kind": "java.package",
      "member_count": 3, "method_count": 2, "field_count": 1 }
  ],
  "summary": {
    "total": 4,
    "returned": 2,
    "truncated": true,
    "by_kind": { "java.class": 2, "java.interface": 1, "java.record": 1 },
    "by_parent": [
      { "parent": { "id": 4702, "name": "billing", "qualified_name": "com.example.billing",
                    "kind": "java.package", "parent_id": 4701, "parent_kind": "java.package" },
        "match_count": 3 },
      { "parent": { "id": 4720, "name": "money", "qualified_name": "com.example.money",
                    "kind": "java.package", "parent_id": 4701, "parent_kind": "java.package" },
        "match_count": 1 }
    ]
  },
  "next_cursor": "eyJ2IjoxLCJ0b29sIjoibGlzdF9kZXNjZW5kYW50cyIs…"
}
```

| Field | Content | Why |
|-------|---------|-----|
| `root` | the node `nodeId` names, as an enriched node reference | confirms the scope and its size |
| `results` | this page's matches as enriched node references | the ids and sizes of the matches |
| `summary.total` | matches in the full result | the answer to "how many?" |
| `summary.returned` | matches on this page | tells a page from the full result |
| `summary.truncated` | `true` when `total > returned` | marks a partial view; stays `true` on the last page |
| `summary.by_kind` | matches per kind over the full result | the composition of the result |
| `summary.by_parent` | up to ten `{parent, match_count}` entries over the full result | where the matches cluster |
| `next_cursor` | token for the next page; absent on the last page | resumes the walk without server state |

**`by_parent[].parent`** is a minimal node reference
([§6.1](hierograph-mcp-tools.md#61-minimal-noderef)) rather than an enriched one, so ten
entries stay small. For matches directly below `nodeId`, the parent is `nodeId` itself.

Members and external types in `results` carry no counts beyond the minimal fields
([§6.2](hierograph-mcp-tools.md#62-enriched-noderef)).

---

## 5. Errors

| Situation | Code | Message | Recovery |
|-----------|------|---------|----------|
| `nodeId` names no node | `NODE_NOT_FOUND` | `No node with id 9999 exists in the graph.` | `Use find_node to look up the correct node ID.` |
| `kindFilter` contains a value that is neither a kind nor an alias | `INVALID_KIND` | `Unknown kind 'classes'. Valid kinds: java.module, java.package, …. Group aliases: types, members, packages.` | `Use valid kind values or group aliases.` |
| `kindFilter` is `[]` | `INVALID_KIND` | `Unknown kind . Valid kinds: …` | omit `kindFilter` instead |
| `modifierFilter` contains an unknown value | `INVALID_PARAMETER` | `Unknown modifier 'pubic'. Valid modifiers: public, protected, private, package-private, static, final, abstract, synchronized, native, default, transient, volatile.` | `Use valid modifier values, or omit modifierFilter.` |
| `cursor` is malformed, from another tool, or issued for other parameters or another graph | `INVALID_CURSOR_FORMAT`, `STALE_CURSOR_VERSION`, `WRONG_TOOL_CURSOR`, `STALE_CURSOR_QUERY`, `STALE_CURSOR_DATA` | see [§7.2](hierograph-mcp-tools.md#72-cursors) | restart without `cursor`, or restore the original parameters |
| The page exceeds the response budget | `RESULT_TOO_LARGE` | see [§7.3](hierograph-mcp-tools.md#73-the-response-budget) | retry with `suggested_limit` (`200`), or `limit = 1` for the summary |
| No graph is loaded | — | `{"status": "no_graph_available", …}` ([§8.3](hierograph-mcp-tools.md#83-no-graph-loaded)) | build the project, then call `reload_graph` |

The checks run in this order: node, kind filter, modifier filter, cursor, budget. `NODE_NOT_FOUND` and
`INVALID_KIND` use the nested shape of [§8.1](hierograph-mcp-tools.md#81-error-shapes)
with `code`, `message` and `recovery` and no further fields. The cursor and budget errors
carry the extra fields described in §7 of the MCP tools specification.

The empty-list case reports an `INVALID_KIND` whose message names no kind. A caller
that wants no filter omits the parameter.

---

## 6. Working with the tool

**Count without listing.** `list_descendants(nodeId = 4690, kindFilter = ["java.record"], limit = 1)`
returns the number of records in `billing-core` in `summary.total` and the packages that
hold them in `by_parent`.

**List every package of a module.** `list_descendants(nodeId = 4690, kindFilter = ["packages"])`.
Packages with `direct_type_count` above `0` are the ones that hold types.

**Find members by signature.** `list_descendants(nodeId = 4711, kindFilter = ["members"], namePattern = "money")`
returns every member of `Invoice` whose signature mentions `Money`.

**Page through a large result.** Call without `cursor`. While the response carries
`next_cursor`, call again with it and the same filters. Stop when `next_cursor` is
absent; do not stop on `truncated`, which stays `true` on the last page.

**Recover from a stale cursor.** After `STALE_CURSOR_DATA` the graph has changed; start
again without `cursor`.

---

## 7. Guarantees

1. **Complete.** Following `next_cursor` from the first page to the last returns every
   match exactly once.
2. **Deterministic order.** For one loaded graph, the same parameters produce the same
   result in the same order.
3. **Full-result summaries.** `total`, `by_kind` and `by_parent` describe the full result
   on every page.
4. **Root excluded.** The node `nodeId` names never appears in `results`.
5. **Bounded pages.** A page either fits the response budget or fails with
   `RESULT_TOO_LARGE`; it is never silently shortened.
6. **Read-only.** The tool changes no server state.
