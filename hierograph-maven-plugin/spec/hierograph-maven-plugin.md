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

The plugin exposes four goals that serve two workflows.

```
 Local development (the server scans and watches your build)
 ────────────────────────────────────────────────────────────
   hierograph:create-descriptor   per module   →  target/hierograph/module.json
   hierograph:merge-descriptors   once (root)  →  .hierograph/manifest.json  ──►  MCP server (manifest mode)

 Publishing (a scanned archive travels as an artifact)
 ────────────────────────────────────────────────────────────
   hierograph:scan      per module   →  <artifactId>.hierograph          ──►  mvn deploy ──► repository
   hierograph:collect   once (root)  →  <artifactId>-collect.hierograph   (optional convenience)
```

| Goal | Scope | Default phase | Scans bytecode? | Primary output |
|------|-------|---------------|-----------------|----------------|
| `create-descriptor` | per module | `process-classes` | no | `module.json` descriptor |
| `merge-descriptors` | aggregator | `verify` | no | merged `manifest.json` |
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
> classpath — into a per-module descriptor. **Does not scan bytecode.**

Only the build tool knows a module's layout and its already-resolved classpath.
`create-descriptor` captures those facts into a small descriptor that
`merge-descriptors` later folds into the reactor manifest. It is cheap and has no
scanning cost, so it can run on every build.

- **Binding:** `process-classes`
- **Dependency resolution:** `test` (so the test classpath is available; the main classpath is its non-test subset)
- **Inputs:** `project.build.outputDirectory`, `project.build.testOutputDirectory`, `project.compileSourceRoots`, `project.testCompileSourceRoots`, and `project.artifacts` (all scopes).
- **Skips:** modules with `pom` packaging, and modules with no compiled output (logged, not a failure).

**Output** — one descriptor per module:

```
<module>/target/hierograph/module.json
```

A build-local file, consumed by `merge-descriptors`. It is not attached to the
project and not deployed.

**Parameters**

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `skip` | `hierograph.skip` | `false` | Skip the goal. |

---

### `hierograph:merge-descriptors`

> Merge every module's descriptor into one reactor manifest for a watching server.

An aggregator goal: it runs once on the top-level build, collects each module's
`module.json`, and merges them into a single manifest. The Hierograph MCP server
reads that manifest, scans each module's compiled output itself, and observes the
output directories for live updates — the fast inner loop for local analysis, with
no artifacts to publish.

- **Binding:** `verify` (aggregator)
- **Inputs:** every reactor module's `target/hierograph/module.json`.
- **Duplicate coordinates:** if two modules report the same GAV, the first is kept and a warning is logged.
- **Ordering:** modules are sorted by GAV for a stable file.

**Output** — one manifest for the reactor:

```
<reactor-root>/.hierograph/manifest.json
```

The reactor root is the build's execution root (the directory Maven was launched
in), so the manifest lands in the same place even when the build is narrowed with
`-pl`.

**Parameters**

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `manifestDir` | `hierograph.manifestDir` | `${session.executionRootDirectory}/.hierograph` | Directory the merged manifest is written to. |
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
container of the per-module archives, not a content merge.

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
| `merge-descriptors` | `.hierograph/manifest.json` | JSON manifest | no (build-local) | — |
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
  "mainOutputDir": "/ws/billing/billing-core/target/classes",
  "testOutputDir": "/ws/billing/billing-core/target/test-classes",
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
  "schemaVersion": 1,
  "generator": "io.hierograph.maven.plugin/<plugin-version>",
  "generatedAt": "2026-11-08T17:04:11Z",
  "reactorRoot": "/ws/billing",
  "modules": [ /* one module.json object per module, sorted by gav */ ]
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
├── refs.parquet          # type-level references (extends / implements / uses)
├── detail_refs.parquet   # member-level references (calls, field access, …)
└── classpath.parquet     # the module's ordered classpath (§5.4)
```

Each Parquet file carries **footer metadata** describing the scan, so an archive
is self-describing wherever it travels:

| Key | Meaning |
|-----|---------|
| `artifactKey` | the artifact's GAV |
| `origin` | `MODULE_MAIN`, `MODULE_TEST`, `LIBRARY`, or `JDK` |
| `contentHash` | sha-256 of the scanned bytecode (jar, or the class files of a directory) |
| `scannerVersion` | the scanner that produced it |
| `schemaVersion` | the table schema version |
| `scanMode` | `FULL` (bodies scanned) or `API` (signatures only) |

The `contentHash` ties an archive to the exact bytecode it was scanned from: a
consumer can detect an archive that no longer matches its artifact and rescan.

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
  watch the output directories for changes. Paired with `create-descriptor` +
  `merge-descriptors`; the inner loop for local development.
- **`repository`** — given a root GAV or an explicit classpath, resolve the
  `hierograph`-type archives across the dependency closure and assemble the link
  set. An artifact without a published archive is scanned on demand from its jar
  and cached by content hash, so published archives are an optimization, not a
  requirement. Paired with `scan`.
- **`parquet`** — load a prepared directory of Parquet tables directly. Paired
  with `collect` (unpacked) or any archive set assembled by hand.

In every case the server computes cross-artifact facts (overrides, virtual-call
resolution) over the assembled link set at load time, so the same archive serves
correctly in any combination of dependencies.

---

## 7. Usage

### 7.1 Local development — a watching server

Bind `create-descriptor` per module and `merge-descriptors` once at the root:

```xml
<plugin>
  <groupId>io.hierograph</groupId>
  <artifactId>hierograph-maven-plugin</artifactId>
  <version>${hierograph.version}</version>
  <executions>
    <execution>
      <goals>
        <goal>create-descriptor</goal>   <!-- per module, process-classes -->
        <goal>merge-descriptors</goal>   <!-- once at the root, verify -->
      </goals>
    </execution>
  </executions>
</plugin>
```

`mvn verify` writes `.hierograph/manifest.json`; start the server in manifest mode
pointed at the reactor root.

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
| `hierograph.manifestDir` | `merge-descriptors` | `${session.executionRootDirectory}/.hierograph` | Manifest output directory. |
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
