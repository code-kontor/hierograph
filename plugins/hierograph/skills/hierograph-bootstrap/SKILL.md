---
name: hierograph-bootstrap
description: Set a Maven or Gradle project up for architectural analysis with the Hierograph MCP server and register the server with the MCP client. By default it starts the Hierograph Docker container with the project mounted read-only — for Maven (with ~/.m2 read-only) the container produces and regenerates the build manifest itself, no change to the project at all; for Gradle the host writes the manifest through an init script and the container serves it. Alternatively it runs the server as a local Java process on a manifest from the host build. Trigger when the user says "bootstrap hierograph", "set up hierograph for this project", "get this project ready for hierograph", "wire up the hierograph MCP server", or "I want to analyze this codebase's architecture" (and hierograph is not yet running). Do NOT use once the hierograph MCP tools are already available — then just query them (see hierograph-structure / hierograph-extract); this skill only performs first-time setup.
allowed-tools:
  - Bash(java -version)
  - Bash(java -jar:*)
  - Bash(java -Xmx2g -jar:*)
  - Bash(mvn:*)
  - Bash(./mvnw:*)
  - Bash(./gradlew:*)
  - Bash(gradle:*)
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
  - Bash(python3 -I:*)
  - Read
  - Write
  - Glob
---

# hierograph-bootstrap — set a project up for hierograph analysis

This skill performs the one-time setup that makes the Hierograph MCP tools available for a
**target Maven or Gradle project**. Hierograph works from the **build manifest** (`manifest.json`):
what only the build knows — the modules, their compiled-output directories, their resolved classpaths,
the build files. The server reads it, scans the compiled classes itself, and then **follows the project
on its own**: recompiled classes, added or removed modules and edited build files reach the graph
without another setup step. There is no database and no second server.

The skill offers two run modes:

| Mode | Where the server runs | Who produces the manifest | Change to the project |
|------|-----------------------|---------------------------|-----------------------|
| **`docker`** (default) | the Hierograph container, the project mounted **read-only** | Maven: the container itself, with its baked-in Maven and plugin (`~/.m2` mounted read-only). Gradle: the host, with the `hierographManifest` task | Maven: **none**. Gradle: `.hierograph/` in the build root |
| **`jvm`** | a local Java process | the host: the Maven `hierograph:manifest` goal (or a POM binding), or the Gradle `hierographManifest` task | `.hierograph/` in the build root |

```
docker, Maven:   IDE / mvn on the host ──► target/classes ══ro══► container: manifest, regenerate, scan ──► MCP
docker, Gradle:  build + hierographManifest on the host ──► classes, .hierograph/manifest.json ══ro══► container: scan ──► MCP
jvm:             manifest goal / task ──► .hierograph/manifest.json ──► java -jar server (scan, watch, regenerate) ──► MCP
```

A Gradle build never runs in the container: a Gradle build definition is a program that needs the
host's JDKs, repository credentials and init scripts, so the manifest is written where the classes
are compiled.

In both modes the **host keeps compiling** as usual (IDE, Maven or Gradle); Hierograph never compiles.

**Open by setting expectations:** before Step 1, tell the user this is a one-time setup that takes
a few minutes (a build, plus an image pull or a ~60 MB server download on first use) and that you'll
report progress at each stage.

## Supported projects

| Build | Supported | Detect by |
|-------|-----------|-----------|
| **Maven** | yes, Maven 3.9+ | `pom.xml` at the build root |
| **Gradle** | yes, Gradle **8.11+** | `settings.gradle(.kts)` or `build.gradle(.kts)` at the build root |
| both files | Maven, unless the user wants the Gradle build analysed (`BUILD_TOOL`) | |

Android and Kotlin Multiplatform projects are skipped by the Gradle plugin (no `java` source
sets); for such a build, tell the user only its plain JVM projects appear. Do not improvise a
manifest by hand.

## Parameters — resolve these first, then echo them back to the user

| Parameter | Default | Notes |
|-----------|---------|-------|
| `BUILD_TOOL` | detected | `maven` or `gradle`, from *Supported projects*. |
| `HIEROGRAPH_VERSION` | latest **release** | Version of the image, the server jar and the plugins (released together). Determined in Step 1. Must be **0.3.0 or later**: 0.2.0 lacks views, `server_info` and the per-workspace port; earlier releases are a retired generation. Never a `-SNAPSHOT`. |
| `RUN_MODE` | `docker` | `docker` or `jvm` (see above). Asked in Step 1. |
| `BUILD_ROOT` | the current project directory | The directory holding the root `pom.xml` / the settings script — where the build runs and where `.hierograph/` is written (`jvm` mode, and Gradle in both modes). Use its **absolute** path in Docker mounts. |
| `MAVEN` | `./mvnw` if `BUILD_ROOT/mvnw` exists, else `mvn` | Maven only: the host's Maven, for the host build (both modes) and the manifest (`jvm`). |
| `GRADLE` | `./gradlew` if `BUILD_ROOT/gradlew` exists, else `gradle` | Gradle only: the host's Gradle, for the host build and the manifest (both modes). |
| `PROFILES` | none | Maven only: profiles the user's normal build activates (e.g. one that adds modules). Pass them to every Maven command here — in `docker` mode via `-e HIEROGRAPH_MAVEN_ARGS=-P<PROFILES>` — or those modules are missing from the graph. Ask if the root POM has `<profiles>` with `<modules>`. |
| `GRADLE_ARGS` | none | Gradle only: properties the user's normal build needs to include all projects (`-P…`), when the settings script includes projects conditionally. Pass them to every Gradle command here. |
| `MANIFEST_SETUP` | `manifest` (Maven), `init-script` (Gradle) | How the host produces the manifest. Maven, `jvm` mode only: `manifest` (the `hierograph:manifest` goal, no POM change) or `pom` (bind `create-descriptor` in the POM); asked in Step 3J. Gradle, both modes: `init-script` (no change to the build) or `settings` (apply the plugin in the settings script); asked in Step 3G. |
| `PROJECT` | the root `artifactId` / `rootProject.name` | `docker` mode: names the state volume (`hierograph-PROJECT`). |
| `MCP_PORT` | `8765` | Port of the Hierograph MCP endpoint on the host: `http://localhost:<MCP_PORT>/mcp`. Keep the default unless the user names a port or 8765 is taken (Step 1). Use it in **every** command below. |

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

- Confirm the shape with the file tools (Glob): `BUILD_ROOT/pom.xml`, or
  `BUILD_ROOT/settings.gradle(.kts)` / `build.gradle(.kts)` → `BUILD_TOOL` (see *Supported
  projects*; with both, ask). Maven: note whether `BUILD_ROOT/mvnw` exists (→ `MAVEN`) and whether
  the root POM declares profiles that add modules (→ ask about `PROFILES`). Gradle: note whether
  `BUILD_ROOT/gradlew` exists (→ `GRADLE`), whether the settings script includes projects
  conditionally (→ ask about `GRADLE_ARGS`), and Read `gradle/wrapper/gradle-wrapper.properties`: the
  version in `distributionUrl` must be **8.11 or later**; if it is older, stop and tell the user.
- **Determine `HIEROGRAPH_VERSION`** from Maven Central's metadata for the plugin of `BUILD_TOOL`, as
  one plain command, and read the `<release>` element from the output:
  ```bash
  curl -s https://repo1.maven.org/maven2/io/hierograph/hierograph-maven-plugin/maven-metadata.xml
  ```
  ```bash
  curl -s https://repo1.maven.org/maven2/io/hierograph/hierograph-gradle-plugin/maven-metadata.xml
  ```
  - If the release is **0.3.0 or later**, use it.
  - If the request returns 404 or the release is older than 0.3.0, **stop**: no released version
    supports this setup yet. Tell the user; do not fall back to a `-SNAPSHOT` or an older release.
  - If Maven Central is unreachable, ask the user for the version.
- **Choose `RUN_MODE`.** Recommend `docker`: the server runs isolated, with the project read-only.
  For Maven there is no change to the project and no Hierograph command on the host; POM edits are
  handled inside the container. For Gradle the host writes the manifest (Step 3G), and after a
  build-file edit the user reruns that one command; `jvm` mode reruns it by itself. Check
  `docker version`; if Docker is not available, or the user prefers a local process (e.g. for native
  file watching or automatic regeneration), use `jvm`.
- **`jvm` only:** `java -version` → **21+** (the server needs it; the project itself may build with any
  JDK). If not, stop and tell the user — or offer `docker`.
- **Is a server already running for this project?** Never start a second one: two servers watch and
  scan the same workspace, and the second one holds another port.
  - `jvm`: Read `BUILD_ROOT/.hierograph/server.json`. If it exists, a server may be running at its
    `url`; check with `nc -z localhost <its port>`. If the port answers, skip to Step 5 with that URL.
    A file whose port doesn't answer is left over from a server that was killed; ignore it.
  - `docker`: `docker ps` lists a running `hierograph-mcp` container; if it is there, skip to Step 5.
- **Is `MCP_PORT` free?** `nc -z localhost MCP_PORT` must fail (nothing listening). If something
  answers, pick another port with the user (for example 8766) and use it as `MCP_PORT`.

### 2. Make sure the build is green

Hierograph analyzes compiled bytecode, and the host produces it in both modes. Run the user's normal
build once from `BUILD_ROOT`, far enough to compile main **and** test classes:

- Maven, with `PROFILES`: `mvn -B test-compile` or `mvn -B install -DskipTests` (`./mvnw` with a
  wrapper);
- Gradle, with `GRADLE_ARGS`: `./gradlew classes testClasses` (`gradle` without a wrapper).

A failing build must be fixed first; don't continue on a red build.

### 3G. Produce the Gradle manifest on the host (Gradle, both modes)

For a Gradle build the host writes the manifest, in both run modes. **Ask the user
(`MANIFEST_SETUP`).** For Maven, skip this step.

**`init-script` (default) — no change to the build.** Write this init script with the Write tool to
`BUILD_ROOT/.hierograph/hierograph.init.gradle` (replace `VERSION`):

```groovy
initscript {
    repositories { mavenCentral() }
    dependencies { classpath "io.hierograph:hierograph-gradle-plugin:VERSION" }
}
beforeSettings { settings ->
    settings.pluginManager.apply(io.hierograph.gradle.plugin.HierographSettingsPlugin)
}
```

Then run from `BUILD_ROOT` (add `GRADLE_ARGS` when needed):

```bash
./gradlew -q --init-script .hierograph/hierograph.init.gradle hierographManifest
```

The task compiles nothing and writes only `.hierograph/manifest.json`. The manifest records this
command, with the init script's absolute path, as its regeneration command.

**`settings` — apply the plugin in the build.** Add to the settings script; show the edit and get
confirmation before applying it. `pluginManagement` must list `mavenCentral()`, where the plugin is
published (merge with an existing `pluginManagement` block):

```kotlin
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
plugins {
    id("io.hierograph") version "HIEROGRAPH_VERSION"
}
```

Then run `./gradlew -q hierographManifest` from `BUILD_ROOT`. The task is not wired into the normal
build: in `jvm` mode the server reruns it after a build-file edit, in `docker` mode the user does
(below).

**Read `BUILD_ROOT/.hierograph/manifest.json`** and check it as in Step 3J (*In all cases*), including
the `.gitignore` suggestion. Then continue with **Step 3D** or **Step 3J**.

### 3D. Start the container (`RUN_MODE=docker`)

Announce the start (first run pulls the image). Mount the build root **read-only** (for Maven also
the user's Maven directory), and a named volume for the container's state; run it detached, without
`--rm`, so it can be restarted later (replace `ABSOLUTE_BUILD_ROOT`, `ABSOLUTE_HOME`, `PROJECT` and
`VERSION`).

**Maven** — add `-e HIEROGRAPH_MAVEN_ARGS=-P<PROFILES>` when the build needs profiles:

```bash
docker run -d --name hierograph-mcp -p 127.0.0.1:MCP_PORT:8765 \
  -e HIEROGRAPH_URL=http://localhost:MCP_PORT/mcp \
  -e HIEROGRAPH_WORKSPACE=ABSOLUTE_BUILD_ROOT \
  -v ABSOLUTE_BUILD_ROOT:/workspace:ro \
  -v ABSOLUTE_HOME/.m2:/m2:ro \
  -v hierograph-PROJECT:/hierograph \
  ghcr.io/code-kontor/hierograph-mcp-server:VERSION
```

**Gradle** — the manifest from Step 3G must exist; add `-e HIEROGRAPH_BUILD_TOOL=gradle` when the
build root also holds a `pom.xml`:

```bash
docker run -d --name hierograph-mcp -p 127.0.0.1:MCP_PORT:8765 \
  -e HIEROGRAPH_URL=http://localhost:MCP_PORT/mcp \
  -e HIEROGRAPH_WORKSPACE=ABSOLUTE_BUILD_ROOT \
  -v ABSOLUTE_BUILD_ROOT:/workspace:ro \
  -v hierograph-PROJECT:/hierograph \
  ghcr.io/code-kontor/hierograph-mcp-server:VERSION
```

- **Maven: on its first start the container has no manifest and generates one** with the Maven and
  Hierograph plugin baked into the image — without compiling and without writing into the project.
  Dependencies come from the mounted `~/.m2`; only missing ones are downloaded into the container's
  own repository. `/m2/settings.xml` is used when present. The log (`docker logs hierograph-mcp`)
  shows `No manifest yet; generating it` and `Regeneration finished` before the readiness lines in
  Step 4.
- **Gradle: the container reads the host's manifest** from `/workspace/.hierograph/manifest.json`
  and maps its host paths to `/workspace`. It never runs Gradle; without a manifest it exits, naming
  the command from Step 3G.
- **The state volume** keeps the snapshot (and for Maven the manifest and downloaded dependencies)
  across restarts. It is optional; without it, a Maven container regenerates the manifest first
  (seconds).
- Watching uses polling, which works across the mount.
- **Localhost only:** `-p 127.0.0.1:MCP_PORT:8765` publishes the port on the host's loopback
  interface, so other machines on the network can't reach the server — it serves the code base
  without authentication. Always keep the `127.0.0.1:` prefix; a plain `-p MCP_PORT:8765` listens on
  every interface of the host. (Inside the container the server listens on all interfaces, which
  Docker's port forwarding needs; the host side of the mapping is what keeps it local.)
- **Diagnostic endpoints are off:** `/actuator/env`, `/actuator/threaddump` and `/actuator/heapdump`
  answer 404 unless enabled. Don't add `-e HIEROGRAPH_ACTUATOR_DIAGNOSTICS_ENABLED=true` to a normal
  setup; see *Diagnosing the server* for when and how to switch them on.
- Inside the container the server always listens on 8765; `MCP_PORT` is only the host side of the
  mapping. `HIEROGRAPH_URL` and `HIEROGRAPH_WORKSPACE` make `server_info` report the URL and the
  project path as the host sees them.
- If the pull fails with `unauthorized`, the image may need `docker login ghcr.io`.
- For Maven nothing needs to go into the project's `.gitignore`: the container writes nothing there.

Continue with **Step 4**.

### 3J. Produce the manifest and start the server (`RUN_MODE=jvm`)

**Produce the manifest.** Gradle: as in **Step 3G**, if not done yet. Maven: ask the user
(`MANIFEST_SETUP`).

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

In all cases, **Read `BUILD_ROOT/.hierograph/manifest.json`** and check: `schemaVersion` is `2`;
`modules` lists every module the user expects (a missing one usually means missing `PROFILES` or
`GRADLE_ARGS`); the `mainOutputDirs` point at existing directories (`target/classes`, or
`build/classes/java/main` and `build/classes/kotlin/main`). Then **suggest adding `.hierograph/`
to the project's `.gitignore`** (manifest, snapshot, logs, the server jar, runtime views) — an edit to
the user's file, so ask. Exactly `.hierograph/`, not a pattern like `.hierograph*`: project views live
beside it in `.hierograph-views/` and are versioned.

**Record the port.** Write `BUILD_ROOT/.hierograph/server.properties` with the Write tool, so every
later start uses the same port without a command-line argument:

```properties
# Settings for the Hierograph server of this workspace, read on every start.
server.port=MCP_PORT
```

**Start the server.** Download it once into the project's `.hierograph/`, as one plain command
(replace `VERSION`):

```bash
curl -fL --create-dirs -o .hierograph/server/io.hierograph.server-VERSION-exec.jar https://repo1.maven.org/maven2/io/hierograph/io.hierograph.server/VERSION/io.hierograph.server-VERSION-exec.jar
```

Then start it from `BUILD_ROOT` **in the background**:

```bash
java -Xmx2g -jar .hierograph/server/io.hierograph.server-VERSION-exec.jar --hierograph.graph.watch.build-files=regenerate --hierograph.graph.watch.mode=native
```

The port comes from `server.properties`. A server that finds another one running for the workspace
refuses to start and names its URL; use that URL in Step 5 instead.

- `build-files=regenerate`: after a build-file edit the server reruns the manifest's own
  regeneration command — the `manifest` goal, `process-classes` with the POM binding, or the
  Gradle task. Leave it out to get
  `build_state: "stale"` reports instead and run the command yourself.
- `-Xmx2g`: caps the heap at 2 GB. Without it the JVM may grow to a quarter of the machine's
  memory, although the graph needs far less: a 157-module reactor with about 215,000 nodes holds
  about 515 MB live and stays under 1.6 GB of heap during a build. For a larger reactor, raise it.
- `watch.mode=native`: event-driven watching, the faster choice on a local JVM.
- **Localhost only:** the server listens on `127.0.0.1` by default (`server.address`), so other
  machines on the network can't reach it — it serves the code base without authentication. Don't
  pass `--server.address` or put it in `server.properties` unless the user explicitly asks to expose
  the server.
- **Diagnostic endpoints are off:** `/actuator/env`, `/actuator/threaddump` and `/actuator/heapdump`
  answer 404 unless enabled. Don't pass `--hierograph.actuator.diagnostics.enabled=true` in a normal
  setup; see *Diagnosing the server* for when and how to switch them on.

### 4. Wait for readiness

A single check, repeated as its own step if needed:

- `jvm`: Read `BUILD_ROOT/.hierograph/server.json`. The server writes it once it is ready to serve;
  its `url` is what Step 5 registers.
- `docker`: `nc -z localhost MCP_PORT`.

Then read the server output (`docker logs hierograph-mcp` in `docker` mode) and confirm these lines:

- `Hierograph MCP ready: http://localhost:…/mcp (workspace …, N of M artifacts, K nodes)` — the URL
  clients connect to, and what the server serves;

- `Scanned N of M declared artifact(s) from manifest …` — M counts each module's main output and,
  with test scanning on (the default), its test output; modules without tests have no compiled test
  output, so N is usually smaller than M. Every module should have its main output among the N (a
  declared output that is not compiled yet is watched and scanned once it appears);
- `Hierarchical graph created with … root children` and the node counts by kind;
- `Watching … module(s) and … build file(s) for changes`.

### 5. Register the server with the client

**Claude Code:**

```bash
claude mcp add hierograph --transport http http://localhost:MCP_PORT/mcp
```

**Other JSON-config clients** (Claude Desktop, Cursor) — give the user this to add:

```json
{
  "mcpServers": {
    "hierograph": {
      "type": "http",
      "url": "http://localhost:MCP_PORT/mcp"
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
- **After reconnecting:** call `server_info`. Its `workspace` is `BUILD_ROOT`, its `version` is
  `HIEROGRAPH_VERSION`, and `artifacts.not_compiled` lists the outputs that are declared but not
  compiled (usually test outputs of modules without tests). Then call `graph_overview`. It returns
  `stats` (node counts by kind), the top-level `hierarchy` — one entry per module — and
  `build_state` with `status: "current"` and `build_tool: "maven"` or `"gradle"`.

Report what is running (mode, server or container, port, how to restart it), that a new session is
needed before the tools are usable, and how the graph stays current (below).

### 7. Optional: a multi-module view

Maven only. If the reactor has aggregators below the root — POMs that list `<modules>` themselves — offer a
**view** that groups the modules by them. With it, the analysis skills answer "by multi-module"
with one `view = "multi-modules"` instead of collecting the membership by hand. Skip this step for
a flat reactor, and skip it unless the user wants it.

Derive the document from the POMs, never from module names:

```bash
python3 -I <this skill's dir>/scripts/multimodule_view.py ABSOLUTE_BUILD_ROOT [--profile <id> ...] > <scratch dir>/multi-modules.json
```

It walks `<modules>` recursively (pass the `PROFILES` that add modules), makes one group per
aggregator below the root, named by its `artifactId`, with its modules as members by GAV
(`groupId:artifactId:**`, test artifacts included; `--main-only` leaves them out); nested
aggregators become nested groups, and modules the root lists directly stay ungrouped. Show the
groups to the user, then save the document as a **project view**,
`BUILD_ROOT/.hierograph-views/multi-modules.json`: the standard place for a view, versioned with the
code and read by the server in both modes. In `docker` mode this is the one change to the project, so
ask first; committing the file is the user's to decide.

If the user wants no change to the project, there is no multi-modules view: don't fall back to
`define_view`. A view defined at runtime is an internal draft that the server deletes when it
restarts, so it would vanish with the next container or server start.

Pass on every warning the script prints. It resolves `${...}` references in a groupId or artifactId
from the POMs' properties; a module whose coordinates it cannot resolve is left out of the view, and
the warning names it.

In the next session, `graph_overview(view = "multi-modules")`
shows the groups and the view's report. Zero errors means no group conflicts; it does not mean
every module was placed. A `SELECTOR_MATCHES_NOTHING` warning names a selector that found no
module: a module without compiled output, which is not in the graph, or coordinates that differ
from the POM's (check the module's `groupId:artifactId` with `find_node`). Every module is placed
when the report has no errors and no such warning, and the script printed no warning.

## Keeping the graph current

There is no rescan step.

- **Code changes:** any compile on the host — Maven, Gradle or the IDE — rewrites the compiled
  classes; the server rescans the changed modules within a second or two.
- **Module or dependency changes (build-file edits):** the server regenerates the manifest itself —
  inside the container for Maven in `docker` mode, on the host with `build-files=regenerate` in `jvm`
  mode — and applies it as a diff. For Gradle in `docker` mode, and without `regenerate` in `jvm`
  mode, `graph_overview` / `reload_graph` report `build_state: "stale"` until the manifest command
  (or a Maven build reaching `process-classes`) reruns on the host; the server applies the rewritten
  manifest by itself. Don't call `reload_graph` after a build-file edit: while the server regenerates
  and applies the manifest, `server_info` shows `build_state: "regenerating"` and a `graph_update`
  other than `idle`, and a reload during that time only waits for the update in progress.
- **Something the watcher can't see** (a profile activated in `settings.xml`, an updated SNAPSHOT
  dependency, a new file in `buildSrc`): rebuild, then call the `reload_graph` tool.
- **After a restart:** `docker start hierograph-mcp` (the state volume keeps the manifest), or start
  the server again as in Step 3J; it takes its port from `.hierograph/server.properties` again.

## Diagnosing the server

The server serves Spring Boot Actuator endpoints under `/actuator` on `MCP_PORT`, reachable from the
local machine only:

| Endpoint | Default | Content |
|----------|---------|---------|
| `health`, `info` | on | status and build info |
| `metrics` | on | JVM memory, GC, threads, CPU, e.g. `http://localhost:MCP_PORT/actuator/metrics/jvm.memory.used?tag=area:heap` |
| `env` | **off** | the configuration properties, all values masked |
| `threaddump` | **off** | the server's threads with their stacks |
| `heapdump` | **off** | a heap dump (`.hprof`) to download |

**The diagnostic endpoints `env`, `threaddump` and `heapdump` are off by default** and answer 404. A
heap dump holds everything the server has loaded — the scanned code base included — and is as large
as the used heap (over 1 GB for a large reactor; the JVM pauses while writing it). Switch them on only
when the user asks to diagnose the server (memory, a hang), and off again afterwards. For memory use,
`metrics` is usually enough and needs no switch.

**Enable them** with `hierograph.actuator.diagnostics.enabled=true`, then restart the server:

- `docker`: the switch is an environment variable, and a container's environment is fixed at
  creation, so recreate it — `docker stop hierograph-mcp`, `docker rm hierograph-mcp`, then the
  `docker run` of Step 3D with `-e HIEROGRAPH_ACTUATOR_DIAGNOSTICS_ENABLED=true` added. The state
  volume keeps the manifest and the snapshot. To switch them off, recreate it without the variable.
- `jvm`: stop the server and start it as in Step 3J with
  `--hierograph.actuator.diagnostics.enabled=true` added. To switch them off, start it without the
  argument. (Putting the line `hierograph.actuator.diagnostics.enabled=true` into
  `.hierograph/server.properties` keeps them on across restarts; only do that when the user asks.)

Check with `curl -s -o /dev/null -w "%{http_code}" http://localhost:MCP_PORT/actuator/threaddump`:
`200` when on, `404` when off. Download a heap dump with
`curl -o heap.hprof http://localhost:MCP_PORT/actuator/heapdump` into the scratch directory, never
into the project.

## Handing off

Once verified, point the user at the analysis skills — **hierograph-structure** (dependency
structure, DSM, layering, cycles, and views by multi-module, layer or context) and
**hierograph-extract** (plan a module extraction, projected as a restructuring view) — or let
them ask directly, e.g. *"use hierograph: what's the most fragile coupling in this codebase?"* If the
AI starts reading source files instead of querying, nudge it with **"use hierograph"**.

## Common failure modes

| Symptom | Cause | Fix |
|---------|-------|-----|
| Container exits with `no pom.xml and no settings.gradle(.kts) or build.gradle(.kts) in /workspace` | The mounted directory isn't the build root | Mount the directory holding the root `pom.xml` / settings script at `/workspace` |
| Container exits with `no manifest at /workspace/.hierograph/manifest.json` | A Gradle build whose manifest the host hasn't written | Run Step 3G, then `docker start hierograph-mcp` |
| The container analyses the Maven build of a project that has both | Maven wins by default | Recreate it with `-e HIEROGRAPH_BUILD_TOOL=gradle` |
| `build_state: "regeneration-failed"` naming a missing artifact | A dependency is neither in `~/.m2` nor downloadable (offline, or credentials the container can't see) | Build once on the host so `~/.m2` has it; mount `settings.xml` at `/m2/settings.xml`; `reload_graph` retries |
| `build_state: "regeneration-failed"` with a build error | A POM is broken; `message` holds the end of Maven's output | Fix the POM; the next edit or `reload_graph` retries |
| `Manifest not found: …/.hierograph/manifest.json` (`jvm`) | Step 3J's manifest wasn't produced, or the server was started outside `BUILD_ROOT` | Produce the manifest; start the server from `BUILD_ROOT` |
| `Manifest … declares no module output directory` (`jvm`) | The manifest comes from a plugin older than the server (schema version 1) | Use the same `HIEROGRAPH_VERSION` for plugin and server; produce the manifest again |
| `No module of manifest … is compiled yet` | The host build didn't compile | Run the build (Step 2); the server keeps watching and builds the graph when classes appear |
| A module is missing from `graph_overview` | A profile or Gradle property wasn't passed, (`pom` setup) the module doesn't inherit the binding, or (Gradle) it's an Android / Kotlin Multiplatform project | Pass `PROFILES` (`HIEROGRAPH_MAVEN_ARGS` in `docker` mode) or `GRADLE_ARGS` (to the Step 3G command); use the `manifest` goal |
| `build_state: "stale"` (Gradle in `docker`, or `jvm` without `regenerate`) | A build file changed after the last manifest | Rerun the manifest command on the host, or a build reaching `process-classes` with the POM binding |
| The client reports `ECONNREFUSED` | No server listens at the registered URL: it stopped, or it runs on another port | Read `BUILD_ROOT/.hierograph/server.json` (`jvm`) or `docker ps` (`docker`). If a server runs, re-register its `url`; if not, start it as in Step 3. Never start a second server while one runs |
| `Hierograph is already running for workspace …` | A server for this workspace runs already | Register the URL the message names; stop the running server first only if it must be replaced |
| `MCP_PORT` in use at start | Another process holds the port | Choose another `MCP_PORT`: `jvm` — change `server.port` in `.hierograph/server.properties`; `docker` — recreate the container with `-p 127.0.0.1:<port>:8765` and `HIEROGRAPH_URL` on that port. Then re-register the URL |
| Another application of the project can't start because hierograph holds its port | `MCP_PORT` collides with a port the project needs | Stop the server, move it to another port as in the row above, restart it and re-register the URL |
| `/actuator/env`, `/actuator/threaddump` or `/actuator/heapdump` answers 404 | The diagnostic endpoints are off by default | Enable them as in *Diagnosing the server*, if the user wants them |
| `docker run` fails with `name … already in use` | A `hierograph-mcp` container exists from an earlier setup | `docker start hierograph-mcp`, or `docker stop` and remove it before a fresh run |
