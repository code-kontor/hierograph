# Hierograph Module-Set Tracking — Design

**Module-set tracking** keeps a `manifest`-mode server's graph in step with the
reactor's **module set**: the modules, their coordinates, their output
directories and their classpaths. Class changes inside a known module already
reach the graph through the file watcher. Changes to the module set (creating,
deleting or renaming a module, or editing a dependency) reach it only after a
full reactor build up to `verify`.

This document describes the gap, the design that closes it, and the changes it
makes to the plugin, the manifest and the server. It is a design document, not a
specification: it describes intended behaviour. Once implemented, its content
moves into the specifications listed in §9 and this file is deleted.

---

## Contents

1. [Problem](#1-problem)
2. [Where it sits](#2-where-it-sits)
3. [Design overview](#3-design-overview)
4. [A — The manifest follows every build](#4-a--the-manifest-follows-every-build)
5. [B — Modules without output are watched](#5-b--modules-without-output-are-watched)
6. [C — Build files are watched](#6-c--build-files-are-watched)
7. [Server: applying a manifest change](#7-server-applying-a-manifest-change)
8. [Failure modes and limits](#8-failure-modes-and-limits)
9. [Specification changes](#9-specification-changes)
10. [Test plan](#10-test-plan)
11. [Decisions to confirm](#11-decisions-to-confirm)

---

## 1. Problem

The running reactor for all examples is `/ws/billing`:

```
/ws/billing/pom.xml                       aggregator
├── billing-api/pom.xml                   com.example:billing-api:1.4.0
└── services/pom.xml                      nested aggregator
    ├── billing-core/pom.xml              com.example:billing-core:1.4.0
    └── billing-export/pom.xml            com.example:billing-export:1.4.0 (created)
```

The server reacts correctly to every change it is told about. It watches the
manifest file and, when it changes, re-reads it and re-targets the watcher. It
watches every scanned output directory and rescans a module whose classes change.
It drops a module whose output directory disappears. The gaps lie in what tells
it:

| Change | What the developer does | What the server sees |
|--------|-------------------------|----------------------|
| Create `billing-export` | add `<module>` to `services/pom.xml`, compile in the IDE or with `mvn compile` | nothing: the manifest is not rewritten, and `billing-export` is in no watch target |
| Rename `billing-core` → `billing-ledger` | edit the `artifactId`, `mvn compile` | classes under the old GAV keep changing; dependents' classpaths still name the old GAV |
| Delete `billing-api` | remove the directory and the `<module>` entry | the output directory vanishes and the module is dropped, but its GAV stays on the classpaths of dependents |
| Add a dependency to `billing-core` | edit `billing-core/pom.xml`, `mvn compile` | references resolve against the stale classpath |

There are three causes, one per part of the design:

1. **The manifest is written only at `verify`.** `create-descriptor` updates each
   module's `module.json` at `process-classes`, but only `merge-descriptors`,
   bound to `verify`, folds them into `manifest.json`. `mvn compile`, a `-pl`
   build and an IDE build never reach it.
2. **A module without compiled output is invisible.** `create-descriptor` writes
   no descriptor for a module with neither `target/classes` nor
   `target/test-classes`. `ManifestGraphSource.scan` registers only modules whose
   output directory exists, and only registered modules become watch targets. A
   module created in the IDE stays out of the graph even after the IDE compiles
   it.
3. **POM edits produce no build.** The module set and the classpaths are facts
   only Maven can compute. When the developer edits a POM and runs no Maven build,
   nothing produces the new facts.

Causes 1 and 2 are defects in the producer and the consumer. Cause 3 is inherent:
some Maven run is needed after a POM change. The design makes that run as cheap
as any `process-classes` build, and optionally lets the server start it.

---

## 2. Where it sits

```
 developer / IDE          Maven build                 server
 ───────────────          ───────────                 ──────

 mvn process-classes ───► ┌─────────────────────┐
                          │ create-descriptor   │
                          │  module.json        │
                          │  manifest.json (A)  │ ──► manifest watch ──────┐
                          └─────────────────────┘                          │
 compile ─────────────────── target/classes ──────► output watch (B) ──────┤
                                                    incl. missing dirs     │
 edit POM ────────────────── pom.xml ─────────────► build-file watch (C) ──┤
                                                    notify / regenerate    ▼
                                                    ┌────────────────────────┐
                                                    │HierarchicalGraphService│
                                                    └────────────────────────┘
```

**Producers.** The Maven plugin's `create-descriptor` goal writes the descriptor
and the merged manifest. The compiler, whether Maven's or the IDE's, writes the
output directories. The developer edits the build files.

**Consumers.**

- `ManifestGraphSource` reads the manifest, scans the output directories and
  applies manifest diffs (§7).
- `GraphWatcher` (polling and native) reports changed output directories, a
  changed manifest and changed build files.
- `HierarchicalGraphService` rebuilds the graph, reports a stale build, and
  optionally runs the regeneration command (§6.3).

---

## 3. Design overview

| Part | Fixes cause | Side | Change |
|------|-------------|------|--------|
| **A** | 1 | plugin | `create-descriptor` merges the manifest itself, atomically, and only when its content changed. |
| **B** | 2 | plugin + server | Descriptors are written for every non-`pom` module; the server watches declared output directories that do not exist yet. |
| **C** | 3 | plugin + server | The manifest lists the reactor's build files; the server watches them and reports or regenerates. |
| **D** | — (cost) | server | A manifest change is applied as a diff, not as a full rescan (§7). |

D is a consequence of A. Once every build can rewrite the manifest, a full
rescan per manifest change would make each `mvn compile` cost a cold start.

A and B are enough for any Maven run to correct the module set, even one
restricted with `-pl` (§4.1), and they let new modules appear from their first
compile.
C removes the last manual step for developers who mostly work in the IDE.

---

## 4. A — The manifest follows every build

### 4.1 Merging in `create-descriptor`

After writing its own `module.json`, `create-descriptor` rebuilds `manifest.json`
from the descriptors of **`session.allProjects`**, using the same merge logic as
`merge-descriptors` (one shared function).

**Why `allProjects`.** It is the full reactor as defined by the POMs, including
modules excluded with `-pl`. Merging over it gives three properties:

- A module removed from `<modules>` is no longer in `allProjects`, so its entry
  disappears, even if its directory and its stale `target/hierograph/module.json`
  remain on disk.
- A renamed module rewrites its descriptor in place with the new GAV, so the old
  GAV disappears.
- A module not built in this run contributes its last descriptor unchanged.

Searching the filesystem for descriptors has none of these properties: it finds
stale descriptors and descriptors of projects outside the reactor.

**Verified on Maven 3.9.6.** In a reactor `root → a, nested → b`, the build
`mvn -pl a process-classes hierograph:merge-descriptors` writes a manifest listing
both `a` and `b`: `allProjects` holds the full reactor under `-pl`.

**`-pl` and reactor siblings.** Maven 3 resolves a sibling from its `target/`
only when the sibling is part of the same build. `mvn -pl nested/b process-classes`
fails with `Could not resolve dependencies … zz.hgtest:a:jar:1.0 (absent)` unless
`a` is installed. A restricted build of a module with internal dependencies
therefore needs `-am` (`mvn -pl nested/b -am process-classes`). This is Maven's
behaviour, independent of Hierograph.

`merge-descriptors` stays a goal and calls the same function. It is no longer
needed in the default binding; existing builds that bind it keep working.

### 4.2 Writing only on change

The merge compares the new manifest with the existing file, ignoring
`generatedAt`. It rewrites the file only when something else differs.

**Why.** The server treats a changed manifest file as a module-set change. A
rebuild of an unchanged module set produces identical descriptors, so it must
not touch the manifest. Otherwise every `mvn compile` triggers a manifest reload
on top of the class-change rescans.

### 4.3 Atomic, serialised writes

| Concern | Mechanism |
|---------|-----------|
| A reader sees a half-written file | write `manifest.json.tmp` in the same directory, then `Files.move(…, ATOMIC_MOVE, REPLACE_EXISTING)` |
| Parallel module builds (`-T`) merge at once | a JVM-wide lock around read-compare-write |
| Two Maven processes (CLI and IDE) merge at once | an exclusive `FileChannel` lock on `.hierograph/manifest.lock` |

The settle delay of the watchers already hides most partial writes, but only by
timing. Atomic replacement makes the reader's view correct by construction.

### 4.4 Cost

Each `create-descriptor` invocation reads every reactor descriptor: *N* reads per
module, *N²* per full build. Descriptors are a few kilobytes, so this stays
negligible for reactors of a few hundred modules. A session-scoped cache of
parsed descriptors removes the repeated reads if it ever matters.

---

## 5. B — Modules without output are watched

### 5.1 Plugin

`create-descriptor` writes a descriptor for every module whose packaging is not
`pom`, with or without compiled output. It always records
`mainOutputDir = project.build.outputDirectory` and
`testOutputDir = project.build.testOutputDirectory`. The classpaths are resolved
as before; resolution does not depend on compiled output.

`testClasspath` is recorded for every module. It was previously tied to the
existence of test output; test output can appear later, so the decision belongs
to the consumer.

### 5.2 Server

`ManifestGraphSource.Scanned` gains the **declared outputs**: every
`(key, name, dir, origin)` the manifest names, whether or not the directory
exists (`key` is `<gav>` for main output, `<gav>:test` for test output with
`includeTests`).

```kotlin
data class DeclaredOutput(
    val key: String,       // "com.example:billing-export:1.4.0" or "…:test"
    val name: String,      // display name, "billing-export" or "billing-export (test)"
    val dir: Path,         // re-rooted output directory; may not exist yet
    val origin: Origin,    // MODULE_MAIN or MODULE_TEST
)
```

| Component | Change |
|-----------|--------|
| `ManifestGraphSource.scan` | registers every declared output; scans only those whose directory exists |
| `Scanned.moduleDirs` | covers declared outputs, so a missing directory becomes a watch target |
| `ManifestGraphSource.rescan` | a changed key that is declared but not yet scanned is scanned and added, not skipped |
| `PollingGraphWatcher` | unchanged: a missing directory has signature `0`; its appearance is a signature change |
| `NativeGraphWatcher` | on each flush tick, checks declared roots for appearance and disappearance; either event reports the key as changed and re-registers the native watch with the new root set |

**Why the native watcher needs the existence check.** `DirectoryWatcher` can only
register directories that exist. Without the check, a missing directory is never
watched, and a directory removed by `mvn clean` and recreated by the next
compile is lost.

**Startup without output.** A manifest whose declared outputs exist nowhere
(a fresh clone) still starts the watcher. The service starts without a graph, as
it does today when loading fails, and builds the first graph when the first
output appears.

---

## 6. C — Build files are watched

### 6.1 The `buildFiles` field

The merge records the files that define the reactor in a top-level field:

```json
"buildFiles": [
  "/ws/billing/.mvn/maven.config",
  "/ws/billing/billing-api/pom.xml",
  "/ws/billing/pom.xml",
  "/ws/billing/services/billing-core/pom.xml",
  "/ws/billing/services/billing-export/pom.xml",
  "/ws/billing/services/pom.xml"
]
```

| Source | Included | Why |
|--------|----------|-----|
| `project.file` of every project in `session.allProjects` | always, including `pom`-packaged aggregators | any POM can add or remove modules or change dependencies |
| the parent chain of each project | when the parent was read from a local file, not from the local repository | a parent outside the reactor root (`<relativePath>../../parent</relativePath>`) changes inherited dependencies |
| `.mvn/maven.config`, `.mvn/extensions.xml` under the multi-module project directory | when present | they can activate profiles and thus change modules or dependencies |

The list is sorted and free of duplicates, so it does not cause spurious manifest
rewrites (§4.2).

**Why not search the tree for `pom.xml`.** A search picks up POMs that are not
part of the build, such as test fixtures under `src/test/resources`, archetype
templates and orphaned directories, and it misses parents outside the tree.
Maven has already resolved the reactor; the plugin records its answer.

**Nested aggregators need no special handling.** A new module or sub-reactor
always appears through an edit of an aggregator's `<modules>`, and that
aggregator is already listed. The resulting regeneration lists the new POMs.

Paths follow the manifest's path-portability rule: paths under `reactorRoot` are
re-rooted, paths outside are used as recorded. A path that does not exist
locally is not watched.

### 6.2 Watching

Both watchers poll build files by `(size, mtime)` signature on their regular
tick, with the same settle delay as for output directories. A reactor has tens to
hundreds of build files, so polling them is cheap, and it works the same way
under Docker Desktop. The native watcher does not register module directories for
events: that would deliver every source-file save.

A settled change in any build file raises **one** `onBuildFilesChange` callback
for the whole burst. A rename or a version bump touches many POMs and must lead
to one regeneration, not dozens.

### 6.3 Reaction

`hierograph.graph.watch.buildFiles` selects the reaction:

| Value | Reaction |
|-------|----------|
| `off` | none |
| `notify` (default) | mark the graph **stale**: `reload_graph` and `graph_overview` report `build_state: "stale"` with the changed files, until the next manifest change clears it |
| `regenerate` | run the regeneration command in the reactor root; the rewritten manifest then follows the regular path (§7) |

**Regeneration command.** `hierograph.graph.regenerate.command` defaults to
`./mvnw -q -B process-classes` when `mvnw` exists in the reactor root, otherwise
`mvn -q -B process-classes`.

**Why `process-classes` and not a bare `hierograph:create-descriptor`.** Without
a lifecycle phase, Maven 3 does not resolve reactor siblings from their output
directories, so dependency resolution fails for every module with an internal
dependency. Verified on Maven 3.9.6: `mvn hierograph:create-descriptor` fails on
`b` with `Could not resolve dependencies … zz.hgtest:a:jar:1.0 (absent)`, both
after `clean` and with `a/target/classes` present; `mvn process-classes` succeeds
in both states. `process-classes` compiles first, so siblings resolve to their
output directories. The compilation is incremental and also produces the classes
the server is about to scan.

| Rule | Why |
|------|-----|
| One regeneration at a time; changes during a run schedule exactly one follow-up run | the run itself reads the POMs; a change mid-run may not be in its result |
| Disabled, with a warning, when the recorded and the local reactor root differ | the server runs in a container without the build host's Maven and repository; it falls back to `notify` |
| A failed run logs the last lines of its output and sets `build_state: "regeneration-failed"` | the developer sees why the graph did not follow, and the previous graph stays live |
| A timeout (`hierograph.graph.regenerate.timeout`, default `10m`) kills the run | a hanging build must not block later regenerations |

`notify` is the default because running a build is a side effect the developer
should opt into.

---

## 7. Server: applying a manifest change

`reloadFromManifest` today rescans every module. With A, the manifest changes
whenever the module set changes, and one change rarely affects more than a few
modules. The server therefore applies the manifest as a **diff** against the
current `Scanned`, keyed by declared output key:

| Entry | Effect |
|-------|--------|
| removed | drop its scan and classpath |
| added | declare it; scan it if its directory exists |
| output directory changed | rescan |
| classpath or name changed | replace the classpath or name; **no rescan** |
| unchanged | keep the scan |

**Why classpath changes need no rescan.** `scanModule(key, dir)` depends only on
the key and the directory. Classpaths are applied when the graph is built, by the
classpath-order type resolver, so a classpath change only requires a rebuild.

**Why a rename rescans.** A GAV change is a removal plus an addition. The key is
part of every scanned fact, so the module is rescanned under its new key.

After the diff the service rebuilds the graph from the cached union, swaps it in
and re-targets the watcher, as an incremental update does today.

Example: creating `billing-export` and compiling it once. The merge adds one
entry and one build file. The server declares
`com.example:billing-export:1.4.0`, finds `target/classes` present, scans that
one module, rebuilds, and re-targets the watcher, which now includes
`services/billing-export/pom.xml`. The other three modules are not rescanned.

---

## 8. Failure modes and limits

| Situation | Effect |
|-----------|--------|
| POM edited, no Maven run, `notify` | graph stale; `build_state: "stale"` reports it |
| POM edited, regeneration fails (e.g. a POM syntax error) | previous graph stays live; `build_state: "regeneration-failed"` with the cause |
| New dependency not in the local repository, offline | regeneration fails as above; the developer runs an online build |
| `-pl` on a module with internal dependencies, without `-am` | Maven fails dependency resolution (§4.1); nothing changes |
| Maven launched inside a submodule directory (`cd services/billing-core; mvn compile`) | `allProjects` holds only that module, and the execution root is the submodule; the merge writes `services/billing-core/.hierograph/manifest.json`, not the reactor's manifest; the reactor graph keeps class changes but not module-set changes |
| A module directory removed but still listed in `<modules>` | Maven fails the build; nothing changes until the POM is fixed |
| Profile activated by environment or `settings.xml` | not covered by `buildFiles`; `reload_graph` after a manual build |
| SNAPSHOT dependency updated in `~/.m2` | not a module-set change; `reload_graph` |
| Server in a container | `regenerate` falls back to `notify` (§6.3); build files outside the mounted reactor root are not watched |

---

## 9. Specification changes

On implementation, the following specifications take over this design. Each one
describes the result as built; this document is then deleted.

| Specification | Sections | Change |
|---------------|----------|--------|
| [build manifest](../spec/hierograph-build-manifest.md) | §1 | `buildFiles`; output directories always recorded; `testClasspath` always present |
| | §6 | declared outputs, the diff in §7, build-file watching |
| [hierarchical graph service](../spec/hierarchical-graph-service.md) | §6 | missing-directory watching, manifest diff, build-file reaction, `build_state` |
| | §10 | `hierograph.graph.watch.buildFiles`, `hierograph.graph.regenerate.command`, `hierograph.graph.regenerate.timeout` |
| [Maven plugin](../../../hierograph-maven-plugin/spec/hierograph-maven-plugin.md) | §3 | `create-descriptor` merges, writes atomically and only on change, no longer skips modules without output; `merge-descriptors` optional |
| | §7.1 | binding `create-descriptor` alone is enough |

---

## 10. Test plan

| Area | Test |
|------|------|
| plugin merge | `allProjects` with a removed module → entry gone; renamed GAV → old entry gone |
| plugin merge | unchanged descriptors → manifest file not rewritten (mtime unchanged) |
| plugin merge | `-T 4` reactor → valid manifest, all modules present |
| plugin descriptor | module without classes → descriptor with both output directories |
| plugin build files | nested aggregator, external parent, `.mvn/maven.config` all listed; local-repository parent not listed |
| `ManifestGraphSource` | declared-but-missing output appears → scanned and added by `rescan` |
| `ManifestGraphSource` | manifest diff: classpath-only change does not call `scanModule`; GAV change does |
| watchers | polling and native: missing directory appears; directory deleted and recreated |
| watchers | burst of 20 POM edits → exactly one `onBuildFilesChange` |
| service | `notify` sets and clears `build_state`; `regenerate` serialises runs and falls back to `notify` under a re-rooted reactor |
| end to end (`hierograph-itest`) | add a module to a fixture reactor, run `process-classes` with `-pl <module> -am` → graph contains it without restart |

---

## 11. Decisions to confirm

1. **Default reaction** `notify`, not `regenerate` (§6.3).
2. **Default command** `process-classes`, accepting that it compiles (§6.3). A
   bare goal is ruled out: it fails on internal dependencies under Maven 3.
3. **`testClasspath` always recorded** (§5.1), which grows descriptors of modules
   without tests.
4. **Builds launched below the reactor root** keep today's behaviour (§8) instead
   of locating and updating the reactor's manifest. Updating it would need a
   merge that knows the full reactor, which such a build does not have.
