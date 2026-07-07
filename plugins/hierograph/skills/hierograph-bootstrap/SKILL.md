---
name: hierograph-bootstrap
description: Set a JVM project up for architectural analysis with jQAssistant + the Hierograph MCP server — scan the project into an embedded Neo4j store, start the jQAssistant Bolt server and the Hierograph MCP server, and register the server with the MCP client. Handles the three project "shapes" — Maven, Gradle, and plain JARs / a classes directory (no build tool) — each via its own scan sub-skill. Trigger when the user says "bootstrap hierograph", "set up hierograph for this project", "get this project ready for hierograph", "set up jqassistant", "wire up the hierograph MCP server", "scan this project for dependency analysis", or "I want to analyze this codebase's architecture" (and hierograph is not yet running). Do NOT use once the hierograph MCP tools are already available — then just query them (see hierograph-dsm / hierograph-extract); this skill only performs first-time setup.
allowed-tools:
  - Bash(java -version)
  - Bash(mvn:*)
  - Bash(./mvnw:*)
  - Bash(gradle:*)
  - Bash(./gradlew:*)
  - Bash(nc -z:*)
  - Bash(curl:*)
  - Bash(sed:*)
  - Bash(docker run:*)
  - Bash(docker logs:*)
  - Bash(docker ps:*)
  - Bash(docker stop:*)
  - Bash(docker pull:*)
  - Bash(docker login:*)
  - Bash(docker version:*)
  - Bash(claude mcp add:*)
  - Bash(claude mcp list:*)
  - Read
---

# hierograph-bootstrap — set a project up for hierograph analysis

This skill performs the one-time setup that makes the Hierograph MCP tools available against a
**target JVM project**. It is the orchestrator: it handles the shared flow and delegates the hard,
shape-dependent part — the jQAssistant scan — to a per-shape sub-skill.

The pipeline you are wiring up:

```
Target bytecode → jQAssistant (scan) → Neo4j store → jQAssistant Bolt server
                                                          → Hierograph MCP server → this client
```

Two long-running servers are involved (the jQAssistant Bolt server and the Hierograph MCP server).
**Start each in the background** and keep it running for the rest of the session — do not block on
them.

**Open by setting expectations:** before Step 1, tell the user this is a one-time setup that takes
a few minutes (a build/scan plus a Docker image pull) and that you'll report progress at each
stage. See *Keep the user informed* below for how.

## Project shapes

The jQAssistant setup differs by how the project is built and where its bytecode lives. Detect the
shape in Step 2, then follow the matching sub-skill (a file in this skill's directory) for the scan:

| Shape | Detect by | Sub-skill |
|-------|-----------|-----------|
| **Maven** | `pom.xml` at the root / target module | [`scan-maven.md`](scan-maven.md) |
| **Gradle** | `build.gradle` or `build.gradle.kts` | [`scan-gradle.md`](scan-gradle.md) |
| **Plain JARs / classes** | No build tool, or you only have prebuilt `.jar`s / a `classes/` dir | [`scan-jars.md`](scan-jars.md) |

Each sub-skill takes you from bytecode to a **live jQAssistant Bolt server** on a populated store.
This orchestrator then wires that store into the Hierograph MCP server and your client.

## What every shape must produce (the scan invariant)

However the scan is run, it must satisfy these Hierograph requirements — the sub-skills all
implement them, but verify them if you deviate:

1. **Bytecode scanned** into an embedded Neo4j store at `STORE_URI`. Hierograph analyzes compiled
   bytecode, not source.
2. The **`io.hierograph:io.hierograph.jqassistant.rules`** plugin (version `HIEROGRAPH_VERSION`) is
   on jQAssistant's plugin path.
3. **`analyze` is run** with group `hierograph:virtual-external` and the concepts below. This group
   materializes the virtual external nodes/edges the MCP tools query; without it `analyze` fails
   with *"Cannot find group"* and the graph lacks the shape Hierograph expects.
   ```yaml
   analyze:
     groups:
       - hierograph:virtual-external
     concepts:
       - java-classpath:Resolve
       - java:TypeAssignableFrom
       - java:MethodOverrides
       - java:MemberInheritedFrom
       - java:VirtualInvokes
   scan:
     reset: true
   ```
4. A **Bolt server** is started on that store (default `bolt://localhost:BOLT_PORT`).

## Parameters — resolve these first, then echo them back to the user

| Parameter | Default | Notes |
|-----------|---------|-------|
| `HIEROGRAPH_VERSION` | latest **release** (currently `0.1.0`) | Version of `io.hierograph:io.hierograph.jqassistant.rules` and the MCP server image. **Always use the latest released version — never a `-SNAPSHOT` build.** Discover it in Step 1; it resolves automatically from Maven Central, no local build required. |
| `JQASSISTANT_VERSION` | `2.9.1` | jQAssistant version. Must match across the build-tool plugin / CLI, `.jqassistant.yml`, and the `server` command. |
| `STORE_URI` | `file:.jqassistant-store` | Embedded Neo4j store location, relative to project root. Kept out of `target/` / `build/` so a clean does not wipe it between scan and serve. Add it to `.gitignore`. |
| `BOLT_PORT` | `7687` | jQAssistant Bolt endpoint. |
| `MCP_PORT` | `8080` | Hierograph MCP HTTP endpoint (`http://localhost:<MCP_PORT>/mcp`). |

## The procedure

### Run commands so they don't stall on approval

Run every command below as a **single, plain invocation, exactly as written**. The harness cannot
statically verify commands that contain shell expansions or chaining, so those force a manual
permission prompt *every time* — regardless of any allowlist — which stalls an otherwise unattended
setup. Do **not**:

- use shell expansions or command substitution — `${PIPESTATUS[0]}`, `$(...)`, `` `…` ``, `$VAR`
  arithmetic;
- chain with `;`, `&&`, or `||`;
- pipe into `tail`/`grep`/`head` or redirect with `2>&1 | …` just to trim output;
- pass quoted brace/template arguments — `docker ps --format '{{.Names}}'`,
  `docker inspect -f '{{…}}'` — the quoted `{{ }}` reads as expansion obfuscation and always
  prompts; use the command's plain default output instead;
- write polling loops to wait for something — `for i in $(seq 1 20); do … sleep 2; done`,
  `while … do sleep`. These bundle substitution + chaining and always prompt.

**Inspect files with the file tools, not Bash.** Use Read / List / Glob to look at `pom.xml`,
`build.gradle`, `target/`, `.jqassistant-store/`, etc. — they never prompt. Don't reach for
`head`/`cat`/`ls`/`tail` in a shell (and never bundle several with `;`, e.g.
`head -60 pom.xml; ls target/; docker --version`). Reserve Bash for things only a shell can do —
builds, servers, `java -version`, `docker --version` — each as its own single command.

Run the action, then **verify in a separate command**. Two examples of what *not* to do, and the
fix:

- Scan: not `mvn … -Pjqassistant 2>&1 | tail -8; echo "EXIT=${PIPESTATUS[0]}"; ls -d .jqassistant-store/data`
  — run `mvn clean install -Pjqassistant` on its own, then `ls .jqassistant-store` as its own step.
- Wait for a server: not a `for`/`while` poll over `docker logs … | grep` and `docker ps --format`.
  Instead run **one plain check** and, if it's not up yet, repeat that single command as a separate
  step: `docker logs hierograph-mcp` to read status, then `nc -z localhost 8080` (or
  `curl -s http://localhost:8080/mcp`) as its own command. Because the server runs in the
  background, the harness also re-invokes you when the process changes state — you rarely need to
  poll at all.

The only multi-line commands that are fine are ones using plain `\` line-continuation with no
expansion (e.g. the `docker run …` block in Step 3). Long output is fine — let it scroll rather than
piping it through a filter.

### Keep the user informed

This is a multi-minute setup with slow steps (first scan downloads jQAssistant plugins; `docker
pull` fetches the image) and two background servers. Don't run it silently:

- **Track the pipeline as a task list** — create one task per stage (preflight, scan, Bolt server,
  MCP server, register client, verify) and flip each to in-progress / completed as you go, so the
  user sees the whole arc and where it currently is.
- **Narrate the slow and background steps** before they run — say what's starting and the rough
  wait (e.g. "first scan downloads plugins, ~1 min"; "pulling the MCP image"). A backgrounded
  command that takes a while should be announced, not left to look hung.
- **Confirm each server as it comes up** — echo the concrete endpoint and that it's listening
  (`Bolt enabled on …:7687`; MCP up on `MCP_PORT`), not just "started".
- **On failure, surface the actual error and which stage it belongs to** — don't retry silently.

### 1. Preflight

- `java -version` → **21+**. If not, stop and tell the user.
- Confirm the project's **bytecode is available**: for Maven/Gradle a green build
  (`mvn`/`gradle` install/build) is a prerequisite for a meaningful scan; for the JARs shape the
  built `.jar`s / `classes/` dir must already exist.
- **Determine the latest release version.** Query Maven Central's metadata for the rules plugin and
  use the `<release>` value as `HIEROGRAPH_VERSION` — **do not use a `-SNAPSHOT` version:**
  ```bash
  curl -s https://repo1.maven.org/maven2/io/hierograph/io.hierograph.jqassistant.rules/maven-metadata.xml \
    | sed -n 's:.*<release>\(.*\)</release>.*:\1:p'
  ```
  (At time of writing this returns `0.1.0`.) If Maven Central is unreachable, ask the user for the
  release version rather than falling back to a snapshot.
- **The rules plugin resolves automatically.** `io.hierograph:io.hierograph.jqassistant.rules` at a
  released version is on Maven Central, so jQAssistant fetches it during the scan — no local build
  or `git clone` needed. (A missing plugin surfaces later as *"Cannot find group"* — see the
  sub-skill's failure notes.)

### 2. Detect the shape and run the scan

Determine the shape from the table above, confirm it with the user (and which module/artifacts to
scan), then **read the matching sub-skill file and follow it**:

- Maven → `scan-maven.md`
- Gradle → `scan-gradle.md`
- Plain JARs / classes → `scan-jars.md`

Do not proceed until that sub-skill reports a **live Bolt server** on a populated store. (Smoke
check: the Neo4j browser at `http://localhost:7474`, or the sub-skill's own verification note.)

### 3. Start the Hierograph MCP server (background)

The server connects to the Bolt store and exposes the MCP tools over HTTP. It is distributed as a
**Docker image** at the released `HIEROGRAPH_VERSION` — use that tag, not `latest` or a snapshot:

```bash
docker run --rm -p 8080:8080 \
  -e HIEROGRAPH_BOLT_URI=bolt://host.docker.internal:7687 \
  ghcr.io/code-kontor/hierograph-mcp-server:0.1.0
```

- The image is on GHCR and may require authentication to pull — if `docker run` fails with a
  manifest/`unauthorized` error, run `docker login ghcr.io` first (a GitHub PAT with
  `read:packages`). If the package is **private**, `docker login` alone won't help without org
  access: the maintainer must make the GHCR package public (or grant you access) before the pull
  succeeds.
- `host.docker.internal` lets the container reach the Bolt server on the host. On plain-Docker
  Linux, add `--add-host=host.docker.internal:host-gateway`, or use the host's LAN IP.
- The Bolt server listens only on loopback by default and will refuse the container's connection.
  The sub-skills note how to bind it to all interfaces
  (`-Djqassistant.store.embedded.listen-address=0.0.0.0`); apply that if you use Docker.

If the user has a local Hierograph checkout instead of Docker, run the server from source (it then
reaches `bolt://localhost:7687` directly, no host-networking notes):

```bash
mvn -o -pl hierograph-mcp/io.hierograph.mcp.server spring-boot:run   # from the hierograph checkout
```

Wait until the MCP server logs that it is up on `MCP_PORT`.

### 4. Register the server with the client

**Claude Code:**

```bash
claude mcp add hierograph --transport streamable-http http://localhost:8080/mcp
```

**Other JSON-config clients** (Claude Desktop, Cursor) — emit this for the user to add:

```json
{
  "mcpServers": {
    "hierograph": {
      "type": "streamable-http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

For Claude Code, the `hierograph` tools are **not callable in the session that ran `claude mcp
add`** — even though `claude mcp list` reports the server as ✔ Connected. The tools load only after
a **new session reconnects** to the server (start a fresh session, or reconnect via `/mcp`).
"Connected" confirms the server is reachable, not that the tools are available in the current
conversation.

### 5. Verify

Because the client-side tools require a fresh session (Step 4), verify in two stages — and **do not
hand-roll the streamable-HTTP JSON-RPC handshake in bash** (session-id capture via `$(…)`, header
arrays like `H=(…)` / `"${H[@]}"`, `sed`/`grep`/`awk` pipes). That construct always trips a
permission prompt and is brittle; prove the pipeline from the server's own signals instead.

**a) In-session smoke test (no reconnect needed)** — two plain commands prove the server is up and
has loaded the graph from Bolt:

- `nc -z localhost 8080` — confirms the MCP server is listening (or `curl -s http://localhost:8080/mcp`
  as a single command; a `400/406` still proves it's reachable).
- `docker logs hierograph-mcp` — on startup the server connects to Bolt and loads the hierarchical
  model; look for the model-load / node-count line and the **absence** of Bolt-connection errors.
  That confirms scan → store → Bolt → MCP end to end. (For a source run, read the `spring-boot:run`
  console instead.) An empty model or a Bolt error means the store is empty or the MCP server can't
  reach Bolt — recheck the scan (Step 2) and the Bolt URI (Step 3).

**b) Functional verification (after reconnect)** — the real check is a **tool call, not curl**: in a
fresh session the client lists a `hierograph` server (`graph_overview`, `find_node`,
`pairwise_dependencies`, …); call `graph_overview` directly and confirm it returns a `stats` block
(node counts by kind). If you ever must exercise the HTTP endpoint before a client is connected, use
a purpose-built MCP client rather than a bash handshake.

Report what is running (both servers, ports, store path), that a new session is needed before the
tools are usable, and how to re-scan after code changes (the sub-skill's scan command).

## Handing off

Once verified, point the user at the analysis skills — **hierograph-dsm** (DSM / layering / cycles)
and **hierograph-extract** (plan a module extraction) — or just ask hierograph directly, e.g.
*"use hierograph: what's the most fragile coupling in this codebase?"* If the AI starts reading
source files instead of querying, nudge it with **"use hierograph"**.

## Common failure modes (shared)

- **"Cannot find group hierograph:virtual-external"** — the rules plugin isn't resolvable (Step 1)
  or wasn't added to the scan's plugin path (see the sub-skill).
- **Empty `graph_overview()`** — scan didn't run / didn't populate the store, or the MCP server
  points at a different store/Bolt URI than the running server.
- **Container can't reach Bolt** — Bolt bound to loopback only; bind all interfaces with
  `-Djqassistant.store.embedded.listen-address=0.0.0.0` (Step 3 / sub-skill).

Shape-specific failures (wrong `server` invocation, plugin-declaration syntax, CLI download) live
in each sub-skill.
