# Hierograph GraphStore Archive — Specification

The module `io.hierograph.graphstore.jvm.archive` is the code side of Hierograph's
snapshots. It defines the **facts** the bytecode scanner produces, and it writes and
reads them as the Parquet tables of a snapshot. The scanner, the Maven plugin, the
store and the server all exchange snapshots through it.

The files themselves are defined in the
[snapshot format specification](hierograph-graphstore-format.md): tables, columns,
packaging, vocabularies and loading rules. This document describes the API that
implements that format, and the behaviour the format leaves to the implementation.
It does not repeat the format.

---

## Contents

1. [Where it sits](#1-where-it-sits)
2. [The fact model](#2-the-fact-model)
3. [Writing a snapshot](#3-writing-a-snapshot)
4. [Resolving a snapshot](#4-resolving-a-snapshot)
5. [Reading a snapshot](#5-reading-a-snapshot)
6. [Errors](#6-errors)
7. [Concurrency](#7-concurrency)
8. [Boundaries](#8-boundaries)

---

## 1. Where it sits

```
  scanner ──ClassFacts, Dependency──▶ ParquetWriter ──▶ snapshot ◀── HierographArchive.zip
                                                           │          (hierograph:collect)
                                                           ▼
                                         SnapshotSources ──▶ ParquetReader
                                                           │
                          ┌────────────────────────────────┴───────────────────┐
                          ▼                                                    ▼
            SnapshotLoader (store): rolls up, fills a GraphStore   detail tools (server): read on demand
```

| Module | Uses |
|--------|------|
| `io.hierograph.graphstore.jvm.scanner` | the fact model; `ParquetWriter.writeArtifacts` |
| `hierograph-maven-plugin` | `writeArtifacts` through the scanner (`hierograph:scan`); `HierographArchive.zip` and `fileName` (`hierograph:collect`) |
| `io.hierograph.graphstore.jvm.store` | the fact model; `SnapshotSources`, and `ParquetReader`'s per-source reads in `SnapshotLoader`; the detail reads in `DetailQueryEngine` and `DetailEnricher` |
| server modules | the snapshot-wide reads of `ParquetReader`, for the detail tools and node properties |

The module depends on Hardwood (Parquet) and the Kotlin standard library only. It knows
nothing of a GraphStore: the type-level aggregate, type binding and derived facts are the
store's (§8).

---

## 2. The fact model

Package `io.hierograph.graphstore.jvm.archive.model`. Every class is an immutable value.

| Type | Holds | Table |
|------|-------|-------|
| `ClassFacts` | one class file: `fqn`, `kind` (a type kind of `JavaNodeKind`), `modifiers` (`TypeModifiers` bits), `members`, `classHash` (empty when unknown), `sourceFile` | `types`, `members`, `parameters` |
| `MemberFacts` | one field, method or constructor: `key`, `kind`, `signature`, `name`, `modifiers` (`MemberModifiers` bits), `lineFrom`, `lineTo`, `parameters`, `bridgeTarget` | `members` |
| `ParameterFacts` | one declared parameter: `index`, `name`, `typeFqn`, `annotations` | `parameters` |
| `Dependency` | one detail fact: `fromKey`, `fromType`, `toKey`, `toType`, `kind`, `line`, and `fromArtifact`, the artifact of the class file it comes from (§3, §5.3); it is not a column, because the archive a row sits in names its artifact | `dependencies` |
| `ArtifactClasspath`, `ClasspathEntry` | an artifact's key, display name and classpath entries, without the artifact itself | `classpath` |

A class's dependencies are not part of its `ClassFacts`. They travel beside it as a list of
`Dependency`, because they are large and are read on demand.

The vocabularies are enums and helpers:

- **`JavaNodeKind`** — the node kinds, with their tool names (`java.class`, …) as `value`.
  `TYPES` are the five type kinds, `MEMBERS` are `FIELD` and `METHOD`, `NODE_KINDS` the kinds
  a graph node can have. `EXTERNAL_TYPE` (`"external.type"`) is the kind of an unscanned
  type; it is a string, not an enum value, and `isTypeKind` accepts both. The tables store
  the enum **names** (`CLASS`, `CONSTRUCTOR`).
- **`DependencyKind`** — the relationships of format §5.2, with their wire names as `value`.
  `counted` says whether a kind adds weight to the aggregate (all but `RAISES` and the
  derived kinds); `derived` marks `OVERRIDES`, `READ_BY` and `WRITTEN_BY`, which are computed
  at load and never stored. `AGGREGATED` lists the counted kinds in vocabulary order,
  `STRUCTURAL` the kinds behind the `extends` / `implements` / `annotatedBy` flags, and
  `toMap` turns per-kind counts into the tools' `{wire name: count}` form.
- **`TypeModifiers`, `MemberModifiers`** — pack and decode the bits of format §5.1.
  `modifiers`, `methodModifiers` and `fieldModifiers` decode into the canonical lists the
  tools show; `isGeneric` and `isConstant` read the two bits that are not modifiers.

---

## 3. Writing a snapshot

`ParquetWriter` (an object) writes facts. Every table it writes carries the format
version in its footer and follows the format's sort orders.

| Function | Writes |
|----------|--------|
| `writeArtifacts(outDir, classes, dependencies, codec, classpaths, packaging, cancelled)` | a snapshot directory: one `.hierograph` artifact per artifact key |
| `writeSnapshot(dir, classes, dependencies, codec)` | the four fact tables of one artifact, or of an unpacked snapshot, into `dir` |
| `writeDependencies(dir, dependencies, codec)` | the `dependencies` table alone |
| `writeClasspath(dir, classpath, codec)` | the `classpath` table alone, with the artifact itself prepended at index 0 |

A class is passed as `ArtifactClass(artifactKey, facts)`. `writeSnapshot` and
`writeArtifacts` compress with ZSTD unless told otherwise; the two single-table writers
default to uncompressed. `CompressionCodec.UNCOMPRESSED` avoids the need for a codec on the
classpath.

**`writeArtifacts`** is the scanner's output path:

1. It groups the classes by artifact key, and each dependency by its `fromArtifact`, the
   artifact whose class file it comes from. The scanner always sets it, so when several
   artifacts define the same FQN, each copy's facts go into its own archive. A dependency
   without `fromArtifact` goes to the artifact of the first class whose FQN is its
   `fromType`; one whose `fromType` is none of the classes is not written.
2. For each artifact, in the order the keys first occur in `classes`, it checks `cancelled`.
   Once that returns `true`, it stops with a `CancellationException`; the artifacts written
   so far stay in `outDir`.
3. It deletes an artifact of the same name in `outDir`, whichever its form, and writes the
   new one under `HierographArchive.fileName(key)`:
   - `Packaging.ARCHIVE` (the default) writes the tables into a staging directory
     `.staging-<name>` in `outDir`, zips them, and deletes the staging directory, also when
     the write fails;
   - `Packaging.EXPLODED` writes the tables straight into the `<name>.hierograph/`
     directory.
4. It adds the artifact's `classpath` table when `classpaths` has an entry for its key.

Nothing is written outside `outDir`.

**`writeSnapshot`** writes its four tables in parallel, one thread per table. Each table is
the same as a sequential write would make it. If a table fails, the others still finish,
and the first failure is rethrown as its own exception, not wrapped.

**`HierographArchive`** packages tables: `fileName(artifactKey)` is the archive name of
format §2.1, and `zip(sourceDir, target)` writes every regular file directly under
`sourceDir` into `target` as STORED entries, sorted by name. `hierograph:collect` uses the
same `zip` to build a container from a directory of archives.

---

## 4. Resolving a snapshot

`SnapshotSources.of(path)` turns a snapshot path into its artifacts, as
`List<ArtifactSource>`, following format §2.3:

| `path` is | Sources |
|-----------|---------|
| a directory with `*.hierograph` entries | one per entry, in name order; a container contributes one per inner archive, in entry-name order |
| a directory without such entries | the directory itself (the unpacked layout) |
| a `.hierograph` file | the archive, or the inner archives of a container |
| anything else, including a missing path | one directory source that has no tables |

An **`ArtifactSource`** has a `name` and `table(file)`, which opens one table as a Hardwood
`InputFile`, or returns `null` when the artifact does not have it. The name is the
directory or file name; for an archive inside a container it is
`<container>!/<archive>`. Error messages use it.

Tables are read **in place** (format §2.1). A STORED entry is a byte range of the file, read
with positional reads on a file channel that is open only while the table is. Files are
never memory-mapped, so a snapshot may be replaced or deleted while it is read. An entry
that is not STORED is inflated into memory each time its table is opened; an archive inside
a container whose entry is not STORED is inflated the same way, and nothing of it is kept.

`SnapshotSources` caches the parsed central directory of every archive file, keyed by its
path and checked against its size and modification time. A file rewritten in place is
parsed again; a cache entry for a file that no longer exists is dropped on the next
`of` call.

---

## 5. Reading a snapshot

`ParquetReader` reads rows and returns facts. It holds no state, and it never builds a
graph. Its functions come in two groups.

### 5.1 Per-source reads

These read one `ArtifactSource`. `SnapshotLoader` builds on them, reading the sources in
parallel.

| Function | Returns |
|----------|---------|
| `checkFormat(source)` | nothing; throws if the source is not of the current format (format §6.5) |
| `readTypes(source, filter)` | the `types` rows as `TypeRow`s |
| `readMembers(source, filter)` | the `members` rows, grouped by owner FQN, without parameters |
| `forEachDependencyFact(source, fromType, visit)` | streams `fromType`, `fromKey`, `toType` and the kind of every `dependencies` row, or of one type's rows |

`forEachDependencyFact` reads only the four columns the rollup needs (format §6.4) and
builds no `Dependency` objects. Without `fromType` it reads every row through a column
projection; with it, it pushes the filter down.

`TypeRow` holds the `types` columns as stored: `kind` is the type kind's name, already
checked against the vocabulary, and `modifiers` is `null` for a table written before the
column existed.

### 5.2 Snapshot-wide reads

These take a snapshot path, resolve it with `SnapshotSources`, and combine the results of
every source. The detail tools use them.

| Function | Returns |
|----------|---------|
| `readTypeRows(path, fqns)` | the `TypeRow`s of the given FQNs, one per defining artifact |
| `readMembersByOwner(path, ownerFqns)` | the members of the given owners, by owner FQN |
| `readMember(path, memberKey)` | one member with its parameters, or `null` |
| `readAllMembers(path)` | every member, without parameters |
| `readParameters(path, methodKey)` | a method's parameters, ordered by index |
| `readDependencies(path, fromTypes, toTypes, kind)` | the `dependencies` rows that match all given filters |
| `readClasspath(path)` | each artifact's display name and classpath order, by artifact key |

An empty `fqns` or `ownerFqns` returns nothing; an empty `fromTypes` or `toTypes` does not
filter. Member keys and owner FQNs are not artifact-scoped: when two artifacts define the
same type, `readMembersByOwner` and `readAllMembers` return its members from both.
`readMember` takes the member from the first source that has it, and its parameters from
the same source; `readParameters` likewise reads the first source that has the method's
parameters.

The snapshot-wide reads do **not** call `checkFormat`. A caller that reads a snapshot it has
not loaded through `SnapshotLoader` checks the format itself.

### 5.3 Filters and artifact tags

The reads push a filter down to Hardwood where they can, so that row groups and pages
outside the filter are skipped. `readDependencies` pushes down **one** predicate: the
`fromTypes` filter if given, else `toTypes`, else `kind`. It applies the others in memory.
Hardwood 1.1.0.Beta3 prunes pages wrongly when such predicates are combined, and drops
valid rows; each predicate alone is correct.

`readDependencies` tags each row with `fromArtifact`, the artifact whose snapshot holds it,
when the source's `types` table names exactly one artifact. In an unpacked snapshot that
covers several artifacts the tag is `null`. With the tag, a consumer can bind the row's
types in that artifact's context (format §6.1).

---

## 6. Errors

| Exception | When |
|-----------|------|
| `SnapshotFormatException` | `checkFormat` finds a stale or newer artifact (format §6.5); a read meets a `kind` or `relationship` outside its vocabulary. `SnapshotFormatException.stale` words the stale case with a hint to rescan. |
| `IOException` | an archive is not a ZIP, is in ZIP64 format, has a corrupt central directory or local header, uses a compression method other than STORED or DEFLATED, or holds a deflated table shorter than declared |
| `CancellationException` | `writeArtifacts` was cancelled |

The ZIP index of an archive file, and of a STORED archive inside a container, is parsed
when the archive is resolved, so `SnapshotSources.of` already fails on such a damaged
archive. Problems inside an entry, such as an unsupported method, a truncated table or a
damaged deflated archive inside a container, surface when a table is opened.

---

## 7. Concurrency

- `ParquetReader` holds no state, so one instance can be shared across threads.
- `SnapshotSources` keeps its cache in a concurrent map, so `of` may be called from several
  threads. An `ArtifactSource` may open several tables at the same time; each opened table is
  used by one thread at a time.
- `ParquetWriter.writeSnapshot` starts its own threads for the tables and returns when all
  have finished. Writing two snapshots into the same directory at the same time is not
  supported.

---

## 8. Boundaries

| Not in this module | Where it lives |
|--------------------|----------------|
| Scanning class files into facts | `io.hierograph.graphstore.jvm.scanner` |
| Rolling dependencies up into the type-level aggregate (`Ref`, `DependencyRollup`) | the store module, in `SnapshotLoader` and the server's in-memory ingest |
| Binding type names to artifacts (`TypeResolver`) and external-type placeholders | the store module |
| Derived facts: overrides, virtual-call targets, inverse relationships | the store's detail engine and the server's load-time resolution |
| Deciding when to rescan or rewrite a snapshot | the Maven plugin and the server's file watcher |
