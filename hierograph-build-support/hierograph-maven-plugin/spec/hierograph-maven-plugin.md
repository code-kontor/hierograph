# Hierograph Maven Plugin — Specification

The Hierograph Maven Plugin connects a Maven build to Hierograph's architectural
analysis. It reports what only the build knows — each module's compiled output,
its coordinates, and its resolved dependency classpath — and, on demand, scans
that compiled output into a portable archive that can be published to an artifact
repository and consumed anywhere.

The plugin never embeds a database or a server. It produces **files and
artifacts**; the Hierograph MCP server consumes them. This separation is
deliberate: a build stays fast and side-effect-free, and a scanned graph becomes
something you can version, deploy, and resolve by coordinates like any other
artifact.

- **Group ID / Artifact ID:** `io.hierograph` / `hierograph-maven-plugin`
- **Goal prefix:** `hierograph`
- **Requires:** Maven 3.9+, Java 21
- **Thread-safe:** yes (all goals)

---

## 1. Overview

The plugin exposes five goals that serve two workflows.

```
 Local development (the server scans and watches your build)
 ────────────────────────────────────────────────────────────
   hierograph:create-descriptor   per module   →  target/hierograph/module.json
                                  + merge      →  .hierograph/manifest.json  ──►  MCP server (manifest mode)
   hierograph:manifest            once (root)  →  <manifestDir>/manifest.json   (no compile, no write into the project)

 Publishing (a scanned archive travels as an artifact)
 ────────────────────────────────────────────────────────────
   hierograph:scan      per module   →  <artifactId>.hierograph          ──►  mvn deploy ──► repository
   hierograph:collect   once (root)  →  <artifactId>-collect.hierograph   (optional convenience)
```

| Goal | Scope | Default phase | Scans bytecode? | Primary output |
|------|-------|---------------|-----------------|----------------|
| `create-descriptor` | per module | `process-classes` | no | `module.json` descriptor and merged `manifest.json` |
| `merge-descriptors` | aggregator | `verify` | no | merged `manifest.json` (optional) |
| `manifest` | aggregator | none (invoked directly) | no | `manifest.json`, written without compiling or touching the project |
| `scan` | per module | `package` | yes | attached `.hierograph` archive |
| `collect` | aggregator | `verify` | yes (collects) | attached combined archive |

Both workflows are independent and may be enabled together: a reactor can emit a
manifest for a watching local server *and* publish archives from the same build.

---

## 2. Concepts

**Artifact identity.** Every scanned artifact is keyed by its full Maven
coordinates — `groupId:artifactId:version` (**GAV**). The GAV is stable, unique
across modules, and models multiple versions of the same library on one
classpath. The `artifactId` alone is kept as the human-readable display name.

**Artifact type.** A published archive is stored under a dedicated Maven type,
`hierograph`, with the file extension `.hierograph`. Maven maps the type to that
extension by default, so `mvn deploy` publishes the archive with the right name and
no classifier is needed to tell a module's archive apart from its jar. Making
`hierograph` a fully first-class type for consumer-side, *type-aware* resolution
needs a dedicated artifact handler, which is deferred (§10).

**Archive.** The scan of one artifact's bytecode, written as a `.hierograph`
file: a set of Parquet tables holding **facts** — types, members, parameters,
and the member-level references a single class file can answer (calls, field
reads/writes, return/parameter/throws types, annotations). An archive describes
one artifact in isolation.

**Descriptor.** A small per-module file (`module.json`) recording the build-only
facts about a module — its coordinates, compiled-output directories, source roots,
and resolved main and test classpaths in Maven order. It carries no scanned
bytecode; it is the input a watching server needs to scan the module itself.

**Classpath manifest.** The ordered lists of dependencies a module compiles
against — one for main code, one for test — captured from the build in Maven
resolution order. They let Hierograph resolve a bare type name to the right
artifact when several define it, exactly as the classpath would at runtime. They
appear in a descriptor as JSON and inside an archive as `classpath.parquet`.

**Manifest.** A single JSON file describing every module of a reactor — produced
by merging the per-module descriptors — written for a local server that scans the
build itself and watches it for changes.

**Link set.** The set of archives loaded together. Cross-artifact facts
(overrides, virtual-call targets) depend on *which* artifacts are present, so they
are computed when the link set is assembled, never baked into an individual
archive. This is what makes a library's archive reusable across projects.

---

## 3. Goals

### `hierograph:create-descriptor`

> Record a module's build facts — coordinates, output directories, and resolved
> classpath — into a per-module descriptor, then merge every module's descriptor
> into the reactor manifest. **Does not scan bytecode.**

Only the build tool knows a module's layout and its already-resolved classpath.
`create-descriptor` captures those facts into a small descriptor and folds all
descriptors of the reactor into the manifest. It is cheap and has no scanning
cost, so it runs on every build: any build that reaches `process-classes`,
including a build narrowed with `-pl`, keeps the manifest in step with the module
set. `mvn compile` stops one phase earlier and does not run it.

- **Binding:** `process-classes`
- **Dependency resolution:** `test` (so the test classpath is available; the main classpath is its non-test subset)
- **Inputs:** `project.build.outputDirectory`, `project.build.testOutputDirectory`, `project.compileSourceRoots`, `project.testCompileSourceRoots`, and `project.artifacts` (all scopes).
- **Skips:** modules with `pom` packaging.

**Modules without compiled output** get a descriptor too. The output directories
are recorded as configured, whether or not they exist, and both classpaths are
resolved. A watching server then observes the directories and scans the classes
once they appear, for example after the IDE's first compile of a module created
in the IDE.

**Output** — one descriptor per module, and the reactor manifest:

```
<module>/target/hierograph/module.json
<reactor-root>/.hierograph/manifest.json
```

Both are build-local files. They are not attached to the project and not
deployed.

#### The merge

The merge reads the descriptors of **every project in the reactor**
(`session.allProjects`), which is the full reactor even when the build is
narrowed with `-pl`. This gives the manifest these properties:

| Change | Effect on the manifest |
|--------|------------------------|
| A module is removed from `<modules>` | its entry disappears, even if its directory and descriptor remain on disk |
| A module's `artifactId` changes | its descriptor is rewritten with the changed GAV; the previous GAV disappears |
| A module is not built in this run | its last descriptor is used unchanged |
| A module's descriptor is missing (after `mvn clean`, not rebuilt yet) | its entry from the existing manifest is kept |
| Two modules report the same GAV | the first is kept and a warning is logged |

Modules are sorted by GAV for a stable file.

**Build files.** The merge also records the files that define the reactor, each
with the SHA-256 of its content: every project's POM (aggregators included), each
project's parent POM when it was read from a local file rather than the local
repository, and `.mvn/maven.config` and `.mvn/extensions.xml` of the multi-module
project directory. A watching server compares the hashes with the files' current
content to detect a POM edited since the merge. Hashes are cached per file while
its size and modification time are unchanged, so a full build hashes each file
once.

**Build tool and regeneration command.** The manifest names its producer's tool,
`buildTool: { "name": "maven", "version": <the running Maven version> }`, and the
command a watching server runs to rewrite it: `./mvnw` when the execution root
holds a Maven wrapper (`./mvnw.cmd` on Windows), otherwise `mvn` (`mvn.cmd` on
Windows), with `-q -B`, the build's explicitly active profiles (`-P…`), and
`process-classes`. `process-classes` rather than a bare
`hierograph:create-descriptor`: without a lifecycle phase, Maven 3 does not
resolve reactor siblings from their output directories. The plugin names the
command because only it knows Maven; the server runs it without knowing the tool.

**Shared with other build tools.** The merge rules above, write-only-on-change,
the atomic write, the locking and the build-file hashing live in the
[`io.hierograph.manifest` library](../../io.hierograph.manifest/spec/hierograph-manifest.md).
The plugin contributes what only Maven knows: the projects of the reactor, their
classpaths, the build files and the regeneration command.

**Written only on change.** The merge compares the result with the existing
manifest, ignoring `generatedAt`, and rewrites the file only when something else
differs. A watching server treats a changed manifest as a module-set change, so a
rebuild of an unchanged module set must not touch it. The descriptor follows the
same rule.

**Atomic and serialised.** Both files are written to a temporary file and moved
into place atomically, so a reader never sees a partial file. Merges are
serialised within one Maven process (parallel module builds with `-T`) and across
processes by an exclusive lock on `.hierograph/manifest.lock` (a CLI build and an
IDE build at the same time).

The reactor root is the build's execution root (the directory Maven was launched
in), so the manifest lands in the same place even when the build is narrowed with
`-pl`. A build launched inside a module directory has that directory as its
execution root and a reactor of that module only; it writes its own manifest
below that directory and leaves the reactor's manifest untouched.

A build narrowed with `-pl` resolves reactor siblings only when they are part of
the build or installed: `mvn -pl billing-core process-classes` fails to resolve an
uninstalled `billing-api`, while `mvn -pl billing-core -am process-classes`
succeeds. This is Maven's resolution behaviour.

**Parameters**

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `manifestDir` | `hierograph.manifestDir` | `${session.executionRootDirectory}/.hierograph` | Directory the merged manifest is written to. |
| `skip` | `hierograph.skip` | `false` | Skip the goal. |

---

### `hierograph:merge-descriptors`

> Merge every module's descriptor into one reactor manifest, without writing
> descriptors.

An aggregator goal that runs the merge of `create-descriptor` once on the
top-level build. Because `create-descriptor` already merges, a build does not need
to bind it; it is there to merge explicitly, for example from the command line.

- **Binding:** `verify` (aggregator)
- **Inputs:** every reactor module's `target/hierograph/module.json`.
- **Behaviour:** identical to the merge of `create-descriptor` (duplicates, ordering, change detection, atomic write).

**Output** — one manifest for the reactor:

```
<reactor-root>/.hierograph/manifest.json
```

**Parameters**

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `manifestDir` | `hierograph.manifestDir` | `${session.executionRootDirectory}/.hierograph` | Directory the merged manifest is written to. |
| `skip` | `hierograph.skip` | `false` | Skip the goal. |

---

### `hierograph:manifest`

> Write the build manifest for the whole reactor in one step — **without a
> lifecycle phase, without compiling, and without writing anything into the
> project.**

`create-descriptor` depends on Maven's own dependency resolution. Under Maven 3
that resolves a reactor sibling only from its packaged artifact or, after a
lifecycle phase in the same build, from its compiled output — so it needs
`process-classes`, which compiles, and it writes descriptors into `target/`. A
manifest needs much less from a sibling: its classpath entry is internal, with
`jar: null`, and only its coordinates matter.

`manifest` therefore resolves itself. For each reactor project it collects the
dependency graph with Maven's `ProjectDependenciesResolver` — POMs only, the
siblings' POMs from the reactor — and resolves only the artifacts that are **not**
reactor projects. The classpath is the graph in pre-order, first occurrence wins:
the order in which Maven builds `project.artifacts`. The result equals what
`create-descriptor` records for the same project, except `sourceRoots`, which do
not include source directories that only a lifecycle phase adds (such as
`target/generated-sources/annotations`).

- **Binding:** none; invoke as `io.hierograph:hierograph-maven-plugin:<version>:manifest`.
- **Aggregator:** yes — one execution covers `session.allProjects`, the full reactor.
- **Dependency resolution:** done by the goal; reactor siblings are never resolved to files.
- **Writes:** only `manifest.json` (and `manifest.lock`) in `manifestDir`. No descriptor files, nothing below the project.
- **Skips:** modules with `pom` packaging.

The merge rules, the build files, write-only-on-change and the atomic write are
those of `create-descriptor`. The regeneration command it records reruns this
goal: `mvn -q -B [-P…] io.hierograph:hierograph-maven-plugin:<version>:manifest`
(`./mvnw` with a wrapper).

**Where it is used.** The Hierograph Docker image regenerates the manifest with
this goal inside the container, with the project and the host's `~/.m2` mounted
read-only. On a host it is the cheapest explicit command: no `process-classes`,
no installed siblings.

A missing **external** dependency fails the goal: the manifest would be wrong
without it.

**Parameters**

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `manifestDir` | `hierograph.manifestDir` | `${session.executionRootDirectory}/.hierograph` | Directory the manifest is written to. |
| `skip` | `hierograph.skip` | `false` | Skip the goal. |

---

### `hierograph:scan`

> Scan a module's compiled classes into a `.hierograph` archive and attach it to
> the build, so `mvn install` / `mvn deploy` publishes it beside the jar.

This is the only goal that reads bytecode, and the one that turns a scan into a
distributable artifact. It scans the module's compiled classes into a
self-contained archive — the facts **and** the module's classpath manifest —
keyed by the module's GAV, then attaches it as a secondary artifact of type
`hierograph`. Published this way, an archive is resolvable by coordinates: a
consumer can build a graph of a module, or a whole application, from the
repository without ever seeing its source.

- **Binding:** `package` (the primary artifact exists, so attachment is well-defined; the classes to scan are already compiled).
- **Dependency resolution:** `test` (the main archive records the non-test classpath; a test archive records the full test classpath)
- **Inputs:** `project.build.outputDirectory` (and `project.build.testOutputDirectory` with `includeTestClasses`), and `project.artifacts` for the classpath manifests.
- **Skips:** `pom` packaging and modules with no compiled output.

**Output** — one attached artifact per module:

```
file:      <module>/target/hierograph/<artifactId>.hierograph
artifact:  <groupId>:<artifactId>:<version>   ·   type hierograph   ·   no classifier
```

Published as `…-<version>.hierograph` next to the module's own jar by `mvn
install` / `mvn deploy`. With `includeTestClasses`, the test output is scanned
into a second archive carrying the `tests` classifier
(`…-<version>-tests.hierograph`, also type `hierograph`).

**Parameters**

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `outputDirectory` | — | `${project.build.outputDirectory}` | Compiled classes to scan. |
| `archiveDirectory` | `hierograph.archiveDir` | `${project.build.directory}/hierograph` | Where the archive is written before it is attached. |
| `attach` | `hierograph.attach` | `true` | Attach the archive (and thus deploy it). When `false`, the archive is written but not published. |
| `includeTestClasses` | `hierograph.includeTestClasses` | `false` | Also scan `testOutputDirectory` into a second archive classified `tests`. |
| `threads` | `hierograph.threads` | available processors | Scanner parallelism. |
| `skip` | `hierograph.skip` | `false` | Skip the goal. |

---

### `hierograph:collect`

> Collect every module's archive into one artifact attached to the top-level
> project — a single file to hand a server when resolving from a repository is not
> an option.

An optional convenience. Where `scan` publishes one archive per module (the
preferred shape, because each is reusable and independently versioned), `collect`
gathers them into a single file for consumers that want exactly one input. It runs
once at the root and attaches the result to the top-level project. It is a
container of the per-module archives, not a content merge. Readers use the container
as it is, without unpacking it (see the snapshot format, §2.3).

- **Binding:** `verify` (aggregator)
- **Inputs:** each module's `<artifactId>.hierograph` archive (and its `tests` archive, when present). A duplicate archive name from two modules is kept once (first wins).

**Output** — one attached artifact for the reactor:

```
file:      <root>/target/hierograph-collect/<artifactId>-collect.hierograph
artifact:  <root-groupId>:<root-artifactId>:<version>   ·   type hierograph   ·   classifier collect
```

**Parameters**

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `outputDirectory` | `hierograph.collectDir` | `${session.topLevelProject.build.directory}/hierograph-collect` | Where the combined archive is assembled. |
| `attach` | `hierograph.attach` | `true` | Attach the combined archive to the top-level project. |
| `skip` | `hierograph.skip` | `false` | Skip the goal. |

---

## 4. Artifacts and files produced

| Produced by | Name | Form | Published? | Coordinates |
|-------------|------|------|------------|-------------|
| `create-descriptor` | `target/hierograph/module.json` | JSON descriptor | no (build-local) | — |
| `create-descriptor`, `merge-descriptors`, `manifest` | `.hierograph/manifest.json` | JSON manifest | no (build-local) | — |
| `scan` | `<artifactId>.hierograph` | Parquet archive | yes | type `hierograph`, no classifier |
| `scan` (tests) | `<artifactId>.hierograph` | Parquet archive | yes | type `hierograph`, classifier `tests` |
| `collect` | `<artifactId>-collect.hierograph` | archive of archives | yes | type `hierograph`, classifier `collect` |

The published artifacts (`scan`, `collect`) all share the dedicated `hierograph`
type (§2); the local files (`create-descriptor`, `merge-descriptors`) stay on disk
and are never deployed.

---

## 5. File formats

### 5.1 `module.json` — the per-module descriptor

```json
{
  "gav": "com.example:billing-core:1.4.0",
  "name": "billing-core",
  "mainOutputDirs": ["/ws/billing/billing-core/target/classes"],
  "testOutputDirs": ["/ws/billing/billing-core/target/test-classes"],
  "sourceRoots": ["/ws/billing/billing-core/src/main/java"],
  "testSourceRoots": ["/ws/billing/billing-core/src/test/java"],
  "classpath": [
    { "gav": "com.example:billing-api:1.4.0",          "internal": true,  "jar": null },
    { "gav": "com.google.guava:guava:33.0.0-jre",      "internal": false, "jar": "/home/u/.m2/…/guava-33.0.0-jre.jar" }
  ],
  "testClasspath": [
    { "gav": "com.example:billing-api:1.4.0",          "internal": true,  "jar": null },
    { "gav": "com.google.guava:guava:33.0.0-jre",      "internal": false, "jar": "/home/u/.m2/…/guava-33.0.0-jre.jar" },
    { "gav": "org.junit.jupiter:junit-jupiter:5.11.0", "internal": false, "jar": "/home/u/.m2/…/junit-jupiter-5.11.0.jar" }
  ]
}
```

Both classpaths are in Maven resolution order and list **dependencies only** — the
module itself is implicit (the consumer prepends it). `classpath` holds the
module's non-test scopes (compile + runtime + provided + system); `testClasspath`
the full test classpath. An `internal` entry is a reactor sibling (scanned from its
own output, so `jar` is null); an external entry carries the resolved jar path. The
authoritative, field-level definition of this object and the merged manifest is the
**build-manifest specification** (`hierograph-server/io.hierograph.server.graphservice/spec`).

### 5.2 `manifest.json` — the merged reactor manifest

```json
{
  "schemaVersion": 2,
  "generator": "io.hierograph.maven.plugin/<plugin-version>",
  "generatedAt": "2026-11-08T18:04:11+01:00",
  "reactorRoot": "/ws/billing",
  "buildTool": { "name": "maven", "version": "3.9.6" },
  "regenerate": ["./mvnw", "-q", "-B", "process-classes"],
  "modules": [ /* one module.json object per module, sorted by gav */ ],
  "buildFiles": [ { "path": "/ws/billing/pom.xml", "sha256": "9a07…" } ]
}
```

The server reads this in manifest mode; its fields, the path-portability rule and
the compatibility contract are specified in the **build-manifest specification**
(`hierograph-server/io.hierograph.server.graphservice/spec`).

### 5.3 The `.hierograph` archive

The file `scan` produces: a container of Parquet tables holding one artifact's
scan, published under the `hierograph` type.

```
<artifactId>.hierograph
├── types.parquet         # the artifact's types
├── members.parquet       # methods and fields
├── parameters.parquet    # method parameters
├── dependencies.parquet  # every dependency, one detail fact per row (extends, calls, field access, …)
└── classpath.parquet     # the module's ordered classpath (§5.4)
```

The tables are defined in the
[snapshot format specification](../../../hierograph-graphstore/io.hierograph.graphstore.jvm.archive/spec/hierograph-graphstore-format.md).
Each table carries the format version in its footer key-value metadata
(`hierograph.format.version`), so an archive says which format it was written in
wherever it travels. A consumer rejects an archive of an older format, which has to
be rescanned, and one of a newer format, which needs a newer Hierograph. The
artifact's key is stored in the tables themselves (`types.artifact`).

### 5.4 `classpath.parquet`

A flat table of the owning module's classpath, one row per entry, ordered by
`index` (0 = the module itself, then its dependencies in resolution order):

| Column | Type | Meaning |
|--------|------|---------|
| `artifact_key` | string | the owning artifact's GAV (repeated on each row) |
| `name` | string | the owning artifact's display name |
| `index` | int | position in the classpath; 0 is the artifact itself |
| `dep_key` | string | GAV of the entry at this index |
| `internal` | boolean | true = a scanned reactor sibling; false = an external dependency |

---

## 6. How the server consumes the outputs

The Hierograph MCP server builds its graph from one of three sources, selected by
`hierograph.graph.source`:

- **`manifest`** — read `manifest.json`, scan each module's compiled output, and
  watch the output directories for changes. Paired with `create-descriptor`;
  the inner loop for local development.
- **`repository`** — given a root GAV or an explicit classpath, resolve the
  `hierograph`-type archives across the dependency closure and assemble the link
  set. An artifact without a published archive is scanned on demand from its jar
  and cached by content hash, so published archives are an optimization, not a
  requirement. Paired with `scan`.
- **`parquet`** — load a prepared snapshot directly: a directory of archives or
  exploded artifacts, or a single `collect` container. Paired with `collect` or any
  archive set assembled by hand.

In every case the server computes cross-artifact facts (overrides, virtual-call
resolution) over the assembled link set at load time, so the same archive serves
correctly in any combination of dependencies.

---

## 7. Usage

### 7.1 Local development — a watching server

Bind `create-descriptor`; it writes each module's descriptor and merges the
manifest:

```xml
<plugin>
  <groupId>io.hierograph</groupId>
  <artifactId>hierograph-maven-plugin</artifactId>
  <version>${hierograph.version}</version>
  <executions>
    <execution>
      <goals>
        <goal>create-descriptor</goal>   <!-- per module, process-classes -->
      </goals>
    </execution>
  </executions>
</plugin>
```

Any build reaching `process-classes` (`mvn compile` is not enough; `mvn
process-classes`, `mvn test` or later) writes `.hierograph/manifest.json`; start
the server in manifest mode pointed at the reactor root. After a POM change that
adds, removes or renames a module or changes dependencies, run such a build once;
class changes alone need no Maven run. The server reports a POM edited since the
last merge as a stale `build_state`, and can run the build itself (the
`hierograph.graph.watch.build-files` setting of the server).

### 7.2 Publishing archives

Bind `scan` so each module's scan is attached and deployed:

```xml
<plugin>
  <groupId>io.hierograph</groupId>
  <artifactId>hierograph-maven-plugin</artifactId>
  <version>${hierograph.version}</version>
  <executions>
    <execution>
      <goals>
        <goal>scan</goal>   <!-- per module, package -->
      </goals>
    </execution>
  </executions>
</plugin>
```

`mvn deploy` then publishes `…-<version>.hierograph` beside each module's jar. A
consumer selects repository mode and names the application's root coordinates; the
server resolves the archives and builds the graph.

To hand a single file to a server instead, add `collect`:

```xml
<goal>collect</goal>   <!-- once at the root, verify -->
```

---

## 8. Property reference

| Property | Goal(s) | Default | Purpose |
|----------|---------|---------|---------|
| `hierograph.skip` | all | `false` | Skip the goal. |
| `hierograph.manifestDir` | `create-descriptor`, `merge-descriptors`, `manifest` | `${session.executionRootDirectory}/.hierograph` | Manifest output directory. |
| `hierograph.archiveDir` | `scan` | `${project.build.directory}/hierograph` | Archive staging directory. |
| `hierograph.attach` | `scan`, `collect` | `true` | Attach (and deploy) the produced artifact. |
| `hierograph.includeTestClasses` | `scan` | `false` | Also scan test output. |
| `hierograph.threads` | `scan` | available processors | Scanner parallelism. |
| `hierograph.collectDir` | `collect` | `${session.topLevelProject.build.directory}/hierograph-collect` | Combined-archive assembly directory. |

---

## 9. The `hierograph` artifact type

Archives publish under a dedicated Maven type, `hierograph`, rather than borrowing
`jar` or `zip`. Today this works through Maven's default behavior: an unregistered
type's extension defaults to the type name, so `mvn deploy` uploads the archive as
`…-<version>.hierograph` and a repository stores and serves it by that extension —
no handler is required to produce or deploy it.

Making the type fully first-class — so a consumer resolves it *by type* (like `jar`
or `pom`) rather than by extension — needs a dedicated artifact handler with these
facets:

| Facet | Value |
|-------|-------|
| type / packaging | `hierograph` |
| extension | `hierograph` |
| language | *(none)* |
| added to classpath | no |
| includes dependencies | no |

Contributing that handler (which also makes the plugin a build extension) is
deferred (§10); the default-extension behavior above is sufficient for publishing
and deploying.

---

## 10. Deferred

Two capabilities are specified by intent but **not yet implemented**:

- **`scanDependencies`** (a `scan` option) — also scan a module's resolved
  dependency jars as their own archives, so references resolve into third-party
  code instead of stopping at an external placeholder. Deferred pending a decision
  on how those archives are published: a dependency's archive does not belong under
  the depending module's coordinates.
- **The `hierograph` artifact handler** (§9) — a dedicated handler that makes the
  type first-class for consumer-side, type-aware resolution. Producing and
  deploying archives already work via Maven's default type→extension mapping, so
  this is an enhancement, not a prerequisite.
