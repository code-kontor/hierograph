# Hierograph Tool `server_info` — Specification

`server_info` is the running server's **description of itself**: which workspace it
serves, how it was started, which build it runs, and what it has loaded. Where
[`graph_overview`](hierograph-tool-graph-overview.md) describes the graph,
`server_info` describes the server. Concepts shared by all tools are defined in the
[MCP tools specification](hierograph-mcp-tools.md); the instance itself (port, runtime
file, one server per workspace) in the
[server instance specification](hierograph-server-instance.md).

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

Without it, two questions about a running server need process inspection to answer:

- **Which workspace does this server serve?** A client connected to the wrong server
  answers questions about another project, and nothing in the answers says so.
- **Does it run the current build?** A server started before a merge lacks the merged
  features, and only the jar's contents tell.

`server_info` answers both, plus the configuration and the load state that otherwise
appear only in the log.

The same identity is in the runtime file `<state dir>/server.json`
([instance specification, §4](hierograph-server-instance.md#4-runtime-file)), so it can be
read without an MCP connection.

---

## 2. Parameters

The tool has no parameters.

---

## 3. Behaviour

The tool reads the server's state and returns it. It changes nothing.

It answers whether or not a graph is loaded: it is exempt from the
`no_graph_available` response ([§8.3](hierograph-mcp-tools.md#83-no-graph-loaded)),
because which server a client talks to matters most when that server has nothing
loaded. It takes no `view`.

The REST interface serves the same response at `GET /api/server-info`.

---

## 4. Response

```json
{
  "url": "http://localhost:8765/mcp",
  "ui_url": "http://localhost:8765/ui/",
  "port": 8765,
  "pid": 75925,
  "version": "0.3.0",
  "commit": { "id": "cd897fe", "dirty": false, "time": "2026-11-14T13:26:38Z" },
  "build_time": "2026-11-14T13:49:50.177Z",
  "started_at": "2026-11-14T13:50:24.242Z",
  "workspace": "/home/dev/project",
  "state_dir": "/home/dev/project/.hierograph",
  "source": {
    "kind": "manifest",
    "manifest": "/home/dev/project/.hierograph/manifest.json",
    "build_root": "/home/dev/project",
    "include_tests": true,
    "watch": { "mode": "native", "build_files": "regenerate" }
  },
  "artifacts": {
    "declared": 26,
    "scanned": 24,
    "not_compiled": [
      { "artifact": "com.example:billing-itest:1.0:test",
        "dirs": ["/home/dev/project/billing-itest/target/test-classes"] }
    ]
  },
  "snapshot": {
    "generation": 3,
    "data_hash": "ucPiN6zLAzzlf4rj",
    "total_nodes": 9197,
    "detail_dir": "/home/dev/project/.hierograph/snapshot/gen-0003"
  },
  "build_state": { "status": "current", "changed_files": [], "build_tool": "maven" },
  "graph_update": "idle",
  "runtime_file": "/home/dev/project/.hierograph/server.json"
}
```

**Identity** — the same for the life of the server, and recorded in the runtime file:

| Field | Description |
|-------|-------------|
| `url` | the MCP URL clients connect to: `hierograph.server.url`, else `http://localhost:<port>/mcp` |
| `ui_url` | the web UI's URL: the address of `url` with `/ui/` in place of the MCP endpoint; `null` without a web server |
| `port` | the port the server listens on; `null` without a web server |
| `pid` | the server's process id |
| `version` | the server's build version; `null` when the server runs without build info, for example from an IDE |
| `commit` | the git commit the server was built from: `id` (abbreviated), `dirty` (built with uncommitted changes), `time`; `null` when built outside a git checkout |
| `build_time` | when the server jar was built |
| `started_at` | when the server process started |
| `workspace` | the workspace the server serves: `hierograph.server.workspace`, else the build root |
| `state_dir` | where the server writes its state: snapshot, logs, runtime file |
| `source` | the graph source: `kind` `manifest` with `manifest`, `build_root`, `include_tests` and `watch` {`mode`, `build_files`}, or `kind` `parquet` with `parquet_dir` |

**State** — what the server serves right now:

| Field | Present | Description |
|-------|---------|-------------|
| `artifacts` | always; `null` outside manifest mode or before a manifest was read | `declared` outputs (main and test outputs of each module), how many are `scanned`, and the `not_compiled` ones with their directories: watched, and scanned once they appear |
| `snapshot` | always; `null` without a graph | `generation` (the build id of the graph in service), `data_hash` (as in cursors and `reload_graph`), `total_nodes` (as in `graph_overview`), `detail_dir` (the detail snapshot the detail tools read; `null` while it is being written) |
| `build_state` | manifest mode | as in [`graph_overview`](hierograph-tool-graph-overview.md) and [`reload_graph`](hierograph-tool-reload-graph.md) |
| `graph_update` | manifest mode | what the server's updater is doing: `idle`, `pending` (a change or a reload waits for its turn), `rescanning` (changed modules), `applying-manifest` or `reloading`; anything but `idle` means the graph is about to change, and a `reload_graph` would only wait for it |
| `runtime_file` | always | the `server.json` this server wrote; `null` when it wrote none |

The response contains no environment variables and no secrets.

---

## 5. Errors

The tool has no error cases.

---

## 6. Working with the tool

**Check the server before the first query.** Compare `workspace` with the build root of
the project you are working in. If they differ, the client is connected to another
project's server: stop and say so, rather than answering from that graph.

**Check the build.** `version` and `commit` say which build the server runs. A server
started before a change was merged has an older `commit`, or `dirty: true` when it was
built from a working tree.

**Find artifacts that are missing from the graph.** `artifacts.not_compiled` names each
declared output that has no compiled classes yet. A module that is missing from
`graph_overview` and not listed there was not declared: a profile is missing from the
manifest build.

**Wait for detail data.** `snapshot.detail_dir` is `null` while the detail snapshot of
the graph in service is being written; detail-level queries then report "rebuilding".

---

## 7. Guarantees

1. **Always callable.** The tool answers with and without a loaded graph.
2. **Read-only.** The tool changes no server state.
3. **One source of identity.** The identity fields equal the content of the runtime file
   this server wrote.
