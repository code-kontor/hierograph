# Hierograph Build Manifest — Specification

The build manifest is the contract between a build and the Hierograph MCP
server's **manifest mode**. A build-tool plugin writes it as part of the build;
the server's manifest-mode graph source reads it, scans each module's compiled
output itself, and watches those output directories for changes. The format names
no build tool in its semantics: the Maven plugin is one producer, and any other
producer writes the same format.

The manifest records only what the build tool knows and the server cannot
reconstruct on its own: each module's coordinates, its compiled-output
directories, its **resolved dependency classpaths in classpath order**, the files
that define the build, and the command that rewrites the manifest. The server does
the bytecode scanning; the manifest tells it *what* to scan and *against which
classpath to resolve references*.

- **Producers:** build-tool plugins, through the shared
  [`hierograph-manifest` library](../../../hierograph-build-support/hierograph-manifest/spec/hierograph-manifest.md);
  the [Maven plugin](../../../hierograph-build-support/hierograph-maven-plugin/spec/hierograph-maven-plugin.md)'s
  `hierograph:create-descriptor` writes it on every build that reaches
  `process-classes`
- **Consumer:** the server's manifest-mode graph source (`hierograph.graph.source=manifest`)
- **Location:** `<build root>/.hierograph/manifest.json`
- **Encoding:** UTF-8 JSON

Two terms are used throughout. The **build** is the set of modules one build
invocation knows (a Maven reactor). The **build root** is the directory that
holds the manifest's `.hierograph/` directory (the Maven execution root); the
manifest records it as `reactorRoot`.

---

## 1. Format

```json
{
  "schemaVersion": 2,
  "generator": "io.hierograph.maven.plugin/0.2.0",
  "generatedAt": "2026-11-08T17:04:11Z",
  "reactorRoot": "/ws/billing",
  "buildTool": { "name": "maven", "version": "3.9.6" },
  "regenerate": ["./mvnw", "-q", "-B", "process-classes"],
  "modules": [
    {
      "gav": "com.example:billing-core:1.4.0",
      "name": "billing-core",
      "mainOutputDirs": ["/ws/billing/billing-core/target/classes"],
      "testOutputDirs": ["/ws/billing/billing-core/target/test-classes"],
      "sourceRoots": ["/ws/billing/billing-core/src/main/java"],
      "testSourceRoots": ["/ws/billing/billing-core/src/test/java"],
      "classpath": [
        { "gav": "com.example:billing-api:1.4.0",         "internal": true,  "jar": null },
        { "gav": "com.google.guava:guava:33.0.0-jre",     "internal": false, "jar": "/home/u/.m2/…/guava-33.0.0-jre.jar" }
      ],
      "testClasspath": [
        { "gav": "com.example:billing-api:1.4.0",          "internal": true,  "jar": null },
        { "gav": "com.google.guava:guava:33.0.0-jre",      "internal": false, "jar": "/home/u/.m2/…/guava-33.0.0-jre.jar" },
        { "gav": "org.junit.jupiter:junit-jupiter:5.11.0", "internal": false, "jar": "/home/u/.m2/…/junit-jupiter-5.11.0.jar" },
        { "gav": "org.assertj:assertj-core:3.26.3",        "internal": false, "jar": "/home/u/.m2/…/assertj-core-3.26.3.jar" }
      ]
    }
  ],
  "buildFiles": [
    { "path": "/ws/billing/billing-core/pom.xml", "sha256": "3f1c…" },
    { "path": "/ws/billing/pom.xml",              "sha256": "9a07…" }
  ]
}
```

### Top level

| Field | Type | Meaning |
|-------|------|---------|
| `schemaVersion` | int | Manifest schema version (`2`). Advisory — see §4. |
| `generator` | string | Producer and its version, e.g. `io.hierograph.maven.plugin/<version>`; informational. |
| `generatedAt` | string | ISO-8601 instant the manifest was written; informational. |
| `reactorRoot` | string | Absolute path of the build root on the build host. The anchor for path portability (§3). |
| `buildTool` | object? | The producing tool, `{ "name", "version" }`; informational (§1.2). |
| `regenerate` | string[]? | The command that rewrites this manifest (§1.2). |
| `modules` | array | One entry per module of the build, compiled or not, sorted by `gav`. |
| `buildFiles` | entry[] | The files that define the build, with content hashes (§1.1), sorted by `path`. |

### Module

| Field | Type | Meaning |
|-------|------|---------|
| `gav` | string | `group:name:version` — the module's artifact key and node identity. |
| `name` | string | Display name. Falls back to `gav` if absent. |
| `mainOutputDirs` | string[] | Absolute paths of the main output directories. Need not exist yet. |
| `testOutputDirs` | string[] | Absolute paths of the test output directories. Need not exist yet. |
| `sourceRoots` | string[] | Absolute main source directories. Recorded; reserved (§5). |
| `testSourceRoots` | string[] | Absolute test source directories. Recorded; reserved (§5). |
| `classpath` | entry[] | The module's **main** classpath, in classpath order (§2). |
| `testClasspath` | entry[] | The module's **test** classpath, in classpath order (§2). |

**Why output directories are lists.** A module's main (or test) output may be
compiled into several directories, one per compiler, for example Java and Kotlin
classes of one source set. Together they form one artifact: their classes resolve
against one classpath, appear as one module, and change together. The Maven
producer records one directory per list (`target/classes`,
`target/test-classes`).

### Classpath entry

| Field | Type | Meaning |
|-------|------|---------|
| `gav` | string | `group:name:version` of the dependency. |
| `internal` | boolean | `true` = a module of the same build (scanned from its own output); `false` = an external dependency. |
| `jar` | string? | Resolved local path of an external dependency's jar; `null` for an internal entry. Recorded; reserved (§5). |

### 1.1 Build files

| Field | Type | Meaning |
|-------|------|---------|
| `path` | string | Absolute path of a file that defines the build. |
| `sha256` | string | Lower-case hex SHA-256 of the file's content when the manifest was merged. |

The producer lists what its build tool treats as the build definition. The Maven
producer lists every project's POM, including `pom`-packaged aggregators; each
project's parent POM when it was read from a local file rather than the local
repository, which covers a parent outside the build root; and `.mvn/maven.config`
and `.mvn/extensions.xml` of the multi-module project directory when present.

**Why build files are recorded.** The module set and the classpaths are facts only
the build tool computes. When a build file changes, the manifest may not match the
build definition until the build runs again. The hashes let the server decide this
exactly: a build file whose current content differs from its recorded hash is
**stale**, a missing file is stale, and an edit that is reverted is not. Any build
that merges after the edit records the edited file's hash, so the manifest changes
and the staleness clears.

**Why the build tool's answer and not a file search.** Searching the tree for
`pom.xml` finds POMs that are not part of the build (test fixtures under
`src/test/resources`, archetype templates, directories not listed in
`<modules>`) and misses parents outside the tree. A module or nested aggregator
added to the build always appears through an edit of an already listed file, so
the list needs no search to stay complete.

### 1.2 Build tool and regeneration command

`buildTool` names the producing tool and its version on the build host. It is
informational: the server shows it in `build_state` and in its logs, and never
branches on it.

`regenerate` is the command that rewrites the manifest, as an argument vector run
in the build root. An argument starting with `./` is relative to the build root;
other arguments are passed unchanged, so a bare program name is looked up on the
`PATH`. The producer writes the command for the build host's operating system.
The field is absent when the producer cannot name a command.

**Why the producer names the command.** The fitting command differs between build
tools in substance, not only in syntax, and only the producer knows its tool. The
Maven producer writes `./mvnw -q -B process-classes` when the build root holds a
Maven wrapper (`./mvnw.cmd` on Windows), otherwise `mvn -q -B process-classes`. It
uses `process-classes` rather than a bare `hierograph:create-descriptor`, because
without a lifecycle phase Maven 3 does not resolve sibling modules from their
output directories. The server stays free of build-tool knowledge.

**Why an argument vector.** A single command string needs a shell and quoting
rules that differ between platforms; a vector runs directly.

---

## 2. Classpaths

A reference in bytecode carries only a bare type name; when several artifacts on
the classpath define that name, resolution must pick the one the code was actually
compiled against. The manifest therefore records each module's classpath **in
classpath order**, the order the build tool compiles against, and the server
builds a per-owner classpath-order type resolver from it (first definer on the
owner's classpath wins).

**Two classpaths, because main and test code resolve differently.**

- **`classpath` (main)** — the dependencies main bytecode is resolved against.
  The Maven producer records every resolved dependency whose scope is **not**
  `test`: `compile`, `runtime`, `provided`, and `system`. (`provided` and `system`
  matter: a class compiled against a `provided` API references its types, so
  dropping them would leave those references dangling as external placeholders.)
- **`testClasspath` (test)** — the **full** test classpath, against which test
  bytecode (JUnit, AssertJ, Mockito, …) is resolved. The Maven producer records
  every resolved dependency, test scope included. It is recorded as the complete
  list, not a delta over `classpath`, so the consumer never has to merge two lists.

**Entries list dependencies only; the owner is implicit.** Neither list contains
the module itself — the consumer prepends the owner as index 0 (the owner's own
types resolve first). Concretely, the resolver order the server builds is:

- main artifact `g:a:v` → `[g:a:v]` + `classpath[].gav`
- test artifact `g:a:v:test` → `[g:a:v:test, g:a:v]` + `testClasspath[].gav`

(The test artifact is prepended with both itself and the **main** module, so a test
type resolves against its own output, then the module under test, then the test
classpath.)

---

## 3. Path portability

Every path in the manifest — `reactorRoot`, the output directories,
`sourceRoots`, each entry's `jar`, and each build file — is an **absolute path on
the build host**. The manifest is nonetheless portable across machines by one
rule:

> The manifest always lives at `<reactorRoot>/.hierograph/manifest.json`. The
> consumer derives the **local** build root as the manifest file's
> grandparent directory, and re-roots any recorded path that lies under the
> recorded `reactorRoot` onto that local root.

On the build host the recorded and local roots are equal, so re-rooting is the
identity. When the server runs elsewhere — e.g. in a container with the workspace
mounted at a different path — the two roots differ and every path inside the build
(the output directories, the build files) is relocated correctly. The
`./`-relative arguments of `regenerate` resolve against the local build root.

**Limitation:** a path **outside** the build tree is left exactly as recorded.
External dependency `jar` paths (typically in the build tool's local repository)
therefore do not relocate, which is one reason the consumer resolves dependencies
by `gav`, not by `jar` (§5).

---

## 4. Parsing & compatibility

The consumer parses the manifest **leniently**, which defines the compatibility
contract:

- **Unknown fields are ignored.** A newer producer may add fields an older consumer
  does not know; they are skipped, not errors.
- **Missing fields take defaults.** Absent strings default empty, absent arrays
  empty, `internal` defaults `false`, `jar` defaults null, `schemaVersion`
  defaults `0`, and `buildTool` and `regenerate` default absent. An older
  producer's manifest still loads.
- **Single output directories are read as lists.** A module without
  `mainOutputDirs` / `testOutputDirs` is read from the single-directory fields
  `mainOutputDir` / `testOutputDir` (schema version 1), as a list of one.

Together these make the format forward- and backward-compatible for consumers, so
additive changes (such as `testClasspath`) need no coordinated upgrade.

`schemaVersion` is therefore **advisory**: it is recorded for diagnostics and as a
hard-incompatibility signal, but the consumer does not reject a manifest on
version alone. It is bumped only on a shape change that a consumer of the previous
version cannot read; version `2` replaced the single output directories with lists.
New behaviour is gated on the presence of fields rather than on the version number.

---

## 5. Recorded but reserved

Two fields are captured by the producer and carried in the manifest but **not
consumed** by the server. They are recorded so the format does not change when a
consumer starts using them:

- **`jar`** (classpath entries) — the resolved local path of an external
  dependency. Reserved for API-scanning libraries the server has not cached, so
  references resolve *into* third-party types instead of stopping at a placeholder.
  The server resolves dependencies by `gav` only; the path would not survive
  relocation in any case (§3).
- **`sourceRoots`** / **`testSourceRoots`** (modules) — the module's main and test
  source directories, reserved for mapping graph nodes back to their source
  locations.

A consumer must tolerate their presence and absence; a producer should populate
them when it cheaply can.

---

## 6. How the server consumes the manifest

In manifest mode the server:

1. Reads the manifest and builds the `PathRemap` from `reactorRoot` (§3).
2. For each module: records its `classpath` (keyed by `gav`), re-roots
   `mainOutputDirs` and **declares** them as one output. A declared output with at
   least one existing directory is scanned with the ASM bytecode scanner, every
   existing directory under the module's key; an output with none is watched and
   scanned once one appears. It is an error for the manifest to declare *no*
   output directory.
3. When test scanning is enabled: re-roots and declares `testOutputDirs` as the
   artifact `<gav>:test`, resolved against `testClasspath`. A manifest without
   `testClasspath` (§4) resolves test types against `classpath` instead, so
   test-scope dependencies appear as external types.
4. Installs the per-owner classpath-order type resolver (§2) and ingests the
   scanned classes straight into the in-memory graph store; the Parquet detail
   snapshot is written separately, off the hot path.
5. Watches every declared output directory and re-scans only the modules whose
   classes change, rebuilding the graph from the cached union. An output whose
   directory appears is scanned and added; one whose directories have all
   disappeared (a clean build) drops the module's classes but keeps the
   declaration. It also watches the manifest file and, when it changes, applies it
   as a diff against the cached scans (only added, moved or changed outputs are
   scanned) and re-targets the watcher.
6. Watches the build files (§1.1) and, when one changes, compares the hashes and
   reports the stale build files; depending on its configuration it also runs the
   `regenerate` command (§1.2). Build-file paths under `reactorRoot` are re-rooted
   (§3); a manifest read under a different build root never triggers that command,
   because the build host's tools are not available.

When no declared output exists at startup (a fresh clone), the server starts
without a graph but keeps watching, and builds the graph when the first output
appears.

The manifest is thus a *scan plan plus resolution context*: it never contains
scanned facts, only the inputs the server needs to produce them.
