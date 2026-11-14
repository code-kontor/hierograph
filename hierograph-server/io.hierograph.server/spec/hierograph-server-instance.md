# Hierograph Server Instance — Specification

A running Hierograph server serves one workspace. This document specifies how a server
is found and identified: its port, the settings a workspace keeps for it, the runtime
file it writes, the rule of one server per workspace, and its ready line. The
[`server_info`](hierograph-tool-server-info.md) tool returns the same identity over MCP.

---

## Contents

1. [Why it exists](#1-why-it-exists)
2. [Port](#2-port)
3. [Workspace settings](#3-workspace-settings)
4. [Runtime file](#4-runtime-file)
5. [One server per workspace](#5-one-server-per-workspace)
6. [Ready line](#6-ready-line)
7. [In a container](#7-in-a-container)
8. [Settings](#8-settings)
9. [Guarantees](#9-guarantees)

---

## 1. Why it exists

The port of a server is chosen in one place and used in others: the start command, the
MCP client's registration, a container's port mapping. Without a record that both sides
can read, a client registered for one port meets a server on another, with
`ECONNREFUSED` as the only message, and a second server for the same workspace starts
unnoticed and watches and scans it a second time.

The server therefore records where it runs, refuses to start next to itself, and says
in one log line where clients connect.

## 2. Port

The MCP endpoint is `http://localhost:<port>/mcp`. The default port is **8765**.
8080 is avoided because JVM developers often run Spring Boot, Tomcat or Jenkins on it.

`server.port=0` lets the server pick a free port. The runtime file (§4) and
`server_info` report the port in use.

## 3. Workspace settings

`<state dir>/server.properties` holds Spring properties that a workspace keeps for its
server, typically a fixed `server.port`:

```properties
# Settings for the Hierograph server of this workspace, read on every start.
server.port=8766
```

The file is optional. It is written once, by the bootstrap skill or by hand, and read
on every start. Command-line arguments override it. The state directory is
`hierograph.graph.stateDir`, by default `.hierograph` in the directory the server is
started from.

## 4. Runtime file

Once the server is ready to serve, it writes `<state dir>/server.json`:

```json
{
  "url" : "http://localhost:8765/mcp",
  "port" : 8765,
  "pid" : 75925,
  "version" : "0.3.0",
  "commit" : { "id" : "cd897fe", "dirty" : false, "time" : "2026-11-14T13:26:38Z" },
  "build_time" : "2026-11-14T13:49:50.177Z",
  "started_at" : "2026-11-14T13:50:24.242Z",
  "workspace" : "/home/dev/project",
  "state_dir" : "/home/dev/project/.hierograph",
  "source" : {
    "kind" : "manifest",
    "manifest" : "/home/dev/project/.hierograph/manifest.json",
    "build_root" : "/home/dev/project",
    "include_tests" : true,
    "watch" : { "mode" : "native", "build_files" : "regenerate" }
  }
}
```

The fields are the identity part of [`server_info`](hierograph-tool-server-info.md#4-response):
what stays the same while the server runs. What changes while it runs (artifacts,
snapshot, build state) is only in `server_info`.

- **Written when ready.** The file appears once the web server listens and the graph is
  loaded, or the server has started empty. A client that finds the file can connect.
- **Replaced in one step.** A reader never sees a partial file.
- **Deleted on shutdown,** if it still names this server's pid. A server that is killed
  leaves its file behind; the next start treats it as stale (§5) and overwrites it.
- **Only with a web server.** A test that boots the application context without one
  writes nothing.

A failure to write the file, for example in a read-only directory, is logged as a
warning and does not stop the server.

## 5. One server per workspace

Before it builds the graph or binds its port, a server reads the runtime file of its
state directory. When the file names a server that is still running, it refuses to
start:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Hierograph is already running for workspace /home/dev/project:

    url:  http://localhost:8765/mcp
    pid:  75925
    file: /home/dev/project/.hierograph/server.json

Two servers would watch and scan the same workspace.

Action:

Connect the MCP client to the running server at http://localhost:8765/mcp, or stop it (pid 75925) before starting a new one.
```

A recorded server counts as running when its process is alive **and** its port accepts
a connection. Otherwise the file is stale:

| Recorded server | Result |
|-----------------|--------|
| process alive, port answers | start refused |
| process gone | stale; the start goes ahead |
| process alive, port does not answer | stale (the pid was reused by another process) |
| pid of the starting process itself | stale (a container restarted on the same state volume) |
| no file, or a file that is not a JSON object | the start goes ahead |

The check compares state directories, not build roots: two servers started with
different state directories for the same project are not detected.
`hierograph.server.single-instance=false` turns the check off.

## 6. Ready line

Once ready, the server logs one line with the URL and what it serves:

```
Hierograph MCP ready: http://localhost:8765/mcp (workspace /home/dev/project, 24 of 26 artifacts, 9197 nodes)
```

`N of M artifacts` appears in manifest mode; `no graph loaded` replaces the node count
when the server started empty.

## 7. In a container

Inside the container the server listens on 8765, sees the project as `/workspace`, and
keeps its state, including `server.json` and `server.properties`, in `/hierograph`. The
host reaches it through the port mapping, under another URL. Two environment variables
make the server report what the host sees:

| Variable | Setting | Example |
|----------|---------|---------|
| `HIEROGRAPH_URL` | `hierograph.server.url` | `http://localhost:8765/mcp` |
| `HIEROGRAPH_WORKSPACE` | `hierograph.server.workspace` | `/home/dev/project` |

The runtime file lives in the container's state volume, where the host does not read
it; on the host, `docker ps` and `server_info` identify the server.

## 8. Settings

| Setting | Default | Meaning |
|---------|---------|---------|
| `server.port` | `8765` | port of the MCP endpoint; `0` picks a free one |
| `hierograph.server.url` | `http://localhost:<port>/mcp` | the MCP URL reported to clients |
| `hierograph.server.workspace` | the build root | the workspace reported to clients |
| `hierograph.server.runtime-file.enabled` | `true` | write `server.json` (§4) |
| `hierograph.server.single-instance` | `true` | refuse to start next to a running server (§5) |

`spring.ai.mcp.server.version`, the version in the MCP handshake's `serverInfo`,
defaults to the server's build version.

## 9. Guarantees

1. **Found without guessing.** While a server serves a workspace, `<state dir>/server.json`
   names its URL, unless the server was killed or writing failed.
2. **Ready means connectable.** The runtime file and the ready line appear only after
   the port is bound.
3. **One per workspace.** A second server for the same state directory does not start
   while the first one runs, and it names the first one's URL.
4. **No false refusals from a crash.** A file left by a server that is gone never blocks
   a start.
5. **No foreign deletes.** A server deletes only the runtime file that names it.
