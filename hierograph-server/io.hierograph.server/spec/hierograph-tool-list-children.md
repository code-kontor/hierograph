# Hierograph Tool `list_children` — Specification

`list_children` is Hierograph's **one-level browser**: it returns the nodes directly
inside one node (the packages of a module, the types of a package, the members of a
type) as enriched node references with their sizes. It is how a language model looks
one step down the containment tree before deciding where to go next.

This document explains why the tool exists, what it filters and returns, and how to use
it. Concepts shared by all tools (node kinds, kind filters, node references, errors) are
defined in the [MCP tools specification](hierograph-mcp-tools.md).

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

Exploring an unfamiliar module means looking at one level, judging which child matters,
and stepping into it. `list_children` serves that step. Each child comes with the
counts of [§6.2 of the MCP tools specification](hierograph-mcp-tools.md#62-enriched-noderef),
so the caller sees that `com.example.billing` holds three types and `Invoice` five
members without opening them.

**One level, nothing more.** The tool never descends. Its answer has the size of one
node's child list, which for packages and types is small enough to return whole.

**The parent included.** The response repeats the listed node as an enriched node
reference, so the caller sees where it is and how large the node is without a second
call.

**`list_children` and `list_descendants`.** Both list what is inside a node:

| | `list_children` | `list_descendants` |
|---|---|---|
| Depth | direct children | the whole subtree |
| Order | the model's child order | containment tree in pre-order, siblings sorted by qualified name |
| Size control | `limit`, no paging | cursor paging |
| Summary | `by_kind` | `by_kind` and `by_parent` |

1. `list_children` answers "what is directly in here?" and is the right call for one
   step of exploration.
2. `list_descendants` answers "what is anywhere in here?". Calling `list_children`
   repeatedly to walk a tree costs one call per node and is the wrong tool for it.

---

## 2. Parameters

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `nodeId` | integer | yes | — | the node whose children to list |
| `kindFilter` | string[] | no | none | kinds and group aliases to keep |
| `namePattern` | string | no | none | case-insensitive substring of the child's name |
| `modifierFilter` | string[] | no | none | modifiers a method or field must all have |
| `limit` | integer | no | `200` | maximum children to return, clamped to 1–1000 |

**`nodeId`** is any node id, including the synthetic root's, which lists the modules.
On a module the children are top-level packages; on a package, sub-packages and types;
on a type, its methods and fields. Methods, fields and external types have no children.

**`kindFilter`** accepts the kind values and group aliases of
[§3.2 of the MCP tools specification](hierograph-mcp-tools.md#32-kinds), mixed freely. It
exists because a package mixes sub-packages and types, and a type mixes methods and
fields: `["types"]` on a package shows its types without the namespace segments below
it. Omitting it, or passing `[]`, returns all children. An unknown value is rejected
with `INVALID_KIND` (§5). External types cannot be selected by kind; they appear only
without a filter.

**`namePattern`** keeps children whose name contains the pattern, ignoring case. It
matches the name, not the qualified name: a package's last segment, a type's simple
name, a member's signature such as `com.example.money.Money total()`. It exists to find
a member in a large type (`get` lists the getters) without reading the full list.

**`modifierFilter`** keeps the methods and fields whose modifiers include every listed
value. It exists to answer questions about a type's surface in one call:
`["public"]` lists its API, `["static", "final"]` its constants. Values are matched ignoring case against the member modifier vocabulary:
a visibility (`public`, `protected`, `private`, `package-private`), then `static`,
`final`, `abstract`, `synchronized`, `native` and `default` for methods (constructors
included) and `static`, `final`, `transient` and `volatile` for fields. Every member
has exactly one visibility, so `["package-private"]` finds the members without a
visibility keyword. An unknown value is rejected with `INVALID_PARAMETER` (§5); an
empty list is no filter.
Packages and types carry no member modifiers, so while the filter is set they never
match. To read a single member's full modifier list, use `method_details` or
`field_details`.

**`limit`** bounds the response. The default of 200 covers the children of almost any
package or type; a larger value up to 1000 is accepted for the rare type with hundreds
of members. A value below 1 becomes 1, a value above 1000 becomes 1000.

---

## 3. Behaviour

The tool resolves `nodeId`, validates `kindFilter` and `modifierFilter`, then visits the
node's children in the model's child order. A child is kept when its kind passes
`kindFilter`, its name contains `namePattern` and, with `modifierFilter` set, it is a
method or field with all the listed modifiers. The kept children are counted into `summary.total` and
`summary.by_kind`; then the first `limit` of them are returned.

**Order.** Children appear in the order the model holds them. The order is stable for
one loaded graph, but it is not sorted by name. A caller that needs alphabetical order
sorts the result itself.

**Truncation.** When more children pass the filters than `limit` allows, the response
holds the first `limit` and `summary.truncated` is `true`. There is no cursor: the
caller narrows the filters or raises `limit`.

**Example.** The package `com.example.billing` (id `4702`) has three children:
`Invoice`, `Invoice$Line` and `Ledger`. With `kindFilter = ["types"]` and `limit = 2`:

- all three pass the filter, so `total` is `3` and `by_kind` is
  `{"java.class": 2, "java.interface": 1}`;
- the first two are returned, so `returned` is `2` and `truncated` is `true`.

---

## 4. Response

```json
{
  "parent": {
    "id": 4702, "name": "billing", "qualified_name": "com.example.billing",
    "kind": "java.package", "parent_id": 4701, "parent_kind": "java.package",
    "child_count": 3, "descendant_type_count": 3, "direct_type_count": 3
  },
  "results": [
    { "id": 4711, "name": "Invoice", "qualified_name": "com.example.billing.Invoice",
      "kind": "java.class", "parent_id": 4702, "parent_kind": "java.package",
      "modifiers": ["public", "final"], "is_abstract": false,
      "member_count": 5, "method_count": 3, "field_count": 2 },
    { "id": 4712, "name": "Invoice$Line", "qualified_name": "com.example.billing.Invoice$Line",
      "kind": "java.class", "parent_id": 4702, "parent_kind": "java.package",
      "modifiers": ["public", "final", "static"], "is_abstract": false,
      "member_count": 3, "method_count": 2, "field_count": 1 }
  ],
  "summary": {
    "total": 3,
    "returned": 2,
    "truncated": true,
    "by_kind": { "java.class": 2, "java.interface": 1 }
  }
}
```

| Field | Content | Why |
|-------|---------|-----|
| `parent` | the listed node as an enriched node reference | confirms the node and gives its size and position |
| `results` | the kept children as enriched node references, at most `limit` | ids and sizes for the next step |
| `summary.total` | children that pass the filters | the full count, even when the list is cut |
| `summary.returned` | children in `results` | tells a cut list from a complete one |
| `summary.truncated` | `true` when `total > returned` | the signal to narrow or raise `limit` |
| `summary.by_kind` | count per kind over all kept children, in order of first occurrence | the composition of the node in one line |

The members of a type carry their `modifiers` and no counts
([§6.2](hierograph-mcp-tools.md#62-enriched-noderef)). For the root, `parent` has
`kind` `null` and no counts.

---

## 5. Errors

| Situation | Code | Message | Recovery |
|-----------|------|---------|----------|
| `nodeId` names no node | `NODE_NOT_FOUND` | `No node with id 9999 exists in the graph.` | `Use find_node to look up the correct node ID.` |
| `kindFilter` contains a value that is neither a kind nor an alias | `INVALID_KIND` | `Unknown kind: 'classes'. Valid kinds: java.module, java.package, …. Group aliases: types, members, packages.` | `Use values from valid_kinds or valid_aliases, or omit kindFilter.` |
| `modifierFilter` contains an unknown value | `INVALID_PARAMETER` | `Unknown modifier 'pubic'. Valid modifiers: public, protected, private, package-private, static, final, abstract, synchronized, native, default, transient, volatile.` | `Use valid modifier values, or omit modifierFilter.` |
| No graph is loaded | — | `{"status": "no_graph_available", …}` ([§8.3](hierograph-mcp-tools.md#83-no-graph-loaded)) | build the project, then call `reload_graph` |

Errors use the nested shape of [§8.1](hierograph-mcp-tools.md#81-error-shapes) with
`code`, `message` and `recovery`; `INVALID_KIND` adds `invalid_values`, `valid_kinds`
and `valid_aliases` ([§3.2](hierograph-mcp-tools.md#32-kinds)). A node is checked before the filter,
so an unknown id reports `NODE_NOT_FOUND` whatever the filters hold; `kindFilter` is
checked before `modifierFilter`. With several unknown modifiers the message starts
`Unknown modifiers`.

---

## 6. Working with the tool

**See the packages of a module.** `list_children(nodeId = 4690)` on `billing-core`
returns its top-level package `com`. Packages nest by segment, so stepping from a
module to its first package with types can take several calls; `list_descendants`
with `kindFilter = ["packages"]` lists them all at once.

**See the types of a package.** `list_children(nodeId = 4702, kindFilter = ["types"])`.

**Find a member of a large type.** `list_children(nodeId = 4711, namePattern = "total")`
returns `com.example.money.Money total()`. Pass its id to `method_details`.

**See the fields of a type.** `list_children(nodeId = 4711, kindFilter = ["java.field"])`
returns `com.example.billing.Ledger ledger` and `java.util.List lines`.

**List the modules.** `list_children` with the root's id (the `parent_id` of any module)
returns every module, `External Types` included.

---

## 7. Guarantees

1. **One level.** Every result's `parent_id` is `nodeId`.
2. **Full counts.** `summary.total` and `summary.by_kind` cover every child that passes
   the filters, whether or not it was returned.
3. **Stable order.** For one loaded graph, the same parameters return the same children
   in the same order.
4. **Bounded response.** At most `limit` children are returned, and `limit` never
   exceeds 1000.
5. **Read-only.** The tool changes no server state.
