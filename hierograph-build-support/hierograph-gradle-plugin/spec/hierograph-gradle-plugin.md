# Hierograph Gradle Plugin — Specification

The Hierograph Gradle Plugin writes the [build manifest](../../../hierograph-server/io.hierograph.server.graphservice/spec/hierograph-build-manifest.md)
`.hierograph/manifest.json` for a Gradle build, so the Hierograph MCP server
analyses a Gradle project exactly as it analyses a Maven project. It is the
counterpart of the Maven plugin's [`hierograph:manifest`](../../hierograph-maven-plugin/spec/hierograph-maven-plugin.md)
goal: one task invocation records the build's modules, output directories, source
roots and resolved classpaths, **without compiling and without writing into the
project** apart from the manifest itself.

- **Plugin id:** `io.hierograph` (a settings plugin)
- **Artifact:** `io.hierograph:hierograph-gradle-plugin`, plus the plugin marker
  `io.hierograph:io.hierograph.gradle.plugin`, on Maven Central
- **Task:** `hierographManifest` on the root project
- **Requires:** Gradle 8.11+, running on Java 21+
- **Configuration cache:** not compatible; disabled for an invocation that runs the task

---

## Contents

1. [Scope](#1-scope)
2. [Where it sits](#2-where-it-sits)
3. [Plugin, task and application](#3-plugin-task-and-application)
4. [Mapping a Gradle build to the manifest](#4-mapping-a-gradle-build-to-the-manifest)
5. [Resolution without building](#5-resolution-without-building)
6. [Build files](#6-build-files)
7. [Regeneration command](#7-regeneration-command)
8. [Usage](#8-usage)
9. [Building and publishing the plugin](#9-building-and-publishing-the-plugin)
10. [Tests](#10-tests)
11. [Not covered yet](#11-not-covered-yet)

---

## 1. Scope

**Covered**

- Single Gradle builds, multi-project included, Groovy and Kotlin DSL.
- Projects with the `java` plugin, which `java-library`, `application`,
  `org.jetbrains.kotlin.jvm`, Spring Boot and others apply: their `main` and
  `test` source sets.
- The Java and Kotlin output directories of one source set as one module.
- Version catalogs, `buildSrc`, and included builds that only contribute build
  logic, as build files.
- The host: `./gradlew hierographManifest`, with the server as a local Java
  process in manifest mode.
- The Hierograph Docker container: the host writes the manifest, the container
  scans and serves it, with the project mounted read-only (§8.4).

**Not covered** — see §11. The main gaps are Android and Kotlin Multiplatform,
additional source sets, and composite builds whose included builds contribute
modules.

---

## 2. Where it sits

```
 ┌───────────────────────────┐   ┌───────────────────────────┐
 │ hierograph-maven-plugin   │   │ hierograph-gradle-plugin  │
 │  hierograph:manifest      │   │  hierographManifest task  │
 └─────────────┬─────────────┘   └─────────────┬─────────────┘
               ▼                               ▼
 ┌───────────────────────────────────────────────────────────┐
 │ io.hierograph.manifest                                     │
 │  ModuleDescriptor · MergeRequest · ManifestWriter.merge    │
 └─────────────────────────────┬─────────────────────────────┘
                               ▼
                  .hierograph/manifest.json
                  buildTool: gradle, regenerate: ./gradlew …
                               ▼
 ┌───────────────────────────────────────────────────────────┐
 │ server: ManifestGraphSource, watchers (unchanged)         │
 └───────────────────────────────────────────────────────────┘
```

The plugin collects what only Gradle knows and hands it to `ManifestWriter.merge`
as a `MergeRequest` with in-memory `ModuleSource`s, the same call `ManifestMojo`
makes. Merging, write-only-on-change, the atomic write, locking and build-file
hashing come from the [shared library](../../io.hierograph.manifest/spec/hierograph-manifest.md).
The server needs no Gradle-specific code.

| Class | Role |
|-------|------|
| `HierographSettingsPlugin` | the settings plugin: registers the task, collects the model once all projects are evaluated |
| `HierographManifestTask` | the task: resolves the classpaths and merges |
| `GradleManifest` | the Gradle side of the merge: modules, classpaths, build files, regeneration command (the counterpart of `MavenManifest`) |

---

## 3. Plugin, task and application

### 3.1 Plugin

`io.hierograph` is a **settings plugin** (`Plugin<Settings>`). It sees the whole
build in one place and can be applied from `settings.gradle(.kts)` or from an init
script, without changing any project's build script.

It registers `hierographManifest` on the root project, and `hierographClasspaths`
on every project with the `java` plugin (§5). In `gradle.projectsEvaluated`, it hands
the tasks the collected model (§4). This happens only when the tasks are realized,
which normally means `hierographManifest` was requested. A build that applies the
plugin permanently pays nothing for it otherwise.

**Applied twice.** When the plugin is applied from both the settings and an init
script, the two applications load the class through different class loaders, so
Gradle treats them as two plugins. A marker on the `Gradle` object's extra
properties makes the second application a no-op.

### 3.2 Task `hierographManifest`

- **Does:** for every project with the `java` plugin, builds a
  `ModuleDescriptor` (§4), and calls `ManifestWriter.merge` with the build files
  (§6) and the regeneration command (§7).
- **Depends on:** only the `hierographClasspaths` task of every module project,
  which resolves that project's classpaths (§5). No `compileJava`, `classes`, `jar`
  or `processResources` task runs.
- **Writes:** only `manifest.json` and `manifest.lock` in the manifest directory.
- **Never up to date:** the library decides whether the file changes, and a
  regeneration must always re-check.
- **Logs** like the Maven goal:
  `hierographManifest: 12 module(s) → /ws/billing/.hierograph/manifest.json (unchanged)`.
  Skipped projects are logged at info level (`--info`).

### 3.3 Configuration cache

The task reads the model of every project and declares
`notCompatibleWithConfigurationCache(…)`. Gradle then disables the configuration
cache for an invocation that runs it, with a message, instead of failing. Normal
builds are not affected, because the task is never wired into them.

### 3.4 Properties

| Property | Default | Meaning |
|----------|---------|---------|
| `hierograph.manifestDir` | `<root project dir>/.hierograph` | directory the manifest is written to; a relative path resolves against the root project directory |
| `hierograph.skip` | `false` | skip the task; a bare `-Phierograph.skip` means `true` |

Both are read as Gradle properties (`-Phierograph.manifestDir=…`, or
`gradle.properties`) and as system properties (`-Dhierograph.manifestDir=…`). The
names equal the Maven plugin's properties.

### 3.5 Not wired into the build

Unlike `create-descriptor`, which the Maven plugin binds to `process-classes`, the
task is not attached to `classes` or any other lifecycle task. The server keeps the
manifest current: `hierograph.graph.watch.build-files=regenerate` runs the command
from §7 when a build file changes. Wiring the task into every build would disable
the configuration cache for all of them.

---

## 4. Mapping a Gradle build to the manifest

### 4.1 Top level

| Manifest | Gradle |
|----------|--------|
| `generator` | `io.hierograph.gradle.plugin/<plugin version>` |
| `reactorRoot` | the root project directory (`settings.rootDir`), absolute |
| `buildTool` | `{ "name": "gradle", "version": gradle.gradleVersion }` |
| `regenerate` | §7 |
| `modules` | one descriptor per project with the `java` plugin, sorted by GAV (by the library) |
| `buildFiles` | §6 |

### 4.2 Module

| Manifest | Gradle |
|----------|--------|
| `gav` | `group:name:version` of the project (§4.4) |
| `name` | `project.name` |
| `mainOutputDirs` | `sourceSets.main.output.classesDirs`, e.g. `build/classes/java/main` and `build/classes/kotlin/main`; the configured paths, which need not exist |
| `testOutputDirs` | `sourceSets.test.output.classesDirs` |
| `sourceRoots` | `sourceSets.main.allSource.srcDirs` without `sourceSets.main.resources.srcDirs`: Java, Kotlin, Groovy and so on, no resources, matching Maven's `compileSourceRoots` |
| `testSourceRoots` | the same for `test` |
| `classpath` | §4.3, from `compileClasspath` and `runtimeClasspath` |
| `testClasspath` | §4.3, from `testCompileClasspath` and `testRuntimeClasspath` |

Projects without the `java` plugin are skipped, as Maven skips `pom` packaging.
That covers the root project of most multi-project builds, BOM and platform
projects, and Android projects. A project with
`org.jetbrains.kotlin.multiplatform` is also skipped: it can carry the `java`
plugin, but its JVM code isn't in the `main` source set.

### 4.3 Classpath entries

A classpath is the **compile** configuration's entries in classpath order,
followed by the entries that occur only in the **runtime** configuration, in that
configuration's order. A component that occurs twice keeps its first position.
This matches Maven's main classpath, which is every non-`test` scope: `compile`,
`provided` ≈ `compileOnly`, and `runtime` ≈ `runtimeOnly`.

**Classpath order** is the order of the configuration's resolved artifacts, which
is the order Gradle passes to the compiler. It follows the configuration
hierarchy rather than the declaration order in the build script. For
`compileClasspath`, that's `api`, then `compileOnly`, then `implementation`.

**The test classpath has no `compileOnly` entries.** `testCompileClasspath`
extends `testCompileOnly` and `implementation`, not `compileOnly`, so a
`compileOnly` dependency is not on the test classpath. Maven's test classpath
includes `provided`. This is a real difference between the two tools, and the
manifest records it.

Per entry:

| Component identifier | `gav` | `internal` | `jar` |
|----------------------|-------|-----------|-------|
| `ProjectComponentIdentifier` of this build | that project's `gav` (§4.4) | `true` | `null` |
| `ModuleComponentIdentifier` | `group:module:version` | `false` | absolute path of the resolved file in Gradle's cache |
| `ProjectComponentIdentifier` of another (included) build | `group:name:version` of that project from the resolution result | `false` | `null`, so not scanned |
| anything else (file dependencies `files(…)`, `fileTree(…)`) | `file:<path relative to the build root>` | `false` | absolute path |

A project belongs to this build when its build-tree path is one of this build's
projects. A component with several artifacts, such as the Java and Kotlin class
directories of a sibling project or a classifier jar next to the main jar,
contributes one entry: the first. Platforms and BOMs (`platform(…)`,
`enforcedPlatform(…)`) have no artifact and do not appear.

### 4.4 Project coordinates

`gav` is `project.group:project.name:project.version`:

- an empty `group` (Gradle's default for the root project) falls back to the root
  project's name;
- a `version` of `unspecified` (Gradle's default) is kept as `unspecified`.

Gradle's default group for a subproject derives from its parent's path: `:a:util`
in the build `plain` gets `plain.a`. So two projects named `util` under `:a` and
`:b` get different GAVs. Two projects with the same explicit coordinates are a
duplicate GAV, which `ManifestWriter` reports, keeping the first.

---

## 5. Resolution without building

The Maven goal avoids compiling by collecting the dependency graph and resolving
only the artifacts that are not reactor projects. Gradle offers this directly:

- **Identities and order:** iterating a configuration's resolved artifacts
  (`incoming.artifacts`) yields each artifact's component identifier and file, in
  classpath order. For a project component, the file is only a location in that
  project's build directory. Listing it doesn't execute the task that produces it.
- **External files:** resolving the artifacts of module components downloads
  them into Gradle's cache, or finds them there. It never builds anything.

The plugin collects the configurations' `ResolvableDependencies` when the projects
are evaluated, and resolves them at execution. It doesn't declare them as task
inputs, so Gradle infers no dependency on the tasks that produce the sibling
projects' outputs.

**Resolved in the owning project.** A configuration may only be resolved while its
project's lock is held, and a task holds only the lock of its own project. A
parallel build (`org.gradle.parallel=true`) enforces this: resolving all projects'
configurations from the root task fails with "Resolution of the configuration … was
attempted without an exclusive lock". So every module project has a task
`hierographClasspaths` (no group) that resolves its own four configurations and
hands the classpaths to the shared model in memory; `hierographManifest` depends on
these tasks and only merges. In a parallel build they run in parallel.

**Guarantee:** a run of `hierographManifest` executes no task besides itself and
the `hierographClasspaths` tasks. The only exception is the tasks that build
`buildSrc` or included build logic, which Gradle runs before configuration. The TestKit tests assert the executed
tasks and that no file outside the manifest directory is created.

A dependency that cannot be resolved fails the task, naming the project and the
configuration, as the Maven goal does. The manifest would be wrong without the
dependency:

```
hierographManifest: cannot resolve the dependencies of :app (compileClasspath): …
```

---

## 6. Build files

The files whose change can change the manifest. The library hashes them, and the
server watches them. The plugin records when it was applied, before the projects'
build scripts are evaluated; a build file modified after that is recorded as
`changed-during-build` instead of its hash, because the build read its earlier
content. The files are:

- the settings script, and every `*.gradle` / `*.gradle.kts` next to it;
- every project's build script (`project.buildFile`), `gradle.properties` and the
  `*.gradle` / `*.gradle.kts` scripts in its directory;
- `gradle.properties` in the root project directory;
- under `gradle/`: every `*.toml` (version catalogs) and every `*.gradle` /
  `*.gradle.kts` (scripts applied with `apply from:`);
- `gradle/wrapper/gradle-wrapper.properties`;
- every file of `buildSrc` and of every included build (`gradle.includedBuilds`),
  excluding each project's `build/` directory and the tool state directories
  `.gradle/`, `.kotlin/`, `.git/`, `.idea/` and `.hierograph/`.

Only existing files inside the build root count. A user-home `gradle.properties`
and init scripts are not build files, which follows the Maven rule of excluding
the local repository.

**Heuristics, and their limits.** Gradle has no public API that lists applied
scripts or imported version-catalog files. So the plugin takes the scripts next to
the build scripts and under `gradle/`, and the catalogs under `gradle/`. A script
applied from elsewhere, or a catalog imported from another directory, is not
watched. A **new** file in `buildSrc` is not in the list either, and triggers no
regeneration until a listed file changes.

---

## 7. Regeneration command

The argument vector the server runs, in the build root, to rewrite the manifest:

```
./gradlew -q [--init-script <abs path>]… [-Phierograph.manifestDir=<abs path>] hierographManifest
```

- `./gradlew` when the root project directory holds a Gradle wrapper
  (`./gradlew.bat` on Windows), otherwise `gradle` (`gradle.bat`) from the `PATH`.
- Every init script given to the current invocation
  (`gradle.startParameter.initScripts`, so `--init-script` / `-I`, not
  `~/.gradle/init.d`) is repeated as `--init-script <absolute path>`. A manifest
  written through an init script is then regenerated the same way. This is the
  counterpart of repeating Maven's explicitly active profiles.
- `-Phierograph.manifestDir=<absolute path>` is repeated when the property was
  given.

A server can override the command through `hierograph.graph.regenerate.command`.

---

## 8. Usage

### 8.1 Applied in the settings

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
plugins {
    id("io.hierograph") version "<version>"
}
```

Then:

```
./gradlew hierographManifest
```

The plugin marker is on Maven Central, so `pluginManagement` must list
`mavenCentral()`. The plugin is not on the Gradle Plugin Portal.

### 8.2 Without changing the project

Gradle has no equivalent of Maven's `groupId:artifactId:version:goal` invocation.
An init script is the counterpart. The plugin jar ships this one as
`io/hierograph/gradle/plugin/hierograph.init.gradle`:

```groovy
initscript {
    repositories {
        mavenCentral()
        mavenLocal() // snapshots, after `./gradlew publishToMavenLocal` of the plugin
    }
    dependencies {
        classpath "io.hierograph:hierograph-gradle-plugin:<version>"
    }
}

beforeSettings { settings ->
    settings.pluginManager.apply(io.hierograph.gradle.plugin.HierographSettingsPlugin)
}
```

Save it outside the project, then:

```
./gradlew -q --init-script /path/to/hierograph.init.gradle hierographManifest
```

The manifest's `regenerate` command repeats the `--init-script` argument, so the
server regenerates it the same way.

### 8.3 With the server

Run the server as a local Java process in manifest mode against the build root.
The setup is the same as for a Maven build, see the
[Maven plugin's usage guide](../../hierograph-maven-plugin/spec/hierograph-maven-plugin.md#7-usage).
The server scans compiled output, so the project must be compiled
(`./gradlew classes testClasses`). Modules that are not compiled yet are watched
and scanned once their output appears. With
`hierograph.graph.watch.build-files=regenerate`, an edited build script makes the
server rerun the manifest's `regenerate` command.

### 8.4 In the Hierograph Docker container

**The host writes the manifest; the container scans and serves it.** Unlike a
Maven build, a Gradle build doesn't run inside the container:

```
./gradlew --init-script hierograph.init.gradle hierographManifest   # on the host
docker run -p 127.0.0.1:8765:8765 \
  -e HIEROGRAPH_URL=http://localhost:8765/mcp \
  -e HIEROGRAPH_WORKSPACE=/path/to/project \
  -v /path/to/project:/workspace:ro \
  [-v hierograph-state:/hierograph] \
  ghcr.io/code-kontor/hierograph-mcp-server:<version>
```

- The entrypoint detects the build tool: `pom.xml` means Maven; a settings or build
  script and no `pom.xml` means Gradle; `HIEROGRAPH_BUILD_TOOL=maven|gradle`
  overrides it, which a build with both needs for Gradle. With neither, the
  container exits.
- It reads the manifest from `/workspace/.hierograph/manifest.json`
  (`HIEROGRAPH_MANIFEST` names another path, e.g. a mounted directory the host
  writes to with `-Phierograph.manifestDir`). Without a manifest the container
  exits, naming the command that writes it.
- The manifest records host paths. The server reads it under the build root
  `/workspace` and maps everything under the recorded `reactorRoot` there; such a
  relocated manifest is never regenerated by the server.
- Build files: `HIEROGRAPH_BUILD_FILES` defaults to `notify`. An edited build script
  makes the build state `stale`; rerunning `hierographManifest` on the host rewrites
  the manifest, which the server applies as a diff.
- Nothing of `~/.gradle` is mounted: the container resolves nothing.

**Why not in the container.** Phase 2 of #30 ran Gradle inside the container, and
the first real project (spring-framework) showed why that doesn't hold up. A
Maven build is declarative: writing the manifest needs the POMs, a repository and
the plugin, and executes nothing of the project. A Gradle build definition is a
program, and running it elsewhere means reproducing the host's environment
piece by piece:

| Finding | Detail |
|---------|--------|
| Gradle needs a writable project | Gradle 9 refuses to configure a project whose directory isn't writable; Gradle 8.11 fails writing the problems report into the root `build/`; `buildSrc` and included build logic compile into their `build/` directories. Redirecting the build directories isn't enough on Gradle 9, and an overlay mount needs `--privileged`. The way out was a shadow copy of the build definition, with its own bugs (a package named `build` was dropped like a build directory) |
| JDK toolchains | spring-framework's build logic queries a JDK 25 toolchain while the project is configured; the image had JDK 21. Toolchain download (foojay) works, but needs network and the JDK in the state |
| The host's configuration | the container's own Gradle user home ignores `~/.gradle/gradle.properties` (repository credentials) and `~/.gradle/init.d` |
| Cost | the first start took about 9 minutes on spring-framework, a warm regeneration 6–10 s, against 9 s and 2 s on the host with its daemon |

A writable workspace mount removes only the shadow copy. It keeps the environment
gaps and adds two Gradle installations, on different paths and JDKs, sharing the
project's `.gradle/` and `build/` directories: task history recorded under one
path or JDK is out of date for the other, and file locks don't coordinate across
the container VM.

The container depends on the host build anyway: the classes it scans are compiled
there. The manifest is written where they are, with the same JDKs, credentials,
caches and daemon.

---

## 9. Building and publishing the plugin

The repository is a Maven reactor. The plugin has its **own Gradle build**, scoped
to the plugin and nothing else, in `hierograph-build-support/hierograph-gradle-plugin/`,
with its own `settings.gradle.kts`, `build.gradle.kts` and Gradle wrapper. It isn't
a module of the Maven reactor, and the Maven build doesn't call it.

### 9.1 The build

| Concern | Solution |
|---------|----------|
| Plugins | `java-gradle-plugin` (plugin descriptor, marker publication, TestKit classpath metadata), `org.jetbrains.kotlin.jvm`, `maven-publish`, `signing` |
| Coordinates | `io.hierograph:hierograph-gradle-plugin`; marker `io.hierograph:io.hierograph.gradle.plugin`, generated by `java-gradle-plugin` |
| Version | `version=` in the build's `gradle.properties`, bumped with the reactor by `release/bump-nonpom-version.sh`; filtered into `plugin.properties` (the `generator`) and the shipped init script |
| Shared library | `io.hierograph:io.hierograph.manifest:<same version>` as a regular `implementation` dependency, so it and Gson are dependencies in the published POM. Not shaded: a settings plugin's classes live in their own class loader |
| Resolving the library | Maven Central, then `mavenLocal()`: a snapshot after `mvn install` of the library, a release version right after `mvn deploy -Prelease` installed it, before Central has published it |
| JVM | toolchain and bytecode 21 |
| Kotlin | `apiVersion` and `languageVersion` 2.0 (§9.2) |

Building locally:

```
mvn -q install -pl hierograph-build-support/io.hierograph.manifest -am
cd hierograph-build-support/hierograph-gradle-plugin
./gradlew build                 # compiles, validates the plugin, runs the TestKit tests
./gradlew publishToMavenLocal   # for the init script and the Gradle integration tests
```

### 9.2 Minimum Gradle version

The library is compiled by the reactor, with Java 21 bytecode and Kotlin 2.4. Both
limit the Gradle versions the plugin runs on:

- **Java 21 bytecode.** Gradle must run on Java 21 or later, which Gradle 8.5 is the
  first version to support.
- **Kotlin stdlib.** A Gradle plugin runs against the Kotlin stdlib that Gradle
  embeds, not the one it was compiled against. Code that calls stdlib API newer
  than Gradle's embedded Kotlin fails at runtime with `NoSuchMethodError`. The
  Kotlin 2.4 compiler can restrict that API only down to 2.0, and Gradle 8.11 is
  the first version that embeds Kotlin 2.0 (2.0.20).

Together these set the **minimum to Gradle 8.11**. Both the library's POM and the
plugin's build compile with `apiVersion` / `languageVersion` 2.0. The TestKit run
on Gradle 8.11 is the check.

### 9.3 Release

After the libraries are on Maven Central:

```
cd hierograph-build-support/hierograph-gradle-plugin
./gradlew publish -Prelease
```

`-Prelease` requires a release version, and signs with the GPG key the Maven
release uses (`gpg` through the agent). The publications go to a local staging
repository, `build/central-staging`. `publishToCentralPortal` zips them into a
Central bundle and uploads it to the Central Portal as a `USER_MANAGED`
deployment, which is reviewed and published in the Portal like the Maven upload.
The Portal user token comes from the Gradle properties `centralUsername` and
`centralPassword`.

The Gradle Plugin Portal isn't used: the marker on Maven Central is enough when
`pluginManagement` lists `mavenCentral()`.

---

## 10. Tests

| Where | Test |
|-------|------|
| plugin (TestKit, every Gradle version under test) | multi-project fixture with `api`, `implementation`, `compileOnly`, `runtimeOnly` and `testImplementation`: modules, output directories, source roots without resources, both classpaths in the order Gradle itself reports, internal entries with `jar: null`; root project without `java` skipped |
| plugin | no task besides `hierographManifest` and the `hierographClasspaths` tasks runs, and no file outside the manifest directory is created (`--warning-mode=fail`) |
| plugin | a parallel build (`org.gradle.parallel=true`) gives the same modules as a serial one |
| plugin | Kotlin JVM project: Java and Kotlin output directories and source roots in one module |
| plugin | build files: settings, build scripts, `gradle.properties`, catalog, wrapper properties, applied script, `buildSrc` sources; `buildSrc/build` not listed (Groovy DSL) |
| plugin | second run on an unchanged build does not rewrite the manifest; a changed build script does |
| plugin | `regenerate` without and with a wrapper, with an init script, with `-Phierograph.manifestDir` |
| plugin | the shipped init script gives the same modules as the settings plugin; applying both is harmless |
| plugin | default coordinates; an unresolvable dependency fails naming the project; `hierograph.skip` |
| itest, `GradleMavenManifestParityIT` | the same build in Maven and Gradle form: the same modules, source roots and classpaths |
| itest, `GradleManifestServerIT` | the server in manifest mode loads a Gradle manifest, and an edited build script makes it run the manifest's `regenerate` command |
| itest, `GradleContainerIT` | the image on a Gradle build whose manifest the host wrote, `/workspace` read-only, running as a non-root user: the container detects Gradle, loads the graph from the relocated manifest, reports an edited build script as stale without running Gradle, and reloads when the host rewrites the manifest; it changes no file of the workspace |

The TestKit tests run on Gradle 8.11 and the Gradle that builds the plugin
(`-Phierograph.test.gradleVersions=8.11,9.5.0` overrides the list). The
integration tests use the published plugin and the shipped init script. They are
opt-in, after `./gradlew publishToMavenLocal` and `mvn install`; the container test
also needs Docker and the image (`-Pdocker package`):

```
mvn -Pitest verify -pl hierograph-itest/io.hierograph.itest.tests -Dhierograph.itest.gradle=true -Dit.test='Gradle*IT'
mvn -Pitest verify -pl hierograph-itest/io.hierograph.itest.tests -Dhierograph.itest.gradle=true -Dhierograph.itest.docker=true -Dit.test='Gradle*IT'
```

---

## 11. Not covered yet

| Not covered | What happens |
|-------------|--------------|
| Regeneration by the container | the container reports edited build files as stale; `hierographManifest` runs on the host (§8.4). The local server mode (§8.3) regenerates itself |
| Android (AGP), Kotlin Multiplatform | no `java` source sets; such projects are skipped with an info message |
| Additional source sets (`testFixtures`, `integrationTest`, test suites other than `test`) | not modules of the manifest; a dependency on another project's test fixtures is recorded as an internal entry of that project |
| Composite builds whose included builds contribute **modules** | an included build's projects are recorded as external entries without a jar (§4.3) |
| Configuration cache, project isolation | the task is declared not compatible (§3.3) |
| Gradle counterparts of `hierograph:scan` / `hierograph:collect` | the server's manifest mode scans class files itself |
