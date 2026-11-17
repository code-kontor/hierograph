# Hierograph Tool `list_views` — Specification

`list_views` is Hierograph's **view catalogue**: it lists every view a tool can be
asked to answer in, the views kept with the project and those defined at runtime,
with whether each one applies cleanly. Beside the views it describes `default`, the
hierarchy as scanned, which every call without `view` answers in.

This document explains why the tool exists, what it lists, and how to read the
listing. Views themselves are defined in the
[views specification](../../io.hierograph.server.views/spec/hierograph-views.md).
Concepts shared by all tools are defined in the
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

A project may keep views with its code (layers, bounded contexts, team ownership),
and models define more at runtime. A caller that wants to answer in a view first needs
to know which names exist and which of them are worth using.

**One call for the whole catalogue.** The listing names every view with its
description, where it comes from and, for a valid view, how many groups it applies and
how many errors and warnings applying it reports. A caller picks a view, or sees that
one needs fixing, without reading each.

**Broken views are listed, not hidden.** A document that does not parse appears with
its problems. Hiding it would leave its author guessing why the view is unknown.

**The scanned hierarchy has its place.** `default` describes what a call without
`view` answers in. It is listed beside the views, not among them, so no view name is
reserved for it: `view = "default"` names a view like any other.

**Works without a graph.** Views are documents on disk, so the tool answers while no
graph is loaded and leaves out the counts that only applying a view can give.

### `list_views` and its neighbours

| Tool | Answers |
|------|---------|
| `list_views` | which views exist, and whether each applies cleanly |
| [`get_view`](hierograph-tool-get-view.md) | what one view defines, its groups and every finding |
| [`define_view`](hierograph-tool-define-view.md) | stores and applies a runtime view |
| [`delete_view`](hierograph-tool-delete-view.md) | removes a runtime view |

---

## 2. Parameters

The tool has no parameters. It always lists every view of the server.

`list_views` takes no `view` parameter: it lists views rather than answering in one.

---

## 3. Behaviour

1. **Read the documents.** The server reads every `*.json` file directly in the
   project view directory (by default `<build root>/.hierograph-views`) and in the
   state directory (by default `<build root>/.hierograph/runtime-views`), re-reading
   only when a file was added, changed or removed since the last read. Files whose
   names start with `.` are ignored.
2. **Resolve names.** A view is named by its document's `name`, not by its file name.
   When a runtime view and a project view share a name, the runtime view applies and
   is marked as shadowing the project view, which is not listed. When two files in
   one directory share a name, the first in file-name order applies and the others are
   listed as invalid. A document that does not parse is listed under its file name
   without `.json`.
3. **Apply the valid views.** While a graph is loaded, each valid view is applied to
   the current graph, or taken from the cache, and its counts are added. A view that
   fails to apply is listed without counts.
4. **Sort.** Entries are sorted by name, then source (project before state), then file
   name.

The full rules of where documents come from are in the
[views specification, §7.1](../../io.hierograph.server.views/spec/hierograph-views.md#71-where-documents-come-from).

---

## 4. Response

### 4.1 Example

A graph with three modules at its top level, the project views `layers` and `broken`,
and the runtime restructuring view `extract-money`:

```json
{
  "default": {
    "description": "The hierarchy as scanned. Omit view to use it.",
    "top_level_count": 3
  },
  "views": [ {
    "name": "broken",
    "description": null,
    "source": "project",
    "file": "/work/billing/.hierograph-views/broken.json",
    "shadows_project_view": false,
    "valid": false,
    "problems": [ "groups: must be an array" ]
  }, {
    "name": "extract-money",
    "description": "Money as a module of its own",
    "source": "state",
    "file": "/work/billing/.hierograph/runtime-views/extract-money.json",
    "shadows_project_view": false,
    "valid": true,
    "group_count": 0,
    "errors": 0,
    "warnings": 0,
    "restructure": true,
    "virtual_module_count": 1,
    "introduces_cycles": false
  }, {
    "name": "layers",
    "description": "Web on top of the domain",
    "source": "project",
    "file": "/work/billing/.hierograph-views/layers.json",
    "shadows_project_view": false,
    "valid": true,
    "group_count": 2,
    "errors": 0,
    "warnings": 1
  } ],
  "summary": { "total": 3 }
}
```

The three top-level nodes are `billing-web`, `billing-core` and `External Types`.

### 4.2 Fields

| Field | Content | Why |
|-------|---------|-----|
| `default.description` | a fixed sentence saying that a call without `view` uses the scanned hierarchy | tells the caller how to leave a view |
| `default.top_level_count` | number of top-level nodes of the scanned hierarchy; absent while no graph is loaded | the size of the level a view regroups |
| `views` | one entry per view, sorted (§3) | the names to pass as `view` |
| `summary.total` | number of entries in `views` | a quick check without counting |

Each entry of `views`:

| Field | Present | Content | Why |
|-------|---------|---------|-----|
| `name` | always | the view's name | the value to pass as `view` |
| `description` | always | the document's description, or `null` | what the view is for |
| `source` | always | `project` or `state` | kept with the code, or defined at runtime |
| `file` | always | the absolute path of the document | where to edit a project view |
| `shadows_project_view` | always | `true` for a runtime view that hides a project view of the same name | tells the caller the project's version does not apply |
| `valid` | always | whether the document parses | an invalid view cannot be used |
| `problems` | invalid | every problem with its location | what to fix |
| `group_count` | valid, graph loaded | number of applied groups | whether the view does anything |
| `errors`, `warnings` | valid, graph loaded | counts of the report's findings | whether the view applies cleanly |
| `restructure` | restructuring view, graph loaded | `true` | marks a view that moves code |
| `virtual_module_count` | restructuring view, graph loaded | number of virtual modules | the size of the projected split |
| `introduces_cycles` | restructuring view, graph loaded | whether the moves create a module cycle | the first question about a split, answered in the listing |

The listing carries counts, not findings. `get_view` returns the findings.

---

## 5. Errors

The tool has no inputs and returns no error. It runs while no graph is loaded
([§8.3](hierograph-mcp-tools.md#83-no-graph-loaded)); the counts are then absent. A
view directory that cannot be read lists no views from that directory.

---

## 6. Working with the tool

**Pick a view.** Call `list_views`, choose a valid view with `errors: 0`, and pass its
name as `view` to any other tool. Call `get_view` first when the group ids are needed.

**Spot a broken view.** An entry with `valid: false` names its problems. A valid entry
with `errors` above zero applies only in part; `get_view` shows which groups are
affected.

**Check a projected split.** For restructuring views, `introduces_cycles` shows
whether any of them creates a module cycle; `get_view` or `define_view` returns the
cycles.

**Return to the scanned hierarchy.** Leave `view` out. There is no view called
`default`.

---

## 7. Guarantees

1. **Complete.** Every view a tool accepts as `view` is listed, and every document
   that does not parse is listed with its problems.
2. **Current.** The listing reflects the files on disk at the time of the call.
3. **Deterministic order.** For the same files, entries come in the same order.
4. **Read-only.** The tool changes no view and no graph.
