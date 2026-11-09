# Hierograph Manifest Library — Specification

`io.hierograph.manifest` is Hierograph's **producer-side manifest library**. It
holds everything about writing a build manifest that does not depend on the build
tool: the manifest model, the merge of per-module descriptors, write-only-on-change
with atomic replacement and locking, and build-file hashing. Build-tool plugins
use it, so they all write the same format with the same guarantees.

This document explains why the library exists, what a producer hands it, what it
guarantees, and how a plugin uses it.

---

## Contents

1. [Purpose](#1-purpose)
2. [Where it sits](#2-where-it-sits)
3. [The model](#3-the-model)
4. [The merge](#4-the-merge)
5. [Write guarantees](#5-write-guarantees)
6. [API reference](#6-api-reference)
7. [Working with the library](#7-working-with-the-library)
8. [Boundaries](#8-boundaries)

---

## 1. Purpose

A watching server depends on more than the manifest's fields. It treats every
rewrite as a module-set change, so a rebuild of an unchanged build must not touch
the file; it reads the file while builds write it, so a write must never be seen
half done; and parallel module builds and concurrent build processes merge at the
same time. Getting these right once, in one place, keeps every build-tool plugin
correct.

**One format, many producers.** A plugin collects what only its build tool knows
(the modules, their classpaths, the build files, the regeneration command) and
hands it over as plain values. The library turns them into the format the
[build manifest specification](../../hierograph-server/io.hierograph.server.graphservice/spec/hierograph-build-manifest.md)
defines.

**No build-tool API.** The library depends on Gson and the Kotlin standard library
only, so a Maven plugin and a Gradle plugin can both use it.

**Lenient reading.** Descriptors and manifests written by an earlier producer
version (single output directories, no `testClasspath`) are read, not rejected:
the merge must keep entries from the existing manifest across such versions.

---

## 2. Where it sits

```
 Maven plugin ─────────┐         ┌──────────────────────────┐
  modules, classpaths, ├────────►│ hierograph-manifest      │
  build files, command │         │  ManifestWriter          │──► target/…/module.json
 (other producers) ────┘         │   writeDescriptor        │──► .hierograph/manifest.json
                                 │   merge                  │
                                 └──────────────────────────┘
```

**Producers.** Build-tool plugins: the Maven plugin's `create-descriptor` and
`merge-descriptors` goals.

**Consumers.** None at run time beyond its callers. The files it writes are read
by the server, which parses them with its own lenient reader; the library is not
on the server's classpath.

---

## 3. The model

```kotlin
data class Manifest(
    val schemaVersion: Int,             // SCHEMA_VERSION
    val generator: String,              // producer and version, e.g. "io.hierograph.maven.plugin/0.2.0"
    val generatedAt: String,            // ISO-8601 instant of the write
    val reactorRoot: String,            // the build root on the build host
    val buildTool: BuildTool?,          // the producing tool, informational
    val regenerate: List<String>?,      // the command that rewrites the manifest
    val modules: List<ModuleDescriptor>,
    val buildFiles: List<BuildFile>,
)

data class ModuleDescriptor(
    val gav: String,                    // "com.example:billing-core:1.4.0"
    val name: String,                   // "billing-core"
    val mainOutputDirs: List<String>,   // one or more; need not exist yet
    val testOutputDirs: List<String>,
    val sourceRoots: List<String>,
    val testSourceRoots: List<String>,
    val classpath: List<ClasspathEntry>,     // main, in classpath order
    val testClasspath: List<ClasspathEntry>, // full test classpath
)

data class ClasspathEntry(val gav: String, val internal: Boolean, val jar: String?)
data class BuildTool(val name: String, val version: String)
data class BuildFile(val path: String, val sha256: String)
```

The fields mean what the build manifest specification says; the model is its
Kotlin form. The constants `MANIFEST_DIR` (`.hierograph`), `MANIFEST_FILE`
(`manifest.json`) and `SCHEMA_VERSION` (`2`) fix the location and version.

A producer describes its build with two more types:

```kotlin
data class ModuleSource(
    val gav: String,           // the module's current GAV
    val name: String,          // display name for messages
    val descriptorFile: Path,  // where the module's descriptor is (or would be)
)

data class MergeRequest(
    val manifestDir: Path,          // <build root>/.hierograph
    val generator: String,
    val reactorRoot: String,
    val buildTool: BuildTool?,
    val regenerate: List<String>?,
    val modules: List<ModuleSource>,    // every module of the build, built in this run or not
    val buildFiles: Collection<Path>,   // the files that define the build
)
```

---

## 4. The merge

`merge` builds one entry per module of the request:

| Situation | Effect |
|-----------|--------|
| The module's descriptor exists | its content is the entry |
| The descriptor is missing (deleted by a clean, not rebuilt yet) | the module's entry from the existing manifest is kept, matched by `gav` |
| A module is no longer in the request | its entry disappears, even if its descriptor remains on disk |
| A module's GAV changed | the entry under the previous GAV disappears; the descriptor's entry appears |
| A descriptor cannot be read | it is skipped and `warn` is told |
| Two modules have the same GAV | the first is kept and `warn` is told |

Modules are sorted by `gav`. Build files are made absolute and normalised,
de-duplicated, sorted by path, and hashed; a listed path that is not a regular
file is left out.

**Why the request lists every module.** Only the build tool knows which modules
form the build. Taking that list from the request, rather than searching for
descriptors, drops modules removed from the build and ignores stale descriptors.

**Why a missing descriptor keeps its entry.** A build after a separate clean
merges once per module; without this rule the first merge would drop every module
not yet rebuilt, and a narrowed build after a clean would drop them until they are
built again.

---

## 5. Write guarantees

| Guarantee | Mechanism | Why |
|-----------|-----------|-----|
| **Written only on change** | the new manifest is compared with the existing file as JSON trees, ignoring `generatedAt`; descriptors are compared as text | a watching server treats a changed manifest as a module-set change |
| **Atomic** | write to `<file>.tmp`, then move with `ATOMIC_MOVE` (plain replace where unsupported) | a reader never sees a partial file |
| **Serialised in the JVM** | a lock around each merge | parallel module builds merge at once |
| **Serialised across processes** | an exclusive file lock on `.hierograph/manifest.lock`; a lock held through another class loader of the same JVM is retried | a command-line build and an IDE build merge at once |
| **Cheap hashing** | hashes cached per path while size and modification time are unchanged | a full build merges once per module and lists the same build files each time |

Comparing JSON trees rather than model objects keeps the comparison correct for an
existing manifest written by an earlier producer, whose missing fields would
otherwise be `null` in non-null properties.

---

## 6. API reference

`ManifestWriter` is a stateless entry point (apart from its locks and hash cache).

| Method | Description |
|--------|-------------|
| `writeDescriptor(descriptor, file): Boolean` | Writes a module descriptor atomically, only when its content changed; returns whether it wrote. Creates parent directories. |
| `merge(request, warn = {}): MergeOutcome` | Merges the request into `<manifestDir>/manifest.json` (§4, §5). `warn` receives messages about unreadable descriptors and duplicate GAVs. |

`MergeOutcome(modules, written, file)` reports the number of merged modules,
whether the file was rewritten, and its path.

---

## 7. Working with the library

**Write a descriptor per module.** In the build step that knows a module's layout
and resolved classpaths:

```kotlin
val descriptor = ModuleDescriptor(
    gav = "com.example:billing-core:1.4.0",
    name = "billing-core",
    mainOutputDirs = listOf("/ws/billing/billing-core/target/classes"),
    testOutputDirs = listOf("/ws/billing/billing-core/target/test-classes"),
    sourceRoots = listOf("/ws/billing/billing-core/src/main/java"),
    testSourceRoots = listOf("/ws/billing/billing-core/src/test/java"),
    classpath = listOf(ClasspathEntry("com.example:billing-api:1.4.0", internal = true, jar = null)),
    testClasspath = listOf(ClasspathEntry("com.example:billing-api:1.4.0", internal = true, jar = null)),
)
ManifestWriter.writeDescriptor(descriptor, Path.of("/ws/billing/billing-core/target/hierograph/module.json"))
```

**Merge after each descriptor.** Hand over every module of the build, not only the
ones built in this run, together with the build files and the command:

```kotlin
val outcome = ManifestWriter.merge(
    MergeRequest(
        manifestDir = Path.of("/ws/billing/.hierograph"),
        generator = "io.hierograph.maven.plugin/0.2.0",
        reactorRoot = "/ws/billing",
        buildTool = BuildTool("maven", "3.9.6"),
        regenerate = listOf("./mvnw", "-q", "-B", "process-classes"),
        modules = listOf(
            ModuleSource("com.example:billing-api:1.4.0", "billing-api", Path.of("/ws/billing/billing-api/target/hierograph/module.json")),
            ModuleSource("com.example:billing-core:1.4.0", "billing-core", Path.of("/ws/billing/billing-core/target/hierograph/module.json")),
        ),
        buildFiles = listOf(Path.of("/ws/billing/pom.xml"), Path.of("/ws/billing/billing-core/pom.xml")),
    ),
) { message -> log.warn(message) }
```

Merging after every module keeps the manifest current for narrowed builds; the
write-only-on-change rule keeps it quiet when nothing changed.

---

## 8. Boundaries

| Not in the library | Where it lives |
|--------------------|----------------|
| Which modules form the build, how classpaths resolve, which files are build files, the regeneration command | each build-tool plugin |
| Reading the manifest for scanning | the server's `ManifestGraphSource`, with its own lenient reader |
| The format's definition | the build manifest specification |

Keeping build-tool knowledge out lets every producer share the same guarantees,
and keeping the server's reader separate lets the server accept manifests from
any producer version.
