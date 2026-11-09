# Hierograph Build-Tool-Neutral Manifest — Design

**Build-tool neutrality** lets the build manifest come from any build tool,
not only from Maven. The server already treats the manifest as a scan plan and
knows almost nothing about Maven. The remaining Maven knowledge on the server
(the regeneration command) moves into the manifest, where each producer writes
the command that fits its tool. The producer-side logic that does not depend on
the tool moves into a shared library, and the manifest format grows what a Gradle
build needs.

This document describes what is Maven-specific today, the design that removes it,
and an outline of a Gradle producer. It is a design document, not a
specification: it describes intended behaviour. Once implemented, its content
moves into the specifications listed in §9 and this file is deleted.

**Status.** Parts A–D are implemented and specified in the
[build manifest](../spec/hierograph-build-manifest.md),
[hierarchical graph service](../spec/hierarchical-graph-service.md),
[Maven plugin](../../../hierograph-maven-plugin/spec/hierograph-maven-plugin.md)
and [manifest library](../../../hierograph-manifest/spec/hierograph-manifest.md)
specifications, with the §11 decisions as proposed. Part E, the Gradle producer,
is open.

---

## Contents

1. [Problem](#1-problem)
2. [Where it sits](#2-where-it-sits)
3. [Design overview](#3-design-overview)
4. [A — The manifest names its build tool and regeneration command](#4-a--the-manifest-names-its-build-tool-and-regeneration-command)
5. [B — Several output directories per output](#5-b--several-output-directories-per-output)
6. [C — A shared producer library](#6-c--a-shared-producer-library)
7. [D — Neutral vocabulary](#7-d--neutral-vocabulary)
8. [E — Outline of a Gradle producer](#8-e--outline-of-a-gradle-producer)
9. [Specification changes](#9-specification-changes)
10. [Test plan](#10-test-plan)
11. [Decisions to confirm](#11-decisions-to-confirm)

---

## 1. Problem

The running example is the reactor `/ws/billing` with the modules
`billing-api` and `billing-core`, once built with Maven and once with Gradle.

The manifest format, the scans, the manifest diff and the build-file hashing do
not depend on the build tool. Four things do:

| Where | What is Maven-specific | Effect on a Gradle build |
|-------|------------------------|--------------------------|
| `BuildFileMonitor.defaultCommand` (server) | `mvnw` / `mvn -q -B process-classes` | `regenerate` mode runs Maven in a Gradle project and fails |
| Manifest format | one `mainOutputDir` / `testOutputDir` per module | a Gradle source set compiles Java and Kotlin into separate directories (`build/classes/java/main`, `build/classes/kotlin/main`); one of them is lost |
| Maven plugin (`ManifestMerger`, internal) | the merge, write-only-on-change, atomic write, locking and hash cache | a Gradle producer re-implements all of it |
| Specs, tool descriptions, messages | "reactor", "Maven resolution order", "run a Maven build reaching process-classes" | wrong guidance for a Gradle user |

The regeneration command is the central problem. The best command differs between
tools in substance, not only in syntax:

- Maven 3 must compile (`process-classes`), because it does not resolve
  reactor siblings that are not part of the build or installed.
- Gradle resolves project dependencies to component identifiers without building
  them. A dedicated task can write the manifest without compiling anything.

Only the producer knows which command fits its tool.

---

## 2. Where it sits

```
 ┌──────────────────────┐   ┌──────────────────────┐
 │ Maven plugin         │   │ Gradle plugin (E)    │
 │  Maven-specific      │   │  Gradle-specific     │
 │  collection          │   │  collection          │
 └──────────┬───────────┘   └──────────┬───────────┘
            │                          │
            ▼                          ▼
 ┌─────────────────────────────────────────────────┐
 │ hierograph-manifest (C)                         │
 │  model · merge · write-on-change · atomic write │
 │  · lock · build-file hashing                    │
 └────────────────────────┬────────────────────────┘
                          ▼
            .hierograph/manifest.json
              buildTool, regenerate (A),
              output directory lists (B)
                          │
                          ▼
 ┌─────────────────────────────────────────────────┐
 │ server: ManifestGraphSource, watchers,          │
 │ BuildFileMonitor (runs `regenerate` as given)   │
 └─────────────────────────────────────────────────┘
```

**Producers.** The Maven plugin, and a Gradle plugin built on the same library.
Each collects what only its tool knows and hands it to the library.

**Consumers.** The server, unchanged in role: it reads the manifest, scans,
watches, and runs the regeneration command the manifest names.

---

## 3. Design overview

| Part | Side | Change |
|------|------|--------|
| **A** | format, plugin, server | The manifest names its `buildTool` and its `regenerate` command; the server drops its Maven default. |
| **B** | format, server | Outputs carry a list of directories; scans and watchers handle several directories per output. |
| **C** | plugin | The tool-independent producer logic moves into a library, `hierograph-manifest`. |
| **D** | specs, server | Tool-neutral terms in the specs, tool descriptions and messages. |
| **E** | new Gradle plugin | Outline only; implemented separately once A–D are in place. |

A and B change the format. D is wording. C is a refactoring with no visible
effect. Together they leave the server free of build-tool knowledge, so a second
producer needs no server change.

---

## 4. A — The manifest names its build tool and regeneration command

### 4.1 Format

Two top-level fields:

```json
"buildTool": { "name": "maven", "version": "3.9.6" },
"regenerate": ["./mvnw", "-q", "-B", "process-classes"]
```

```json
"buildTool": { "name": "gradle", "version": "8.10" },
"regenerate": ["./gradlew", "-q", "hierographManifest"]
```

| Field | Type | Meaning |
|-------|------|---------|
| `buildTool.name` | string | Producing tool: `maven`, `gradle`. Informational. |
| `buildTool.version` | string | Tool version on the build host. Informational. |
| `regenerate` | string[]? | The command that rewrites this manifest, as an argument vector, run in the reactor root. Absent when the producer cannot name one. |

**Why `buildTool` is informational.** The server must not branch on it; that
would put tool knowledge back into the server. It appears in logs and in
`build_state`, so a developer sees which tool the server expects to run.

**Why an argument vector.** A single string needs a shell and quoting rules that
differ between platforms. A vector runs directly.

**Paths in the command.** An argument starting with `./` is relative to the
reactor root; the server resolves it against the local reactor root, so it
follows the path-portability rule. Other arguments are passed unchanged; a bare
`mvn` or `gradle` is looked up on the `PATH`. The producer writes the command for
the build host's operating system (`./mvnw` or `mvnw.cmd`): the server runs a
regeneration only for a manifest that is not relocated, which means on the build
host.

### 4.2 Maven producer

The Maven plugin writes `buildTool` from the running Maven version, and
`regenerate` as `./mvnw -q -B process-classes` when the multi-module project
directory holds a Maven wrapper (`mvnw.cmd` on Windows), otherwise
`mvn -q -B process-classes`. This is the command `BuildFileMonitor` uses as its
default today, moved to the side that knows Maven.

### 4.3 Server

The command is chosen in this order:

| Source | Used when |
|--------|-----------|
| `hierograph.graph.regenerate.command` | set: an explicit override for unusual setups (a build profile, extra flags) |
| the manifest's `regenerate` | present |
| none | `regenerate` mode behaves as `notify`, with a warning naming the missing command |

`BuildFileMonitor.defaultCommand` is removed. `BuildFileMonitor.Inspection`
gains the command from the applied manifest, so a manifest change can change the
command.

**Compatibility.** A manifest from a producer without these fields has no
command. With `regenerate` mode and no configured command, the server reports
stale build files instead of running Maven. This changes behaviour only for
manifests written before A, and only in the opt-in mode.

---

## 5. B — Several output directories per output

### 5.1 Format

```json
"mainOutputDirs": ["/ws/billing/billing-core/build/classes/java/main",
                   "/ws/billing/billing-core/build/classes/kotlin/main"],
"testOutputDirs": ["/ws/billing/billing-core/build/classes/java/test",
                   "/ws/billing/billing-core/build/classes/kotlin/test"]
```

The lists replace `mainOutputDir` and `testOutputDir`. A consumer reads the list
when present and falls back to the single field otherwise, so a manifest written
before B still loads. Producers write only the lists.

**Why a list and not one entry per directory.** The directories of a source set
form one artifact: their classes resolve against one classpath, appear as one
module, and change together. Splitting them into separate outputs would create
modules that do not exist in the build.

### 5.2 Server

| Component | Change |
|-----------|--------|
| `DeclaredOutput` | `dir: Path` becomes `dirs: List<Path>` |
| `ManifestGraphSource` scans | one `ScanArtifact` per existing directory, all with the output's key; the scanner already accepts several artifacts with one key |
| `ModuleScan.signature` | the sum of `outputSignature` over the directories |
| `WatchTargets.modules` | `Map<String, List<Path>>`; a change in any directory reports the key |
| `NativeGraphWatcher` | registers every existing directory; maps an event to the key of the longest matching directory, as today |
| Manifest diff | an output is unchanged when its list of directories and its signature are unchanged |

An output is present when at least one of its directories exists; it is dropped
when none does.

---

## 6. C — A shared producer library

A module `hierograph-manifest` (plain JVM, no Maven or Gradle API) holds what the
Maven plugin keeps internal today:

| Element | Contents |
|---------|----------|
| Model | `Manifest`, `ModuleDescriptor`, `ClasspathEntry`, `BuildFile`, `BuildTool` |
| Merge | descriptors in, manifest out: duplicate GAVs, ordering, keeping an entry whose descriptor is missing |
| Writing | write-only-on-change (comparing JSON trees without `generatedAt`), atomic replacement, the JVM lock and the `manifest.lock` file lock |
| Build files | hashing with the size-and-mtime cache |

The tool-specific parts stay in each plugin: which projects form the build, how
classpaths are resolved, which files are build files, and the `regenerate`
command. The library takes these as plain values (lists of descriptors, paths,
strings).

**Why not share the model with the server.** The server parses leniently with
its own DTOs (unknown fields ignored, missing fields defaulted), which is the
format's compatibility contract. A shared strict model would tie the server to
one producer version.

---

## 7. D — Neutral vocabulary

| Term today | Neutral term | Definition |
|------------|--------------|------------|
| reactor | build | the set of modules one build invocation knows: a Maven reactor, a Gradle build including its included builds |
| reactor root (`reactorRoot`) | build root (field name unchanged) | the directory the manifest's `.hierograph/` lives in: the Maven execution root, the Gradle root project directory |
| Maven resolution order | classpath order | the order of the classpath the tool compiles against |
| module | module | a Maven module, a Gradle project (per source set: main and test) |
| POM | build file | any file the manifest lists in `buildFiles` |

The field name `reactorRoot` stays: renaming it breaks every existing manifest for
no functional gain. The specs define it in the neutral sense.

Text to change on the server: the `reload_graph` description ("run a Maven build
reaching process-classes"), the KDoc of `GraphWatcher`, `NativeGraphWatcher` and
`HierarchicalGraphService` that names POMs and `mvn clean`. `build_state`
messages name the command that ran, which already makes them tool-specific
without the server knowing the tool.

---

## 8. E — Outline of a Gradle producer

Not part of the implementation of A–D; recorded here so the format changes fit it.

**Plugin and task.** A settings plugin `io.hierograph` registers a task
`hierographManifest` on the root project. It writes per-project descriptors and
merges them through the library. It does not depend on compilation: output
directories come from the source sets' configuration, and classpaths are
resolved without building project dependencies (§8.2). `regenerate` is
`./gradlew -q hierographManifest`.

**Keeping the manifest current during normal builds.** Each project's `classes`
and `testClasses` tasks are finalized by `hierographManifest`, so any build that
compiles also updates the manifest.

### 8.1 Mapping

| Manifest | Gradle |
|----------|--------|
| `gav` | `group:name:version` of the project (`project.group`, `project.name`, `project.version`); an empty group falls back to the root project's name |
| `name` | `project.name` |
| `mainOutputDirs` | `sourceSets.main.output.classesDirs` |
| `testOutputDirs` | `sourceSets.test.output.classesDirs` |
| `sourceRoots` / `testSourceRoots` | `allSource.srcDirs` of `main` / `test` |
| `classpath` | `compileClasspath`, then the entries only in `runtimeClasspath` |
| `testClasspath` | `testCompileClasspath`, then the entries only in `testRuntimeClasspath` |
| `internal` | the component identifier is a `ProjectComponentIdentifier` |
| `reactorRoot` | the root project directory |

### 8.2 Resolution without building

Component identifiers come from each configuration's `ResolutionResult`, which
does not build project dependencies. External jar paths come from an artifact
view that excludes project components, which downloads but does not build. This
is why the Gradle task needs no compilation, unlike Maven's `process-classes`.

### 8.3 Build files

`settings.gradle(.kts)`, every project's `build.gradle(.kts)`,
`gradle.properties`, `gradle/libs.versions.toml` and other version catalogs,
`gradle/wrapper/gradle-wrapper.properties`, and every file of `buildSrc` and of
included builds that contribute build logic (excluding their `build/` and
`.gradle/` directories). Build logic in Gradle is code, so its sources are build
files.

### 8.4 Open points for E

- Configuration cache: a root task reading every project's model is not
  compatible with it. Per-project descriptor tasks shared through consumable
  configurations are the Gradle-idiomatic alternative.
- Composite builds: whether included builds' projects are modules of the
  manifest or external dependencies.
- Android and other non-`java` source-set models.

---

## 9. Specification changes

| Specification | Sections | Change |
|---------------|----------|--------|
| [build manifest](../spec/hierograph-build-manifest.md) | §1 | `buildTool`, `regenerate`, `mainOutputDirs` / `testOutputDirs`; neutral definitions of build root and module |
| | §2 | classpath order defined per tool |
| | §3 | relative paths in `regenerate` |
| | §4 | fallback from the lists to the single fields |
| [hierarchical graph service](../spec/hierarchical-graph-service.md) | §6 | command precedence; no default command; several directories per output |
| | §10 | `hierograph.graph.regenerate.command` as an override |
| [Maven plugin](../../../hierograph-maven-plugin/spec/hierograph-maven-plugin.md) | §3, §5 | `buildTool`, `regenerate`, output lists; the library |
| `hierograph-manifest` (new) | — | a short spec of the library's API and its write guarantees |

---

## 10. Test plan

| Area | Test |
|------|------|
| Maven plugin | manifest holds `buildTool` and `./mvnw …` with a wrapper, `mvn …` without |
| server | configured command wins over the manifest's; manifest command used otherwise; no command → `notify` behaviour with a warning |
| server | `./`-relative argument resolved against the local build root |
| server | an output with two directories: both scanned under one key; a change in either reported; dropped only when both are gone |
| server | a manifest with only `mainOutputDir` still loads |
| library | the Maven plugin's existing scenarios (unchanged rebuild, `-pl`, `-T 4`, rename, removal, `clean` + `-pl`) pass on the library |
| end to end | a hand-written manifest with `buildTool: gradle` and a `regenerate` script drives a regeneration without Maven on the `PATH` |

---

## 11. Decisions to confirm

1. **The manifest carries the command** (A), with the server setting as override,
   rather than the server detecting the tool.
2. **No regeneration without a command**: `regenerate` mode falls back to
   `notify` when neither the manifest nor the configuration names one.
3. **Output lists replace the single fields** in the format (B), with a read
   fallback, rather than adding a second field next to them.
4. **`reactorRoot` keeps its name** (D) and is redefined as the build root.
5. **The shared library is a separate module** (C) inside this repository,
   published with the plugins.
6. **E is a separate piece of work**; A–D make the server ready for it.
