---
description: Rescan the already-bootstrapped target project into the jQAssistant store and refresh the live graph, so Hierograph reflects the current code. Auto-detects the build shape (Maven / Gradle / plain JARs).
argument-hint: "[optional: module or scan scope, e.g. a Maven -pl path]"
disable-model-invocation: true
allowed-tools:
  - Bash(java -version)
  - Bash(mvn:*)
  - Bash(./mvnw:*)
  - Bash(gradle:*)
  - Bash(./gradlew:*)
  - Bash(nc -z:*)
  - Bash(curl:*)
  - Bash(docker run:*)
  - Bash(docker logs:*)
  - Bash(docker ps:*)
  - Bash(docker stop:*)
  - Read
  - Glob
---

# hierograph-rescan — refresh the tracked system's graph

Re-run the jQAssistant **scan/analyze** against the target project's current bytecode and bring the
live graph up to date, **without** redoing first-time setup. This is the "I changed code, refresh
Hierograph" action — the counterpart to a full bootstrap.

**This command reuses the scan procedure from the `hierograph-bootstrap` skill rather than
re-describing it.** Its scan sub-skills are the single source of truth for the per-shape commands:
`scan-maven.md`, `scan-gradle.md`, `scan-jars.md` (in that skill's directory). Read the matching one
and follow its **Scan** step verbatim — do not invent scan/CLI commands, especially for Gradle and
JARs, whose sub-skills are still stubs.

Follow the bootstrap skill's **"Run commands so they don't stall on approval"** discipline: one
plain command per step, inspect files with Read/Glob (not `cat`/`ls`), act then verify in a separate
command.

`$ARGUMENTS`, if given, narrows the scan scope (e.g. a specific Maven module) — otherwise scan the
same scope bootstrap used.

## 0. Confirm the project is already bootstrapped

Rescan is **not** first-time setup. Verify the bootstrap artifacts exist; if they don't, stop and
point the user at the `hierograph-bootstrap` skill instead.

- `.jqassistant.yml` present at the project root (Read / Glob).
- The store directory from that file's `store.uri` exists (default `.jqassistant-store`).

If either is missing → *"This project isn't bootstrapped yet — run the hierograph-bootstrap skill
first."* and stop.

## 1. Auto-detect the build shape

Same detection as bootstrap Step 2 — pick by what's at the scan root:

| Shape | Detect by | Scan sub-skill |
|-------|-----------|----------------|
| **Maven** | `pom.xml` | `scan-maven.md` → `mvn clean install -Pjqassistant` |
| **Gradle** | `build.gradle` / `build.gradle.kts` | `scan-gradle.md` (**stub** — tell the user it isn't documented; don't guess) |
| **Plain JARs / classes** | no build tool; prebuilt `.jar`s or a `classes/` dir | `scan-jars.md` (**stub** — same caveat) |

State the detected shape and scope, then read that sub-skill file.

## 2. Cycle the Bolt server around the scan, then reload

The store is an **embedded, single-writer Neo4j** database: while the jQAssistant **Bolt server**
holds it open, the scan can't rewrite it — so the Bolt server still has to be cycled around the scan.
The **MCP server, however, stays up**: it exposes a `reload_graph` tool that rebuilds its in-memory
model from the store without a restart, so the client session and its registered tools stay live (no
`/mcp` reconnect, no dropped container).

1. **Stop the jQAssistant Bolt server** (the `…:server` process started during bootstrap) so the
   store is free to be written. Confirm the port is closed: `nc -z localhost 7687` should now fail.
   Leave the MCP server running (its tools will error transiently against the down Bolt server during
   the scan — that's expected; don't query during the rescan).
2. **Rescan** — follow the detected sub-skill's **Scan** step (Maven: `mvn clean install
   -Pjqassistant`; scope it with `$ARGUMENTS`/`-pl` if given). The config's `scan: reset: true`
   repopulates the store. A "Cannot find group" error means the rules plugin isn't resolvable — see
   the sub-skill's failure notes. Benign `MINOR` "Concept Application Failure" warnings are fine as
   long as the build ends in `BUILD SUCCESS`.
3. **Restart the Bolt server** with the exact command from the sub-skill's *Start the Bolt server*
   step — including `-Djqassistant.store.embedded.listen-address=0.0.0.0` if the MCP server runs in
   Docker. Wait for `Bolt enabled on …:7687`; confirm with `nc -z localhost 7687`.
4. **Reload the graph — do NOT restart the MCP server.** Trigger the `reload_graph` tool so the
   running MCP server re-queries Bolt and atomically swaps in the fresh snapshot:
   - **In-session:** call the `reload_graph` MCP tool directly ("use hierograph: reload_graph").
   - **Or via REST** (equivalent, scriptable): `curl -s -X POST http://localhost:8080/api/reload`.
   It returns a JSON status: `"reloaded"` with the refreshed `root_children` count and a new
   `data_hash`, or `"error"` with a message (in which case the **previous** graph is still being
   served — recheck the Bolt server came back up, then retry).

Nothing was re-registered and the MCP server never went down, so **no client reconnect is needed** —
pagination cursors from before the reload become stale by design and are simply re-requested.

## 3. Verify the refresh

- The `reload_graph` result itself is the primary signal: `status: "reloaded"` with a non-zero
  `root_children` confirms scan → store → Bolt → MCP end to end. A `data_hash` different from before
  confirms the graph actually changed.
- Then a **tool call**: ask hierograph `graph_overview` and confirm the `stats` block reflects the
  current code. The already-registered tools keep working in the same session — no `/mcp` reconnect.

Report what was rescanned (shape + scope), the reload status and refreshed node counts, and that the
Bolt server is back up (the MCP server never went down).
