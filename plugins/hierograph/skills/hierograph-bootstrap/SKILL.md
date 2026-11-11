---
name: hierograph-bootstrap
description: Set a Maven project up for architectural analysis with the Hierograph MCP server and register the server with the MCP client. By default it starts the self-contained Hierograph Docker container — the project and ~/.m2 mounted read-only, no change to the project at all; the container produces and regenerates the build manifest itself. Alternatively it runs the server as a local Java process on a manifest from the host build. Trigger when the user says "bootstrap hierograph", "set up hierograph for this project", "get this project ready for hierograph", "wire up the hierograph MCP server", or "I want to analyze this codebase's architecture" (and hierograph is not yet running). Do NOT use once the hierograph MCP tools are already available — then just query them (see hierograph-structure / hierograph-extract); this skill only performs first-time setup.
allowed-tools:
  - Bash(java -version)
  - Bash(java -jar:*)
  - Bash(mvn:*)
  - Bash(./mvnw:*)
  - Bash(curl -s:*)
  - Bash(curl -fL:*)
  - Bash(nc -z:*)
  - Bash(docker run:*)
  - Bash(docker start:*)
  - Bash(docker logs:*)
  - Bash(docker ps)
  - Bash(docker stop:*)
  - Bash(docker pull:*)
  - Bash(docker version)
  - Bash(claude mcp add:*)
  - Bash(claude mcp list)
  - Read
  - Glob
---

# hierograph-bootstrap — set a project up for hierograph analysis

This skill performs the one-time setup that makes the Hierograph MCP tools available for a
**target Maven project**. Hierograph works from the **build manifest** (`manifest.json`): what only
the build knows — the modules, their compiled-output directories, their resolved classpaths, the POMs.
The server reads it, scans the compiled classes itself, and then **follows the project on its own**:
recompiled classes, added or removed modules and edited POMs reach the graph without another setup
step. There is no database and no second server.

The skill offers two run modes:

| Mode | Where the server runs | Who produces the manifest | Change to the project |
|------|-----------------------|---------------------------|-----------------------|
| **`docker`** (default) | the Hierograph container, project and `~/.m2` mounted **read-only** | the container itself, with its baked-in Maven and plugin | **none** |
| **`jvm`** | a local Java process | the host, with the `hierograph:manifest` goal (or a POM binding) | `.hierograph/` in the build root |

```
docker:  IDE / mvn on the host ──► target/classes ══ro══► container: manifest, regenerate, scan ──► MCP
jvm:     mvn …:manifest ──► .hierograph/manifest.json ──► java -jar server (scan, watch, regenerate) ──► MCP
```

In both modes the **host keeps compiling** as usual (IDE or Maven); Hierograph never compiles.

**Open by setting expectations:** before Step 1, tell the user this is a one-time setup that takes
a few minutes (a build, plus an image pull or a ~60 MB server download on first use) and that you'll
report progress at each stage.

## Supported projects

| Build | Supported | Detect by |
|-------|-----------|-----------|
| **Maven** | yes | `pom.xml` at the build root |
| **Gradle** | **not yet** | `settings.gradle(.kts)` / `build.gradle(.kts)`, no `pom.xml` |

For a Gradle project, stop and tell the user that Hierograph supports Maven builds only for now;
a Gradle producer is planned. Do not improvise a manifest by hand.

## Parameters — resolve these first, then echo them back to the user

| Parameter | Default | Notes |
|-----------|---------|-------|
| `HIEROGRAPH_VERSION` | latest **release** | Version of the image, the server jar and the Maven plugin (released together). Determined in Step 1. Must be **0.2.0 or later** (earlier releases are a retired generation). Never a `-SNAPSHOT`. |
| `RUN_MODE` | `docker` | `docker` or `jvm` (see above). Asked in Step 1. |
| `BUILD_ROOT` | the current project directory | The directory holding the root `pom.xml` — where Maven runs and, in `jvm` mode, where `.hierograph/` is written. Use its **absolute** path in Docker mounts. |
| `MAVEN` | `./mvnw` if `BUILD_ROOT/mvnw` exists, else `mvn` | The host's Maven, for the host build (both modes) and the manifest (`jvm`). |
| `PROFILES` | none | Profiles the user's normal build activates (e.g. one that adds modules). Pass them to every Maven command here — in `docker` mode via `-e HIEROGRAPH_MAVEN_ARGS=-P<PROFILES>` — or those modules are missing from the graph. Ask if the root POM has `<profiles>` with `<modules>`. |
| `JVM_SETUP` | `manifest` | `jvm` mode only: `manifest` (the `hierograph:manifest` goal, no POM change) or `pom` (bind `create-descriptor` in the POM). Asked in Step 3J. |
| `PROJECT` | the root `artifactId` | `docker` mode: names the state volume (`hierograph-PROJECT`). |
| `MCP_PORT` | `8080` | Hierograph MCP HTTP endpoint: `http://localhost:<MCP_PORT>/mcp`. |

## The procedure

### Run commands so they don't stall on approval

Run every command below as a **single, plain invocation, exactly as written**. The harness cannot
statically verify commands with shell expansions or chaining, so those force a permission prompt
every time and stall the setup. Do **not**:

- use expansions or command substitution — `$(...)`, `` `…` ``, `$VAR`, `~`;
- chain with `;`, `&&` or `||`, or pipe into `tail` / `grep` / `head` / `sed` to trim output;
- pass quoted template arguments such as `docker ps --format '{{.Names}}'`;
- write polling loops (`for … sleep`, `while … sleep`).

**Inspect files with the file tools** (Read, Glob), not with `cat` / `ls` / `head`. Reserve Bash for
what only a shell can do — builds, downloads, containers, servers, port checks — each as its own
command. Run the action, then **verify in a separate step**. Long output is fine; let it scroll.

### Keep the user informed

- **Track the pipeline as a task list** — one task per step, flipped to in-progress / completed as
  you go.
- **Announce slow or background steps** before they run, with the rough wait (the first build
  downloads plugins; the image pull or the server jar).
- **Confirm the server with concrete facts** — the port it listens on and what it loaded (modules,
  root children), not just "started".
- **On failure, show the actual error and the step it belongs to** — don't retry silently.

### 1. Preflight

- Confirm the shape with the file tools: `BUILD_ROOT/pom.xml` exists (otherwise see *Supported
  projects*). Note whether `BUILD_ROOT/mvnw` exists (→ `MAVEN`) and whether the root POM declares
  profiles that add modules (→ ask about `PROFILES`).
- **Determine `HIEROGRAPH_VERSION`** from Maven Central's metadata for the plugin, as one plain
  command, and read the `<release>` element from the output:
  ```bash
  curl -s https://repo1.maven.org/maven2/io/hierograph/hierograph-maven-plugin/maven-metadata.xml
  ```
  - If the release is **0.2.0 or later**, use it.
  - If the request returns 404 or the release is older than 0.2.0, **stop**: no released version
    supports this setup yet. Tell the user; do not fall back to a `-SNAPSHOT`.
  - If Maven Central is unreachable, ask the user for the version.
- **Choose `RUN_MODE`.** Recommend `docker`: no change to the project, no Hierograph command on the
  host, POM edits handled inside the container. Check `docker version`; if Docker is not available,
  or the user prefers a local process (e.g. for native file watching), use `jvm`.
- **`jvm` only:** `java -version` → **21+** (the server needs it; the project itself may build with any
  JDK). If not, stop and tell the user — or offer `docker`.

### 2. Make sure the build is green

Hierograph analyzes compiled bytecode, and the host produces it in both modes. Run the user's normal
build once from `BUILD_ROOT`, with `PROFILES`, far enough to compile main **and** test classes — e.g.
`mvn -B test-compile` or `mvn -B install -DskipTests` (`./mvnw` with a wrapper). A failing build must
be fixed first; don't continue on a red build.

### 3D. Start the container (`RUN_MODE=docker`)

Announce the start (first run pulls the image). Mount the build root and the user's Maven directory
**read-only**, and a named volume for the container's state; run it detached, without `--rm`, so it
can be restarted later (replace `ABSOLUTE_BUILD_ROOT`, `ABSOLUTE_HOME`, `PROJECT` and `VERSION`; add
`-e HIEROGRAPH_MAVEN_ARGS=-P<PROFILES>` when the build needs profiles):

```bash
docker run -d --name hierograph-mcp -p 8080:8080 \
  -v ABSOLUTE_BUILD_ROOT:/workspace:ro \
  -v ABSOLUTE_HOME/.m2:/m2:ro \
  -v hierograph-PROJECT:/hierograph \
  ghcr.io/code-kontor/hierograph-mcp-server:VERSION
```

- **On its first start the container has no manifest and generates one** with the Maven and
  Hierograph plugin baked into the image — without compiling and without writing into the project.
  Dependencies come from the mounted `~/.m2`; only missing ones are downloaded into the container's
  own repository. `/m2/settings.xml` is used when present.
- **The state volume** keeps the manifest, the snapshot and downloaded dependencies across restarts.
  It is optional; without it, every start regenerates the manifest first (seconds).
- Watching uses polling, which works across the mount.
- If the pull fails with `unauthorized`, the image may need `docker login ghcr.io`.
- Nothing needs to go into the project's `.gitignore`: the container writes nothing there.

The log (`docker logs hierograph-mcp`) shows `No manifest yet; generating it` and
`Regeneration finished` before the readiness lines in Step 4. Continue with **Step 4**.

### 3J. Produce the manifest and start the server (`RUN_MODE=jvm`)

**Produce the manifest — ask the user (`JVM_SETUP`).**

**`manifest` (default) — no POM change.** One goal, run from `BUILD_ROOT`:

```bash
mvn -B io.hierograph:hierograph-maven-plugin:VERSION:manifest
```

(`./mvnw` with a wrapper; add `-P<PROFILES>` when needed.) It needs no lifecycle phase, compiles
nothing and writes only `.hierograph/manifest.json`. The manifest records this very command as its
regeneration command, so the server can rerun it after a POM edit (below).

**`pom` — bind the plugin in the build**, for teams that want the manifest kept current by every
build. Add this to the `<build><plugins>` of the POM **every module inherits from** — usually the root
POM; show the edit and get confirmation before applying it:

```xml
<plugin>
  <groupId>io.hierograph</groupId>
  <artifactId>hierograph-maven-plugin</artifactId>
  <version>HIEROGRAPH_VERSION</version>
  <executions>
    <execution>
      <goals>
        <goal>create-descriptor</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

Then run `mvn -B process-classes` from `BUILD_ROOT`. From now on every build reaching
`process-classes` keeps the manifest current. A module that does not inherit the binding is missing
from the graph.

In both cases, **Read `BUILD_ROOT/.hierograph/manifest.json`** and check: `schemaVersion` is `2`;
`modules` lists every module the user expects (a missing one usually means a missing profile); the
`mainOutputDirs` point at existing `target/classes` directories. Then **suggest adding `.hierograph/`
to the project's `.gitignore`** (manifest, snapshot, logs, the server jar, runtime views) — an edit to
the user's file, so ask. Exactly `.hierograph/`, not a pattern like `.hierograph*`: project views live
beside it in `.hierograph-views/` and are versioned.

**Start the server.** Download it once into the project's `.hierograph/`, as one plain command
(replace `VERSION`):

```bash
curl -fL --create-dirs -o .hierograph/server/io.hierograph.server-VERSION-exec.jar https://repo1.maven.org/maven2/io/hierograph/io.hierograph.server/VERSION/io.hierograph.server-VERSION-exec.jar
```

Then start it from `BUILD_ROOT` **in the background**:

```bash
java -jar .hierograph/server/io.hierograph.server-VERSION-exec.jar --server.port=8080 --hierograph.graph.watch.build-files=regenerate --hierograph.graph.watch.mode=native
```

- `build-files=regenerate`: after a POM edit the server reruns the manifest's own regeneration
  command — the `manifest` goal, or `process-classes` with the POM binding. Leave it out to get
  `build_state: "stale"` reports instead and run the command yourself.
- `watch.mode=native`: event-driven watching, the faster choice on a local JVM.

### 4. Wait for readiness

A single check, repeated as its own step if needed: `nc -z localhost 8080`. Then read the server
output (`docker logs hierograph-mcp` in `docker` mode) and confirm these lines:

- `Scanned N of M declared artifact(s) from manifest …` — M counts each module's main output and,
  with test scanning on (the default), its test output; modules without tests have no compiled test
  output, so N is usually smaller than M. Every module should have its main output among the N (a
  declared output that is not compiled yet is watched and scanned once it appears);
- `Hierarchical graph created with … root children` and the node counts by kind;
- `Watching … module(s) and … build file(s) for changes`.

### 5. Register the server with the client

**Claude Code:**

```bash
claude mcp add hierograph --transport http http://localhost:8080/mcp
```

**Other JSON-config clients** (Claude Desktop, Cursor) — give the user this to add:

```json
{
  "mcpServers": {
    "hierograph": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

In Claude Code the `hierograph` tools are **not callable in the session that ran `claude mcp
add`**, even when `claude mcp list` reports the server as connected. They load in a **new
session** (or after reconnecting via `/mcp`).

### 6. Verify

- **In this session:** the readiness check and log lines from Step 4 prove build → manifest →
  server end to end. Don't hand-roll the MCP JSON-RPC handshake with `curl`.
- **After reconnecting:** call `graph_overview`. It returns `stats` (node counts by kind), the
  top-level `hierarchy` — one entry per module — and `build_state` with `status: "current"` and
  `build_tool: "maven"`.

Report what is running (mode, server or container, port, how to restart it), that a new session is
needed before the tools are usable, and how the graph stays current (below).

### 7. Optional: a multi-module view

If the reactor has aggregators below the root — POMs that list `<modules>` themselves — offer a
**view** that groups the modules by them. With it, the analysis skills answer "by multi-module"
with one `view = "multi-modules"` instead of collecting the membership by hand. Skip this step for
a flat reactor, and skip it unless the user wants it.

Derive the document from the POMs, never from module names:

```bash
python3 -I <this skill's dir>/scripts/multimodule_view.py "$BUILD_ROOT" [--profile <id> ...] > <scratch dir>/multi-modules.json
```

It walks `<modules>` recursively (pass the `PROFILES` that add modules), makes one group per
aggregator below the root, named by its `artifactId`, with its modules as members by GAV
(`groupId:artifactId:**`, test artifacts included; `--main-only` leaves them out); nested
aggregators become nested groups, and modules the root lists directly stay ungrouped. Show the
groups to the user, then let them choose where the view lives:

| Where | How | Note |
|-------|-----|------|
| in the project, versioned | save the document as `BUILD_ROOT/.hierograph-views/multi-modules.json` and commit it | the server reads it in both modes; in `docker` mode this is the one change to the project, so ask first |
| on the server only | after reconnecting, `define_view(document = <the JSON>)` | kept in the server's state (the state volume in `docker` mode); the project is untouched |

The tools are not available in this session, so the server-only variant happens in the next one;
hand the document over for it. In the next session, `graph_overview(view = "multi-modules")`
shows the groups and the view's report: zero errors means every module was placed. A
`SELECTOR_MATCHES_NOTHING` warning names a module without compiled output, which is not in the
graph.

## Keeping the graph current

There is no rescan step.

- **Code changes:** any compile on the host — Maven or the IDE — rewrites `target/classes`; the
  server rescans the changed modules within a second or two.
- **Module or dependency changes (POM edits):** the server regenerates the manifest itself — inside
  the container in `docker` mode, on the host with `build-files=regenerate` in `jvm` mode — and
  applies it as a diff. Without `regenerate` (`jvm` mode), `graph_overview` / `reload_graph` report
  `build_state: "stale"` until the manifest command or a build reaching `process-classes` reruns.
- **Something the watcher can't see** (a profile activated in `settings.xml`, an updated SNAPSHOT
  dependency): rebuild, then call the `reload_graph` tool.
- **After a restart:** `docker start hierograph-mcp` (the state volume keeps the manifest), or start
  the server again as in Step 3J.

## Handing off

Once verified, point the user at the analysis skills — **hierograph-structure** (dependency
structure, DSM, layering, cycles, and views by multi-module, layer or context) and
**hierograph-extract** (plan a module extraction, projected as a restructuring view) — or let
them ask directly, e.g. *"use hierograph: what's the most fragile coupling in this codebase?"* If the
AI starts reading source files instead of querying, nudge it with **"use hierograph"**.

## Common failure modes

| Symptom | Cause | Fix |
|---------|-------|-----|
| Container exits with `no pom.xml in /workspace` | The mounted directory isn't the build root | Mount the directory holding the root `pom.xml` at `/workspace` |
| `build_state: "regeneration-failed"` naming a missing artifact | A dependency is neither in `~/.m2` nor downloadable (offline, or credentials the container can't see) | Build once on the host so `~/.m2` has it; mount `settings.xml` at `/m2/settings.xml`; `reload_graph` retries |
| `build_state: "regeneration-failed"` with a build error | A POM is broken; `message` holds the end of Maven's output | Fix the POM; the next edit or `reload_graph` retries |
| `Manifest not found: …/.hierograph/manifest.json` (`jvm`) | Step 3J's manifest wasn't produced, or the server was started outside `BUILD_ROOT` | Produce the manifest; start the server from `BUILD_ROOT` |
| `Manifest … declares no module output directory` (`jvm`) | The manifest comes from a plugin older than the server (schema version 1) | Use the same `HIEROGRAPH_VERSION` for plugin and server; produce the manifest again |
| `No module of manifest … is compiled yet` | The host build didn't compile | Run the build (Step 2); the server keeps watching and builds the graph when classes appear |
| A module is missing from `graph_overview` | A profile wasn't passed, or (`pom` setup) the module doesn't inherit the binding | Pass `PROFILES` (`HIEROGRAPH_MAVEN_ARGS` in `docker` mode); use the `manifest` goal |
| `build_state: "stale"` (`jvm` without `regenerate`) | A POM changed after the last manifest | Rerun the manifest command, or a build reaching `process-classes` with the POM binding |
| Port 8080 in use | Another process | Choose another `MCP_PORT` (`-p <port>:8080` / `--server.port=…`) and register that URL |
| `docker run` fails with `name … already in use` | A `hierograph-mcp` container exists from an earlier setup | `docker start hierograph-mcp`, or `docker stop` and remove it before a fresh run |
