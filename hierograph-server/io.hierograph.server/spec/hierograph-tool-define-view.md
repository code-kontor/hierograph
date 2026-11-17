# Hierograph Tool `define_view` — Specification

`define_view` is Hierograph's **view editor**: it stores a view document on the server
and applies it to the graph in one call. A **view** regroups the hierarchy (modules
into layers, packages into bounded contexts) or, as a **restructuring view**, moves
packages and types into other or new modules to project a split or merge before any
code changes. Once a view is defined, every tool that reads the graph answers in it
when called with `view = "<name>"`.

This document explains why the tool exists, what it stores, what it returns, and how
to iterate on a view with it. The view document, its selectors, resolution and the
report belong to the
[views specification](../../io.hierograph.server.views/spec/hierograph-views.md);
this document describes what the tool exposes of them. Concepts shared by all tools
are defined in the [MCP tools specification](hierograph-mcp-tools.md).

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

The scanned hierarchy shows the code as it is built: modules, packages, types. Many
architecture questions are about a different structure. Is the web layer above the
domain? Do the bounded contexts depend on each other only through their APIs? Would
moving `com.example.money` into a module of its own create a cycle? A view answers
them by laying that structure over the same nodes and dependencies, and every other
tool then works on it unchanged.

**Defined by the caller, at runtime.** A model that plans a refactoring writes the
view it needs, looks at the result and refines it. `define_view` is that loop's write
step. Views kept with the code (project views) are written by people and versioned;
`define_view` writes runtime views to the server's state directory and never touches
the project.

**Store and apply in one call.** The tool validates the document, stores it and
applies it to the current graph, and returns what applying it found: the groups it
created with their ids, and the findings of everything that could not be applied.
The caller needs no second call to learn whether the view worked.

**A partly wrong view still applies.** A document that parses is applied as far as it
can be; selectors that match nothing or groups whose members have different parents
become findings in the report. A large view that is mostly right is usable while it
is being fixed.

### `define_view` and its neighbours

| Tool | Reads or writes | Answers |
|------|-----------------|---------|
| `define_view` | writes a runtime view, then applies it | what this document does to the hierarchy |
| [`get_view`](hierograph-tool-get-view.md) | reads one view | what a stored view defines and what applying it reports |
| [`list_views`](hierograph-tool-list-views.md) | reads all views | which views exist and whether they apply cleanly |
| [`delete_view`](hierograph-tool-delete-view.md) | removes a runtime view | — |

`define_view` returns the same applied result as `get_view` without the document,
which the caller has just sent.

---

## 2. Parameters

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `document` | object | yes | — | the view document as a JSON object |

**`document`** is a view document as defined in the
[views specification, §3](../../io.hierograph.server.views/spec/hierograph-views.md#3-the-document):
a `name`, an optional `description`, `groups` with member selectors, and for a
restructuring view `"restructure": true`, `modules` and `moves`. It is a JSON object,
not a string holding JSON, so the client's schema validation and the model's tool
call both see its structure. A short grouping view:

```json
{ "name": "layers",
  "description": "Web, then domain",
  "groups": [
    { "name": "web",    "members": ["com.example:billing-web:*"] },
    { "name": "domain", "members": ["com.example:billing-core:*", "com.example:billing-batch:*"] } ] }
```

The view's name comes from the document. There is no separate `name` parameter, so a
document and the view it defines can never disagree.

`define_view` takes no `view` parameter: it defines a view rather than answering in
one.

---

## 3. Behaviour

A call runs these steps:

1. **Validate.** The server parses the document. A document that breaks a rule of the
   format (an invalid name, a missing field, an unknown kind in a selector) is
   rejected with every problem and its location; nothing is stored (§5).
2. **Store.** The document is written to `<state dir>/<name>.json`, atomically. It
   replaces a runtime view of the same name, also one stored under another file name.
   A project view of the same name stays in the project and is **shadowed**: the
   runtime view applies instead, until it is deleted.
3. **Apply.** The view is resolved against the graph the call reads and materialized
   on a fork of it (see
   [resolution](../../io.hierograph.server.views/spec/hierograph-views.md#5-resolution)
   and [materialization](../../io.hierograph.server.views/spec/hierograph-views.md#6-materialization)).
   A cached result of an earlier version of the view is discarded first.
4. **Report.** The tool returns the view's entry, the applied groups, the report and,
   for a restructuring view, its virtual modules and module-level effect.

**Effect on other calls.** From the next call on, `view = "<name>"` answers in the new
document. Cursors issued in an earlier version of the view become invalid, because a
view's data hash covers its document
([§7.2](hierograph-mcp-tools.md#72-cursors)).

**Group ids.** A group's id depends only on the view's name and the group's path, so
it is the same after every redefinition and every rebuild of the graph. A caller can
keep using a group id while it refines the members of the group.

**No graph loaded.** `define_view` needs a graph to apply the view to. While none is
loaded it returns `no_graph_available`
([§8.3](hierograph-mcp-tools.md#83-no-graph-loaded)) and stores nothing.

---

## 4. Response

### 4.1 Example

The `layers` document of §2, defined while the project also keeps a `layers` view, in
a graph with the modules `billing-web` and `billing-core` and no `billing-batch`:

```json
{
  "name": "layers",
  "description": "Web, then domain",
  "source": "state",
  "file": "/work/billing/.hierograph/runtime-views/layers.json",
  "shadows_project_view": true,
  "valid": true,
  "groups": [
    { "path": "web",    "id": 8797688628483931, "parent_id": 7239895376211087, "child_count": 1 },
    { "path": "domain", "id": 8242955433915173, "parent_id": 7239895376211087, "child_count": 1 }
  ],
  "report": {
    "errors": 0,
    "warnings": 1,
    "infos": 0,
    "findings": [ {
      "severity": "warning",
      "code": "SELECTOR_MATCHES_NOTHING",
      "group": "domain",
      "message": "selector com.example:billing-batch:* matches no node; it is ignored",
      "selector": "com.example:billing-batch:*",
      "nodes": []
    } ]
  }
}
```

Both groups sit below the root (`parent_id` is the root's id) and hold one module
each. The warning says that `domain` lost one of its selectors, not that the view
failed.

A restructuring view that moves `com.example.money` out of `billing-core` into a
virtual module `money`:

```json
{
  "name": "extract-money",
  "description": "Money as a module of its own",
  "source": "state",
  "file": "/work/billing/.hierograph/runtime-views/extract-money.json",
  "shadows_project_view": false,
  "valid": true,
  "virtual_modules": [ { "name": "money", "id": 4712973119291066, "child_count": 1 } ],
  "restructuring": {
    "added": [
      { "from": "com.example:billing-core:1.4.0", "to": "money", "weight": 4 },
      { "from": "money", "to": "External Types", "weight": 2 } ],
    "removed": [
      { "from": "com.example:billing-core:1.4.0", "to": "External Types", "weight": 2 } ],
    "changed": [],
    "cycles": [],
    "new_cycles": [],
    "introduces_cycles": false
  },
  "groups": [],
  "report": { "errors": 0, "warnings": 0, "infos": 0, "findings": [] }
}
```

`Invoice` uses `Money` 4 times, and `Money` uses `java.math.BigDecimal` twice. After
the move, `billing-core` depends on `money` with weight 4, and the dependency on
`External Types` moves from `billing-core` to `money`. No module cycle appears.

### 4.2 Entry fields

| Field | Content | Why |
|-------|---------|-----|
| `name` | the view's name, from the document | the value to pass as `view` |
| `description` | the document's description, or `null` | what the view is for |
| `source` | `state` for a view defined with this tool | tells runtime views from project views |
| `file` | the absolute path of the stored document | where the document lives on the server |
| `shadows_project_view` | `true` when a project view of the same name exists | warns that the project's version does not apply while this view exists |
| `valid` | `true` | always true here: an invalid document is an error (§5) |

### 4.3 Applied-view fields

| Field | Content | Why |
|-------|---------|-----|
| `groups` | one entry per applied group, in materialization order: `path`, `id`, `parent_id`, `child_count` | the ids to pass to other tools, and where each group landed |
| `report` | `errors`, `warnings`, `infos` counts and `findings` | what could not be applied, and why |
| `virtual_modules` | restructuring views only: `name`, `id`, `child_count` per virtual module | the ids of the modules the view creates |
| `restructuring` | restructuring views only: the module-level effect | answers "does this move add a dependency or a cycle?" without a second call |

**`groups`** lists only groups that were applied. A group whose members have different
parents, or that ends up empty, is missing here and appears as a finding instead.
`path` is the group's names from its top-level group down, joined by `/`
(`core/money`). `parent_id` is the node the group sits below: the root, a module, a
package, or an enclosing group.

**`report.findings`** lists at most 50 findings, errors first, then warnings, then
infos, so the cap never hides an error. A longer report adds `finding_count` with the
total. Each finding has `severity` (`error`, `warning`, `info`), `code`, the `group`
path or the move or module it is about (`moves[2]`, `modules[0]`), a `message`, the
`selector` involved or `null`, and up to 10 `nodes` as `id` and `qualified_name`
(`node_count` then gives the total). The codes and their effects are listed in the
views specification:
[grouping codes](../../io.hierograph.server.views/spec/hierograph-views.md#53-the-report),
[`GROUP_HANDLE_COLLISION`](../../io.hierograph.server.views/spec/hierograph-views.md#62-group-nodes) and
[restructuring codes](../../io.hierograph.server.views/spec/hierograph-views.md#644-the-report-and-the-effect).

**`restructuring`** compares the modules after the moves with the scanned ones:

| Field | Content |
|-------|---------|
| `added` | module dependencies that exist only after the moves: `from`, `to`, `weight` |
| `removed` | module dependencies that exist only before: `from`, `to`, `weight` |
| `changed` | module dependencies whose weight changed: `from`, `to`, `weight_before`, `weight_after` |
| `cycles` | the module cycles after the moves, each a list of module names |
| `new_cycles` | the cycles the restructuring creates: those whose modules were not all in one cycle before |
| `introduces_cycles` | `true` when `new_cycles` is not empty |

Modules are named by qualified name (the Maven coordinate), virtual modules by their
name. Each of `added`, `removed` and `changed` holds at most 100 entries; a longer list
adds `added_count`, `removed_count` or `changed_count` with the total.

---

## 5. Errors

| Code | Situation | Extra fields | Recovery |
|------|-----------|--------------|----------|
| `INVALID_VIEW_DOCUMENT` | the document breaks a rule of the format | `problems`: every problem with its location | fix the listed problems and call `define_view` again |
| `INVALID_PARAMETER` | `document` is missing, `null`, or not a JSON object | — | pass the document as a JSON object, not as a string holding JSON |

An invalid document, or none, stores nothing; a view of the same name that existed
before stays as it was. A `document` sent as a string holding JSON, the most common
mistake, is refused with `'document' must be an object, not a string.` rather than
parsed: the schema declares an object, and a client that sends a string has built
the call wrongly.

```json
{ "error": {
    "code": "INVALID_VIEW_DOCUMENT",
    "message": "The view document is invalid; nothing was stored.",
    "problems": [
      "name: 'Layers' is not a valid view name; use lower-case letters, digits, '.', '_' and '-', starting with a letter or digit",
      "groups[0].name: required" ],
    "recovery": "Fix the listed problems and call define_view again." } }
```

A document that parses is never an error, however many findings applying it produces.
While no graph is loaded the tool answers `no_graph_available`
([§8.3](hierograph-mcp-tools.md#83-no-graph-loaded)).

---

## 6. Working with the tool

**Define, read the report, redefine.** Send the document, then read `report`: fix
selectors that match nothing and groups whose members have different parents, and
send the whole document again. Each call replaces the previous version; group ids
stay the same.

**Use the groups at once.** Pass the group ids from `groups` to
`pairwise_dependencies(nodeIds = [<group ids>], view = "layers")` to see whether the
groups are layered, or to `aggregated_dependencies` to measure the coupling between
two of them.

**Project a refactoring.** Define a restructuring view with the planned moves and read
`restructuring`: `added` shows the dependencies the new module will need,
`introduces_cycles` whether the split breaks the build order. Then answer detail
questions in the view, for example `incoming_dependencies` on the virtual module.

**Try a variant of a project view.** Define a view with the project view's name. It
shadows the project view for every client of this server until `delete_view` removes
it; the project's file is never changed.

---

## 7. Guarantees

1. **All or nothing on store.** An invalid document stores nothing. A valid one is
   written atomically: no other call reads a partly written document.
2. **Applied as stored.** The groups and report in the response are those of the
   document just stored, applied to the graph the call reads.
3. **Stable group ids.** A group's id depends only on the view's name and the group's
   path; redefining the view or rebuilding the graph does not change it.
4. **Project views are untouched.** The tool writes only to the state directory.
5. **The graph is untouched.** A view is applied to a fork; the scanned hierarchy and
   every call without `view` are unaffected.
