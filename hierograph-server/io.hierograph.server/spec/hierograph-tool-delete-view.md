# Hierograph Tool `delete_view` — Specification

`delete_view` is Hierograph's **view eraser**: it removes a view defined at runtime
with `define_view`. When that view shadowed a project view of the same name, the
project view applies again, and the tool returns it.

This document explains why the tool exists, what it removes, and what it leaves alone.
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

Runtime views are working material: a model defines a few variants of a layering or a
split, keeps one and discards the rest. Runtime views persist in the server's state
directory across restarts, so without a way to remove them the catalogue would fill
with abandoned experiments.

**Only runtime views.** Project views are kept with the code, versioned and reviewed.
The server never changes them; they are edited or removed in the project. A call that
names a project view is refused with `PROJECT_VIEW`, and the recovery says where the
file is.

**Undo a shadowing.** A runtime view may shadow a project view of the same name, to
try a variant of it. Deleting the runtime view makes the project view apply again,
and the response says so.

### `delete_view` and its neighbours

| Tool | Answers |
|------|---------|
| [`define_view`](hierograph-tool-define-view.md) | stores and applies a runtime view, replacing one of the same name |
| `delete_view` | removes a runtime view |
| [`list_views`](hierograph-tool-list-views.md) | which views exist, and which runtime views shadow a project view |

To change a runtime view, define it again; deleting first is not needed.

---

## 2. Parameters

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `name` | string | yes | — | the name of the runtime view to delete |

**`name`** is the name as `list_views` reports it. A runtime document that does not
parse is named after its file without `.json`, and can be deleted by that name.

`delete_view` takes no `view` parameter.

---

## 3. Behaviour

1. **Look up.** The server takes the view that applies under `name`. An unknown name is
   a `VIEW_NOT_FOUND` error.
2. **Check the source.** A project view is refused with `PROJECT_VIEW`; nothing is
   deleted.
3. **Delete.** The runtime view's file is removed from the state directory, and the
   cached result of applying it is discarded.
4. **Report.** The tool returns the deleted name and the project view that applies now
   under that name, or `null`.

**Effect on other calls.** From the next call on, `view = "<name>"` answers in the
restored project view, or fails with `VIEW_NOT_FOUND` when there is none. A cursor
issued in the deleted view cannot resume: in a restored project view, whose document
differs, it is rejected with `STALE_CURSOR_DATA`.

**No graph needed.** The tool works on documents only and runs while no graph is
loaded ([§8.3](hierograph-mcp-tools.md#83-no-graph-loaded)).

---

## 4. Response

A runtime `layers` view that shadowed the project's `layers` view:

```json
{
  "deleted": "layers",
  "restored": {
    "name": "layers",
    "description": "Web on top of the domain",
    "source": "project",
    "file": "/work/billing/.hierograph-views/layers.json",
    "shadows_project_view": false,
    "valid": true
  }
}
```

A runtime view with no project counterpart:

```json
{ "deleted": "extract-money", "restored": null }
```

| Field | Content | Why |
|-------|---------|-----|
| `deleted` | the name of the deleted view | confirms what was removed |
| `restored` | the entry of the project view that applies again under this name, or `null` | tells the caller whether `view = "<name>"` still works, and with which document |

`restored` has the entry fields of
[`get_view`](hierograph-tool-get-view.md#42-fields): `name`, `description`, `source`,
`file`, `shadows_project_view`, `valid`, and `problems` when the project document does
not parse. It carries no counts; `get_view` applies the restored view.

---

## 5. Errors

| Code | Situation | Recovery |
|------|-----------|----------|
| `VIEW_NOT_FOUND` | no view has this name; the message lists the known views | call `list_views` for the views there are |
| `PROJECT_VIEW` | the name belongs to a project view; the message names its file | edit or remove the file in the project's `.hierograph-views` directory, or shadow it with `define_view` |

```json
{ "error": {
    "code": "PROJECT_VIEW",
    "message": "View 'layers' is a project view (/work/billing/.hierograph-views/layers.json); it is changed or removed in the project, not through the server",
    "recovery": "Edit or remove the file in the project's .hierograph-views directory, or shadow it with define_view." } }
```

Calling `delete_view` twice on a runtime view that shadowed a project view therefore
deletes the runtime view first and is refused with `PROJECT_VIEW` the second time.

---

## 6. Working with the tool

**Discard an experiment.** After comparing variants defined with `define_view`, delete
the ones not kept. Check `list_views` for runtime entries (`source: "state"`) left
over from earlier sessions.

**Go back to the project's version.** When `list_views` shows
`shadows_project_view: true`, `delete_view` with that name restores the project view;
`restored` confirms it.

---

## 7. Guarantees

1. **Project views are never deleted.** The tool removes files from the state
   directory only.
2. **Nothing on refusal.** A `VIEW_NOT_FOUND` or `PROJECT_VIEW` error changes nothing.
3. **The graph is untouched.** Deleting a view changes no node, dependency or id of
   the scanned hierarchy.
