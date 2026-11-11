# Hierograph Tool `get_view` — Specification

`get_view` is Hierograph's **view inspector**: it returns one view's document together
with what applying it to the current graph produces, the groups it creates with their
ids and the full report of what could not be applied. It is how a caller reads a view
it did not write, such as a view kept with the project, before answering in it.

This document explains why the tool exists, what it returns, and when to call it.
Views themselves are defined in the
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

A view's name says little about what it does. Before a model answers questions in the
`contexts` view, it needs to know which groups that view has, which modules each holds,
and whether parts of the view could not be applied. `list_views` gives counts;
`get_view` gives the content.

**Document and result together.** The response holds the document as written and the
result of applying it. A caller can compare the two: a group in the document that is
missing from `groups` was not applied, and the report says why.

**Works for broken views.** A document that does not parse is still returned, with its
problems, so the caller can see what to fix. Such a view cannot be used as `view` by
any tool.

**Works without a graph.** The document and the entry are read from disk, so the tool
answers while no graph is loaded; it then leaves out what only applying the view can
tell.

### `get_view` and its neighbours

| Tool | Answers |
|------|---------|
| [`list_views`](hierograph-tool-list-views.md) | which views exist, with counts of groups, errors and warnings |
| `get_view` | what one view defines, the groups it creates, and every finding |
| [`define_view`](hierograph-tool-define-view.md) | the same applied result for a document the caller sends |
| [`graph_overview`](hierograph-tool-graph-overview.md) with `view` | the view's top level as nodes, with the view's entry and report |

---

## 2. Parameters

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `name` | string | yes | — | the view's name |

**`name`** is the name as `list_views` reports it. When a runtime view shadows a
project view of the same name, `get_view` returns the runtime view, the one that
applies. A document that does not parse is named after its file, without `.json`.

`get_view` takes no `view` parameter: it reads a view rather than answering in one.

---

## 3. Behaviour

1. **Look up.** The server reads the view documents from the project and state
   directories, re-reading any file that changed since the last read, and takes the
   view named `name`. An unknown name is a `VIEW_NOT_FOUND` error.
2. **Read the document.** The document's text is parsed as JSON for the response. Text
   that is not JSON is returned as a string.
3. **Apply.** For a valid view while a graph is loaded, the view is applied to the
   graph the call reads. A view already applied to that graph comes from the cache.
4. **Report.** The response holds the entry, the document and, when step 3 ran, the
   applied groups, the report and, for a restructuring view, its virtual modules and
   module-level effect.

| Situation | Response |
|-----------|----------|
| valid view, graph loaded | entry, `document`, `groups`, `report`; restructuring views add `virtual_modules` and `restructuring` |
| valid view, no graph loaded | entry and `document` |
| invalid document | entry with `valid: false` and `problems`, and `document` |

---

## 4. Response

### 4.1 Example

The project view `layers`, in a graph with the modules `billing-web` and
`billing-core`:

```json
{
  "name": "layers",
  "description": "Web on top of the domain",
  "source": "project",
  "file": "/work/billing/.hierograph-views/layers.json",
  "shadows_project_view": false,
  "valid": true,
  "groups": [
    { "path": "web",    "id": 8797688628483931, "parent_id": 7239895376211087, "child_count": 1 },
    { "path": "domain", "id": 8242955433915173, "parent_id": 7239895376211087, "child_count": 1 }
  ],
  "report": {
    "errors": 0, "warnings": 1, "infos": 0,
    "findings": [ {
      "severity": "warning",
      "code": "SELECTOR_MATCHES_NOTHING",
      "group": "domain",
      "message": "selector com.example:billing-batch:* matches no node; it is ignored",
      "selector": "com.example:billing-batch:*",
      "nodes": []
    } ]
  },
  "document": {
    "name": "layers",
    "description": "Web on top of the domain",
    "groups": [
      { "name": "web",    "members": ["com.example:billing-web:*"] },
      { "name": "domain", "members": ["com.example:billing-core:*", "com.example:billing-batch:*"] } ]
  }
}
```

A project document that does not parse:

```json
{
  "name": "broken",
  "description": null,
  "source": "project",
  "file": "/work/billing/.hierograph-views/broken.json",
  "shadows_project_view": false,
  "valid": false,
  "problems": [ "groups: must be an array" ],
  "document": { "name": "broken", "groups": 1 }
}
```

### 4.2 Fields

| Field | Present | Content | Why |
|-------|---------|---------|-----|
| `name` | always | the view's name | the value to pass as `view` |
| `description` | always | the document's description, or `null` | what the view is for |
| `source` | always | `project` or `state` | whether the view is kept with the code or defined at runtime |
| `file` | always | the absolute path of the document | where to edit a project view |
| `shadows_project_view` | always | `true` for a runtime view that hides a project view of the same name | tells the caller the project's version does not apply |
| `valid` | always | whether the document parses | an invalid view cannot be used as `view` |
| `problems` | invalid document | every problem with its location | what to fix |
| `document` | always | the document as stored: a JSON object, or a string when the text is not JSON | what the view says, as its author wrote it |
| `groups` | valid view, graph loaded | applied groups: `path`, `id`, `parent_id`, `child_count` | ids for the other tools |
| `report` | valid view, graph loaded | counts and findings | what could not be applied |
| `virtual_modules` | restructuring view, graph loaded | `name`, `id`, `child_count` per virtual module | the ids of the modules the view creates |
| `restructuring` | restructuring view, graph loaded | the module-level effect | added, removed and changed module dependencies, cycles |

`groups`, `report`, `virtual_modules` and `restructuring` have the same form as in
[`define_view`](hierograph-tool-define-view.md#43-applied-view-fields), with the same
caps: 50 findings, 10 nodes per finding, 100 entries per dependency list.

---

## 5. Errors

| Code | Situation | Recovery |
|------|-----------|----------|
| `VIEW_NOT_FOUND` | no view has this name; the message lists the known views | call `list_views` for the views there are |

```json
{ "error": {
    "code": "VIEW_NOT_FOUND",
    "message": "Unknown view 'context'. Known views: broken, layers.",
    "recovery": "Call list_views for the views there are." } }
```

An invalid document is not an error: it is returned with `valid: false` (§4). The
tool runs while no graph is loaded
([§8.3](hierograph-mcp-tools.md#83-no-graph-loaded)).

---

## 6. Working with the tool

**Read a project view before using it.** Call `get_view(name = "contexts")`, take the
group ids from `groups`, then ask in the view, for example
`pairwise_dependencies(nodeIds = [<group ids>], view = "contexts")`.

**Find out why a group is missing.** Compare `document.groups` with `groups`; the
findings with that group's path name the cause, such as
`MEMBERS_WITH_DIFFERENT_PARENTS` with the parents involved.

**Fix a broken view.** Read `problems`. For a runtime view, send the corrected document
to `define_view`. A project view is fixed in its `file`, in the project; the change
applies with the next call.

---

## 7. Guarantees

1. **Current documents.** The tool reads the document as it is on disk at the time of
   the call.
2. **Same result as the view in use.** For a valid view, `groups` and `report` are
   those every tool that receives `view = "<name>"` in the same graph works with.
3. **Read-only.** The tool changes no view and no graph.
