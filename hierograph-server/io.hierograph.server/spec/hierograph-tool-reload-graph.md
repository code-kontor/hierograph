# Hierograph Tool `reload_graph` — Specification

`reload_graph` is Hierograph's **manual rebuild**: it makes the server build the graph
again from its configured source and serve the result, without a restart. It is the only
tool that changes server state, and the only one that answers while no graph is loaded,
because it is how a graph gets loaded once its input exists.

This document explains why the tool exists, what a reload does, what it returns, and
when to call it. The reload itself belongs to the
[hierarchical graph service](../../io.hierograph.server.graphservice/spec/hierarchical-graph-service.md);
this document describes what the tool exposes of it. Concepts shared by all tools are
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

The server builds its graph from one of two sources (see
[source modes](../../io.hierograph.server.graphservice/spec/hierarchical-graph-service.md#4-source-modes)):
a Parquet snapshot, or a build manifest whose modules it scans itself. In manifest mode
a watcher follows compiled classes and the manifest, so the graph usually keeps up on
its own. Some changes are invisible to it, and a Parquet snapshot is never watched.
`reload_graph` covers those cases.

**Explicit, complete rebuild.** A reload rebuilds the whole graph from the source: in
manifest mode it rescans every module rather than only the changed ones, in Parquet
mode it reads the snapshot again. Whatever the watcher missed, a reload picks up.

**A failure keeps the graph in service.** The rebuilt graph is complete before it replaces
the graph in service. A failed reload leaves the graph in service unchanged and says why.

**The way out of an empty server.** The server starts even when no graph can be built,
for example before the project has been compiled. Every other tool then answers
`no_graph_available` ([§8.3](hierograph-mcp-tools.md#83-no-graph-loaded)), and
`reload_graph` is the call that loads the graph once the input exists.

**`reload_graph` and `graph_overview`.** Both report the `build_state` in manifest mode.
`graph_overview` only reads it; `reload_graph` rebuilds first and then reports it, and
a reload also starts a regeneration when the mode asks for one (§3).

---

## 2. Parameters

The tool has no parameters. It always reloads from the source the server was
configured with.

**Why none.** The source, its location and the watch settings are server configuration
(see the
[service configuration](../../io.hierograph.server.graphservice/spec/hierarchical-graph-service.md#10-configuration)).
A tool that let a model point the server at another input would let one client change
what every other client sees.

---

## 3. Behaviour

A call runs these steps:

1. **Generate a missing manifest.** In manifest mode, when the manifest file does not
   exist and the build-file mode is `regenerate` with a configured
   `hierograph.graph.regenerate.command`, the service runs that command in the build root
   and waits for it. A failed run sets `build_state` to `regeneration-failed`; the build in
   step 2 then fails for want of a manifest.
2. **Build.** The service builds the graph again from the source: it scans every module the
   manifest declares, or reads the Parquet snapshot. It then builds the node search
   index and the data hash for the rebuilt graph.
3. **Swap.** The rebuilt graph replaces the graph in service in one step. Every read of
   the graph sees a complete graph, the one before the swap or the one after it, never a
   partially built one.
4. **Publish detail and watch.** The detail-level snapshot for the rebuilt graph is
   published (at once in Parquet mode, in the background in manifest mode). In manifest
   mode the watcher is started or re-targeted at the module set of the manifest just read.
5. **Report.** The tool returns the status, the size of the rebuilt graph and its data hash,
   plus `build_state` in manifest mode.

If step 2 fails, the swap does not happen. In manifest mode the watcher is still
re-targeted, so a server waiting for its first compiled module keeps watching. The tool
returns `status` `"error"`.

**Cursors.** Pagination cursors carry the data hash of the graph they were issued for
([§7.2](hierograph-mcp-tools.md#72-cursors)). A reload that changes the graph changes
the hash, and every earlier cursor then fails with `STALE_CURSOR_DATA`. A reload that
produces the same graph produces the same hash, and earlier cursors stay valid.

**Build files.** Applying a manifest re-evaluates the build files it lists. In
`regenerate` mode a reload therefore starts a regeneration when build files are stale,
which also retries a regeneration that failed (see
[build files](../../io.hierograph.server.graphservice/spec/hierarchical-graph-service.md#build-files)).

**Cost.** A reload takes as long as a full scan or snapshot load; `reload_millis`
reports it. Until the swap, tool calls are served from the graph loaded before the call.

---

## 4. Response

A successful reload in manifest mode, for a graph with the modules `billing-core` and
`External Types` and 26 nodes below them:

```json
{
  "status": "reloaded",
  "root_children": 2,
  "search_index_entries": 28,
  "data_hash": "k3VtQ9xZr2LmA7pE",
  "reload_millis": 412,
  "build_state": { "status": "current", "changed_files": [], "build_tool": "maven" }
}
```

A failed reload on a server that has no graph yet:

```json
{
  "status": "error",
  "message": "No module of manifest /work/billing/.hierograph/manifest.json is compiled yet; watching 2 declared output(s)",
  "data_hash": null,
  "note": "The previously loaded graph is still being served; re-run the scan and retry.",
  "build_state": { "status": "current", "changed_files": [], "build_tool": "maven" }
}
```

| Field | Present | Content | Why |
|-------|---------|---------|-----|
| `status` | always | `"reloaded"` or `"error"` | the outcome, for the caller to act on |
| `root_children` | on success | number of modules, `External Types` included | a quick check that the expected modules were found |
| `search_index_entries` | on success | number of nodes in the graph, modules included, root excluded | the size of the rebuilt graph: `graph_overview`'s `total_nodes` plus the modules |
| `data_hash` | always | the data hash of the graph now served; `null` when none is loaded | tells the caller whether earlier cursors are still valid |
| `reload_millis` | on success | duration of the build in milliseconds | the cost of a reload on this codebase |
| `message` | on error | the cause of the failure | what to fix before retrying |
| `note` | on error | a fixed sentence saying the previous graph is still served | reminds the caller that queries still work |
| `build_state` | manifest mode | `status`, `changed_files`, `build_tool`, `message` | whether the manifest still matches the build files |

**`data_hash`** is a 16-character base64url string. Comparing it with the hash before
the call shows whether the reload changed the graph.

**`note`** is the same sentence on every error, also when no graph was loaded before
and `data_hash` is `null`. In that case no previous graph is served.

**`build_state`** has the status values `current`, `stale`, `regenerating` and
`regeneration-failed`, defined in the
[service specification](../../io.hierograph.server.graphservice/spec/hierarchical-graph-service.md#build-files).
A server that loads a Parquet snapshot omits it.

---

## 5. Errors

The tool does not return an `error` object. A failure is a response with `status`
`"error"`:

| Situation | `message` | Recovery |
|-----------|-----------|----------|
| No declared module has compiled output yet (manifest mode) | `No module of manifest … is compiled yet; watching N declared output(s)` | build the project; the watcher loads the graph when output appears, or call again |
| The manifest or snapshot cannot be read | the exception's message | fix the path or run the build that writes the input, then call again |
| Generating a missing manifest failed | the message of the failed manifest read; `build_state.status` is `regeneration-failed`, with the end of the build output in `build_state.message` | fix the build, then call again |

`reload_graph` is exempt from the `no_graph_available` response
([§8.3](hierograph-mcp-tools.md#83-no-graph-loaded)): it runs whether a graph is loaded
or not.

---

## 6. Working with the tool

**Load the graph after the first build.** When a tool answers `no_graph_available`,
build the project, then call `reload_graph`. Check `status` and `root_children`.

**Refresh a Parquet-mode server.** After the snapshot has been rewritten, call
`reload_graph`. The server does not watch a snapshot.

**Pick up what the watcher misses.** In manifest mode, call `reload_graph` after a change
the watcher does not see, such as a SNAPSHOT dependency updated in the local repository
or a profile activated outside the build files.

**Act on `build_state`.** `stale` with `changed_files` means the build files changed
after the manifest was written: run the build that writes the manifest, then call
`reload_graph`. `regeneration-failed` carries the end of the build output in `message`.

**Resume paging after a reload.** If `data_hash` changed, restart every paged query
without `cursor`.

---

## 7. Guarantees

1. **Atomic.** Every tool call sees either the graph before the reload or the graph
   after it, never a partially built one and never both: a call that is running
   when the reload swaps the graph finishes on the graph it started with.
2. **No loss on failure.** A failed reload leaves the graph that was in service before the
   call in service.
3. **Always callable.** The tool runs whether or not a graph is loaded.
4. **Consistent snapshot.** After a successful reload, the search index, the data hash
   and the model served to every tool belong to the same build.
5. **Cursor safety.** A cursor issued before a reload that changed the graph is rejected
   with `STALE_CURSOR_DATA`.
