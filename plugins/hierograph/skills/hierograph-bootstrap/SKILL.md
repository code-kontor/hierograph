---
name: hierograph-bootstrap
description: Set a Maven project up for architectural analysis with the Hierograph MCP server — hook the Hierograph Maven plugin into the build so it writes `.hierograph/manifest.json`, start the Hierograph MCP server on that manifest (local JVM by default, Docker as alternative), and register the server with the MCP client. Trigger when the user says "bootstrap hierograph", "set up hierograph for this project", "get this project ready for hierograph", "wire up the hierograph MCP server", or "I want to analyze this codebase's architecture" (and hierograph is not yet running). Do NOT use once the hierograph MCP tools are already available — then just query them (see hierograph-structure / hierograph-extract); this skill only performs first-time setup.
allowed-tools:
  - Bash(java -version)
  - Bash(java -jar:*)
  - Bash(mvn:*)
  - Bash(./mvnw:*)
  - Bash(curl -s:*)
  - Bash(curl -fL:*)
  - Bash(nc -z:*)
  - Bash(docker run:*)
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
**target Maven project**. The pipeline it wires up:

```
target build ──(hierograph-maven-plugin: create-descriptor)──► .hierograph/manifest.json
                                                                    │
           Hierograph MCP server (manifest mode, watching) ◄────────┘ ──► MCP client
```

The Maven plugin records what only the build knows — the modules, their compiled-output
directories, their resolved classpaths, the POMs — in `.hierograph/manifest.json` at the build
root. The server reads that manifest, scans the compiled classes itself, and then **follows the
project on its own**: recompiled classes, added or removed modules and edited POMs reach the graph
without another setup step. There is no database and no second server.

**Open by setting expectations:** before Step 1, tell the user this is a one-time setup that takes
a few minutes (a build, and a ~60 MB server download on first use) and that you'll report progress
at each stage.

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
| `HIEROGRAPH_VERSION` | latest **release** | Version of the Maven plugin and the server. Determined in Step 1. Must be **0.2.0 or later** (earlier releases are the retired jQAssistant generation). Never a `-SNAPSHOT`. |
| `BUILD_ROOT` | the current project directory | The directory holding the root `pom.xml` — where Maven is launched and where `.hierograph/` is written. |
| `MAVEN` | `./mvnw` if `BUILD_ROOT/mvnw` exists, else `mvn` | Use the project's wrapper when it has one. |
| `PROFILES` | none | Profiles the user's normal build activates (e.g. one that adds modules). Pass them to every Maven command here, or those modules are missing from the graph. Ask if the root POM has `<profiles>` with `<modules>`. |
| `MAVEN_SETUP` | asked in Step 3 | `pom` (plugin bound in the POM) or `command` (no POM change). |
| `RUN_MODE` | asked in Step 1 | `jvm` (server as a local Java process, manifest from the host build) or `docker` (self-contained container: produces and regenerates the manifest itself; Steps 3 and 4 are skipped). |
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
what only a shell can do — builds, downloads, servers, port checks — each as its own command. Run
the action, then **verify in a separate step**. Long output is fine; let it scroll.

### Keep the user informed

- **Track the pipeline as a task list** — one task per step (preflight, Maven setup, manifest,
  server, register client, verify), flipped to in-progress / completed as you go.
- **Announce slow or background steps** before they run, with the rough wait (first build
  downloads plugins; the server jar is ~60 MB).
- **Confirm the server with concrete facts** — the port it listens on and what it loaded (modules,
  root children), not just "started".
- **On failure, show the actual error and the step it belongs to** — don't retry silently.

### 1. Preflight

- `java -version` → **21+**. If not, stop and tell the user.
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
- **Ask for `RUN_MODE`.** `jvm` (default): the server runs on the host and the host build produces the
  manifest (Steps 3–4). `docker`: a self-contained container with the project and `~/.m2` mounted
  read-only; it produces and regenerates the manifest itself, and the project needs no Hierograph
  configuration at all — Steps 3 and 4 are skipped. Docker must be available (`docker version`).

### 2. Make sure the build is green

Hierograph analyzes compiled bytecode. Run the user's normal build once from `BUILD_ROOT` (with
`PROFILES`), e.g. `./mvnw -B install -DskipTests` or `mvn -B install -DskipTests`. A failing build
must be fixed first; don't continue on a red build.

### 3. Hook the Maven plugin into the build — ask the user (`RUN_MODE=jvm` only)

Present both options and let the user choose (`MAVEN_SETUP`):

**`pom` — bind the plugin in the POM (keeps the manifest current automatically).** Add this to the
`<build><plugins>` of the POM **every module inherits from** — usually the root POM. If the modules
have different parents, add it to each of those parents; if a module inherits from none of them,
it will be missing from the graph (offer `command` instead).

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

The goal binds to `process-classes` by default, writes each module's descriptor and merges the
manifest. From then on, **every build reaching `process-classes`** (`process-classes`, `test`,
`install`, …) keeps the manifest current — including builds narrowed with `-pl`. `compile` alone
stops one phase earlier and does not. This changes the user's POM: show the edit and get
confirmation before applying it.

**`command` — no POM change.** The plugin is invoked by its full coordinates when needed:

```bash
mvn -B process-classes io.hierograph:hierograph-maven-plugin:HIEROGRAPH_VERSION:create-descriptor
```

(`./mvnw` instead of `mvn` when the project has a wrapper; add `-P<PROFILES>` when needed.) The
project stays untouched, but the manifest only changes when this command runs. Keep the
`process-classes` phase in front of the goal: without a lifecycle phase, Maven 3 resolves sibling
modules only from `~/.m2` and fails on uninstalled ones.

In both cases, **suggest adding `.hierograph/` to the project's `.gitignore`** (it holds the
generated manifest, the server's snapshot and its logs). It is an edit to the user's file — ask.

### 4. Produce the manifest (`RUN_MODE=jvm` only)

From `BUILD_ROOT`, with `PROFILES`:

- `pom`: `mvn -B process-classes` (or `./mvnw -B process-classes`).
- `command`: the command from Step 3.

Then **Read `BUILD_ROOT/.hierograph/manifest.json`** and check:

- `schemaVersion` is `2`, and `buildTool` / `regenerate` are present;
- `modules` lists the modules the user expects (all of them — a missing one usually means a
  missing profile, or a module outside the POM the plugin was added to);
- the `mainOutputDirs` of the modules point at existing `target/classes` directories.

Always run Maven from `BUILD_ROOT`: a build launched inside a module directory writes its own
manifest there and leaves the root manifest untouched.

### 5. Start the Hierograph MCP server (background)

Announce the start; run the server **in the background** and keep it running for the session.

**`RUN_MODE=jvm` (default).** Download the server once into the project's `.hierograph/`, as one
plain command (replace `VERSION`):

```bash
curl -fL --create-dirs -o .hierograph/server/io.hierograph.server-VERSION-exec.jar https://repo1.maven.org/maven2/io/hierograph/io.hierograph.server/VERSION/io.hierograph.server-VERSION-exec.jar
```

Then start it from `BUILD_ROOT` in the background:

```bash
java -jar .hierograph/server/io.hierograph.server-VERSION-exec.jar --server.port=8080
```

The defaults fit: manifest mode, reading `.hierograph/manifest.json` relative to the working
directory, test classes included, polling file watch, POM edits reported as stale. Options worth
offering:

- `--hierograph.graph.watch.mode=native` — event-driven watching (faster on a local JVM).
- `--hierograph.graph.watch.build-files=regenerate` — after a POM edit, the server runs the build
  that rewrites the manifest itself. With `MAVEN_SETUP=pom` the manifest's own command does this.
  With `command`, also pass the Step 3 command as
  `--hierograph.graph.regenerate.command="mvn -q -B process-classes io.hierograph:hierograph-maven-plugin:VERSION:create-descriptor"`,
  because the manifest's default command does not run an unbound plugin.

**`RUN_MODE=docker`.** Mount the project's build root and the user's Maven directory **read-only**
(replace `ABSOLUTE_BUILD_ROOT`, `ABSOLUTE_HOME` and `VERSION`; add `-e HIEROGRAPH_MAVEN_ARGS=-P<PROFILES>`
when the build needs profiles):

```bash
docker run -d --name hierograph-mcp -p 8080:8080 \
  -v ABSOLUTE_BUILD_ROOT:/workspace:ro \
  -v ABSOLUTE_HOME/.m2:/m2:ro \
  -v hierograph-PROJECT:/hierograph \
  ghcr.io/code-kontor/hierograph-mcp-server:VERSION
```

- **Everything else happens inside the container.** On its first start it has no manifest and
  generates one with the Maven and Hierograph plugin baked into the image — without compiling and
  without writing into the project. Dependencies come from the mounted `~/.m2` (read-only); only
  missing ones are downloaded into the container's own repository. `/m2/settings.xml` is used when
  present.
- **The host keeps compiling as usual** (IDE or Maven); the container picks the classes up through the
  mount. A module the host never compiled is watched until its classes appear.
- **POM edits regenerate the manifest inside the container** — no Maven run on the host needed.
- **The third mount is optional.** A named volume (one per project) keeps the manifest, the snapshot
  and downloaded dependencies across restarts; without it, every start regenerates (seconds).
- Watching uses polling, which works across the mount.
- If the pull fails with `unauthorized`, the image may need `docker login ghcr.io`.

The log shows `No manifest yet; generating it` and `Regeneration finished` before the lines below.

**Wait for readiness** with a single check, repeated as its own step if needed:
`nc -z localhost 8080`. Then read the server output (`docker logs hierograph-mcp` for Docker) and
confirm these lines:

- `Scanned N of M declared artifact(s) from manifest …` — M counts each module's main output and,
  with test scanning on (the default), its test output; modules without tests have no compiled test
  output, so N is usually smaller than M. Every module should have its main output among the N (a
  declared output that is not compiled yet is watched and scanned once it appears);
- `Hierarchical graph created with … root children` and the node counts by kind;
- `Watching … module(s) and … build file(s) for changes`.

### 6. Register the server with the client

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

### 7. Verify

- **In this session:** the readiness check and log lines from Step 5 prove build → manifest →
  server end to end. Don't hand-roll the MCP JSON-RPC handshake with `curl`.
- **After reconnecting:** call `graph_overview`. It returns `stats` (node counts by kind), the
  top-level `hierarchy` — one entry per module — and `build_state` with `status: "current"` and
  `build_tool: "maven"`.

Report what is running (server, port, run mode, how it was started), that a new session is needed
before the tools are usable, and how the graph stays current (below).

## Keeping the graph current

There is no rescan step.

- **Code changes:** any compile — Maven or the IDE — rewrites `target/classes`; the server rescans
  the changed modules within a second or two.
- **Module or dependency changes (POM edits):** with `RUN_MODE=docker` the container regenerates the
  manifest itself. With `RUN_MODE=jvm` the server notices the edited POM and reports
  `build_state: "stale"` in `graph_overview` / `reload_graph` until the manifest is rewritten: with
  `MAVEN_SETUP=pom`, the next build reaching `process-classes` does that; with `command`, rerun the
  Step 3 command. With `--hierograph.graph.watch.build-files=regenerate` the server runs it itself.
- **Something the watcher can't see** (a profile activated in `settings.xml`, an updated SNAPSHOT
  dependency): rebuild, then call the `reload_graph` tool.
- **After a server restart:** start it again as in Step 5; it loads the current manifest.

## Handing off

Once verified, point the user at the analysis skills — **hierograph-structure** (dependency
structure, DSM, layering, cycles) and **hierograph-extract** (plan a module extraction) — or let
them ask directly, e.g. *"use hierograph: what's the most fragile coupling in this codebase?"* If the
AI starts reading source files instead of querying, nudge it with **"use hierograph"**.

## Common failure modes

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Manifest not found: …/.hierograph/manifest.json` | Step 4 didn't run, or the server was started outside `BUILD_ROOT` | Produce the manifest; start the server from `BUILD_ROOT` (Docker: mount `BUILD_ROOT`) |
| `Manifest … declares no module output directory` | The manifest comes from a plugin older than the server (schema version 1), or no module has output directories | Use the same `HIEROGRAPH_VERSION` for plugin and server; rerun Step 4 |
| `No module of manifest … is compiled yet` | The build didn't compile | Run the build; the server keeps watching and builds the graph when classes appear |
| `Could not resolve dependencies … (absent)` with `command` | The goal ran without a lifecycle phase, or `-pl` without `-am` | Keep `process-classes` in front of the goal; add `-am` to narrowed builds |
| A module is missing from `graph_overview` | A profile wasn't passed, or the module doesn't inherit the plugin binding | Pass `PROFILES`; add the plugin to that module's parent, or use `command` |
| `build_state: "stale"` | A POM changed after the last manifest | Run the build (`pom`) or the Step 3 command (`command`) |
| `build_state: "regeneration-failed"` | The regeneration build failed; `message` holds the end of its output | Fix the build error; the next POM edit or `reload_graph` retries |
| Port 8080 in use | Another process | Choose another `MCP_PORT` (`--server.port=…` / `-p <port>:8080`) and register that URL |
| Container exits with `no pom.xml in /workspace` | The mounted directory isn't the build root | Mount the directory holding the root `pom.xml` at `/workspace` |
| Container: `build_state: "regeneration-failed"` naming a missing artifact | A dependency is neither in `~/.m2` nor downloadable (offline, or credentials the container can't see) | Build once on the host so `~/.m2` has it; mount `settings.xml` at `/m2/settings.xml` |
