# Hierograph – Own Bytecode Scanner, Parquet Snapshots & In-Memory GraphStore

Specification / handover · 2026-11-06

---

## 1. Context and goals

Hierograph replaces jQAssistant scanning and Neo4j storage with its own ASM-based bytecode scanner, Parquet snapshots and an in-memory GraphStore. The main driver is **incremental updates**, which the jQA/Neo4j stack cannot deliver cheaply.

### Why move away from jQA + Neo4j

- Neo4j's `BatchInserter` was removed in Neo4j 5. Fast bulk loading is only possible offline via `neo4j-admin database import full`, into an empty database.
- `neo4j-admin database import incremental` needs Enterprise (block format) and a stopped database.
- Hierograph exposes its own API (MCP tools, GraphQL, code mode). Cypher is **not** part of the contract, so Neo4j's main advantage does not apply.
- The current `GraphDbMapping` projects Hierograph's model out of jQA's graph with complex Cypher (virtual types, `RESOLVES_TO` stubs, two-hop annotation shapes). Owning the model removes that layer.

### Goals

- Incremental re-scans at class granularity while Hierograph stays online.
- JDK and libraries scanned once (public API only), cached and shared as Parquet snapshots.
- Backend-neutral design: a fact model and a store port, so Neo4j can remain an optional export target.
- Fully deterministic rebuild from Parquet at any time.

### Non-goals for now

- Source-code parsing (planned later as optional enrichment, e.g. inlined constants).
- A general query language over the GraphStore.

---

## 2. Architecture overview

```
                    ┌──────────────────────────┐
 .class / .jar ───► │ Scanner (ASM)            │
                    │ mode: API | FULL         │
                    └────────────┬─────────────┘
                                 │ ClassFacts (one per class)
                                 ▼
                       ┌───────────────────┐
         ┌──────────── │  Ingest pipeline  │ ◄──────────── Parquet loader
         │             └─────────┬─────────┘               (Hardwood)
         ▼                       ▼                              ▲
 SnapshotWriter          GraphStore (in-memory)                 │
 (async)                 applyClass / removeClass               │
         │                       │                              │
         ▼                       ▼                              │
 snapshots/<key>/*.parquet   Derived layer (aggregations,       │
         └───────────────    cycles, layers) ──► Hierograph API ┘
                             (MCP tools / GraphQL / code mode)
```

### Components

1. **Scanner (ASM `ClassVisitor`)** – one class file in, one immutable `ClassFacts` out. Stateless, trivially parallel, knows nothing about Parquet or the graph. Two modes: **API** (JDK, libraries) and **FULL** (app code).
2. **`ClassFacts`** – the central contract (section 3).
3. **GraphStore** – in-memory model; `applyClass` / `removeClass` replace a type's owned subgraph and manage placeholders (section 5).
4. **SnapshotWriter / loader** – Parquet I/O. The loader turns Parquet back into `ClassFacts` and calls the same `applyClass` path as a live scan: **one ingest path, one set of tests**.
5. **Change detector** – in-memory FQN → class hash index; only changed classes are rescanned.
6. **Derived layer** – aggregations, SCCs/cycles, rule checks; strictly separate from scanned facts.
7. **Hierograph API** – unchanged upper layers, served through the same source interface as today.

### Key principles

- **Parquet is the persistence/exchange format, the GraphStore is the query engine.**
- Libraries and the JDK always come from Parquet; app-code increments go straight to the GraphStore; Parquet for app code is written **off the hot path**.
- Scanned facts and derived facts are kept strictly separate from day one.

---

## 3. Core model

`ClassFacts` is the central contract: one immutable record per class file. The scanner produces it, the Parquet loader reproduces it, and the GraphStore consumes it. It gets explicit schema versioning.

```kotlin
enum class RefKind {
    EXTENDS, IMPLEMENTS, ANNOTATED_BY,                   // structural
    FIELD_TYPE, PARAM_TYPE, RETURN_TYPE, THROWS,         // signature
    INVOKES, READS, WRITES, INSTANTIATES, CAST, CATCHES, // body (FULL mode only)
    DEPENDS_ON                                           // MVP only: coarse, pre-aggregated by jQA
}

data class Ref(val fromKey: String, val toKey: String, val kind: RefKind, val line: Int? = null)

data class MemberFacts(val key: String, val kind: JavaKinds, val signature: String, val modifiers: Int)

data class ClassFacts(
    val fqn: String,
    val kind: JavaKinds,          // CLASS | INTERFACE | ENUM | ANNOTATION | RECORD
    val modifiers: Int,
    val members: List<MemberFacts>,
    val refs: List<Ref>,
    val classHash: String,
    val sourceFile: String?,
)

enum class Origin { MODULE_MAIN, MODULE_TEST, LIBRARY, JDK }
data class ArtifactInfo(val key: String, val name: String, val fqn: String, val origin: Origin)

data class Node(
    val key: String,          // stable identity (see keys below)
    val kind: JavaKinds,      // MODULE | PACKAGE | CLASS | ... | FIELD | METHOD | EXTERNAL_TYPE
    val name: String,         // display name
    val fqn: String,
    val parent: String?,      // parent key; null for modules
    val placeholder: Boolean = false,
)
```

### Keys

- Type: FQN, e.g. `com.foo.Bar`.
- Member: `<owner fqn>#<signature>` – same format the current mapping uses.
- Artifact / snapshot: `maven/<g>/<a>/<v>/<jar-sha256>`, `jdk/temurin/21.0.5`, `module/<g>/<a>/<content-hash>`.
- Keys are content-derived and stable across rescans, restarts and snapshots. They replace Neo4j's internal `id()`.
- References always point to a **key string**, never to a node. Internal and external targets look the same.

### Placeholders

- A referenced but unscanned type becomes a placeholder node under the synthetic module **External Types** (replaces `:Virtual:Type` and `RESOLVES_TO`).
- When the real class is scanned, the placeholder is **upgraded in place** – incoming edges stay valid.
- When a scanned class disappears but is still referenced, it is **downgraded** to a placeholder.
- Orphan placeholders (no incoming edges) are cleaned up periodically.

### Scanner modes

- **API** (JDK, libraries): public/protected signatures only, no method bodies. Dependencies: supertypes, parameter/return types, annotations, generics.
- **FULL** (app modules): method bodies (`INVOKES`, `READS`/`WRITES`, instantiations, casts, catch types), private members, `SourceFile` + `LineNumberTable` (file and line range per method), `invokedynamic` resolved to lambda targets, synthetic members (bridge methods, `lambda$…`, `access$…`) filtered or collapsed.

### Implementation note

`Node` is the external API shape. Internally, use interned `Int` ids and column arrays (`kinds: ByteArray`, `parents: IntArray`, adjacency as primitive arrays) to keep memory low with millions of members and refs.

---

## 4. Parquet snapshot format

One snapshot per artifact (JDK, library jar, app module) as four flat tables with **string keys only**. Dictionary encoding absorbs the repeated FQNs; string keys keep snapshots composable across independently produced files.

```
snapshots/<snapshot-key>/
  types.parquet
  members.parquet
  refs.parquet
  annotations.parquet   # optional: annotation values only
```

### Footer metadata

Key-value metadata in every file, so each file is self-describing:

```
hierograph.schema_version  = 1
hierograph.scanner_version = 0.3.0
hierograph.snapshot_key    = maven/com.google.guava/guava/33.4.0-jre/ab12…
hierograph.origin          = LIBRARY      # MODULE_MAIN | MODULE_TEST | LIBRARY | JDK
hierograph.detail          = API          # API | FULL
hierograph.artifact_name   = guava
```

### types.parquet (sorted by `fqn`)

```
message type {
  required binary fqn          (STRING);
  required binary package      (STRING);
  required int32  kind;                    // CLASS, INTERFACE, ENUM, ANNOTATION, RECORD
  required int32  access_flags;            // raw ASM flags
  optional binary super_fqn    (STRING);
  optional binary outer_fqn    (STRING);
  optional binary generic_sig  (STRING);
  optional binary source_file  (STRING);
  required binary class_hash   (STRING);   // sha256 of the bytecode
}
```

### members.parquet (sorted by `owner_fqn, member_key`)

```
message member {
  required binary owner_fqn    (STRING);
  required binary member_key   (STRING);   // owner#name(desc)ret
  required int32  kind;                    // FIELD | METHOD | CONSTRUCTOR | STATIC_INIT
  required binary name         (STRING);
  required binary descriptor   (STRING);
  optional binary generic_sig  (STRING);
  required int32  access_flags;
  optional int32  line_from;               // FULL only
  optional int32  line_to;
}
```

### refs.parquet (sorted by `from_type, from_key, kind`; by far the largest table)

```
message ref {
  required binary from_key     (STRING);   // type fqn or member key
  required binary from_type    (STRING);   // owning type, denormalized
  required binary to_key       (STRING);
  required binary to_type      (STRING);   // target type, denormalized
  required int32  kind;                    // RefKind
  optional int32  line;                    // FULL only
  optional int32  count;                   // pre-aggregated duplicates / jQA weight
}
```

### annotations.parquet (optional)

```
message annotation {
  required binary target_key     (STRING);   // type, member, or member#param:0
  required binary annotation_fqn (STRING);
  required int32  retention;                 // CLASS | RUNTIME
  optional binary values_json    (STRING);
}
```

### Rules

- Interfaces, supertypes and annotation edges all live in `refs` – one place for every reference. `annotations.parquet` only holds **values**.
- `from_type` / `to_type` are denormalized so dependency aggregation and owned-subgraph deletion work without resolving member keys.
- Snapshots contain nodes **only for types they define**. External types are referenced by FQN; placeholders are created at load time (no duplicates when composing snapshots).
- Compression: ZSTD (Hardwood needs `zstd-jni`) or Snappy; uncompressed + dictionary is fine for small module snapshots.
- Row groups of about 64–128 MB, or a single row group for small snapshots. Sort orders give useful min/max statistics for skipping.
- Measure sizes early: a JDK API snapshot is expected in the low MB range, which decides whether it ships inside the Docker image.

---

## 5. GraphStore and replacement of GraphDbMapping

The GraphStore **is** Hierograph's model, built directly during ingest. Every rule in the current `GraphDbMapping` becomes a few lines of construction logic, and the downstream layers keep consuming the same row shapes.

### How the current rules map

| Current jQA rule | New implementation |
| --- | --- |
| TopLevelRule `:Artifact:Jar` | `registerArtifact(ArtifactInfo)` per library snapshot |
| TopLevelRule Maven project `CREATES` artifact (Main/Test) | `registerArtifact` per module, `Origin.MODULE_MAIN` / `MODULE_TEST` |
| TopLevelRule `:Virtual:Artifact` | Fixed synthetic module `<external>` named "External Types" |
| ParentChildRule Artifact → top-level Package, Package → sub-Package | `ensurePackagePath(artifact, packageOf(fqn))`, derived from the FQN |
| ParentChildRule Package → Class / Interface / Enum / Annotation / Record | `upsert(Node(fqn, kind, …))`, upgrades a placeholder in place |
| ParentChildRule Type → Field / Method | member nodes with key `fqn#signature` |
| ParentChildRule `:Virtual:Package` → `:Virtual:Type` | placeholders under `<external>/<package>` |
| DependencyRule with `EXISTS` subqueries and `RESOLVES_TO` | `typeDependencies()` groupBy over refs |

### Store operations

```kotlin
class GraphStore {
    private val nodes = HashMap<String, Node>()
    private val children = HashMap<String, MutableSet<String>>()
    private val refsByOwner = HashMap<String, List<Ref>>()          // owned subgraph per type
    private val incoming = HashMap<String, MutableSet<String>>()    // target type -> owner types

    fun registerArtifact(a: ArtifactInfo)
    fun applyClass(artifact: String, f: ClassFacts)   // removeOwned, ensure package path, upsert type,
                                                       // put members, store refs, ensure placeholders
    fun removeClass(fqn: String)                       // remove owned subgraph; downgrade to placeholder
                                                       // if still referenced
    fun resolvePlaceholders()                          // bulk variant for snapshot loading
}
```

Sketch of `applyClass`:

```kotlin
fun applyClass(artifact: String, f: ClassFacts) {
    removeOwned(f.fqn)
    val pkg = ensurePackagePath(artifact, f.fqn.substringBeforeLast('.', ""))
    upsert(Node(f.fqn, f.kind, f.fqn.substringAfterLast('.'), f.fqn, parent = pkg))
    f.members.forEach { m -> put(Node(m.key, m.kind, m.signature, m.key, parent = f.fqn)) }
    refsByOwner[f.fqn] = f.refs
    f.refs.forEach { r ->
        val target = typeOf(r.toKey)
        if (target !in nodes) ensurePlaceholder(target)   // under <external>/<package>
        incoming.getOrPut(target) { mutableSetOf() } += f.fqn
    }
}
```

**Never delete a type node itself on update** – incoming edges from unchanged classes hang on it. Only its owned subgraph (members + outgoing refs) is replaced.

### Type dependencies

```kotlin
data class TypeDependency(
    val from: String, val to: String, val weight: Int,
    val extends: Boolean, val implements: Boolean, val annotatedBy: Boolean, val other: Boolean,
)

fun typeDependencies(): Sequence<TypeDependency> =
    refsByOwner.asSequence().flatMap { (owner, refs) ->
        refs.groupBy { typeOf(it.toKey) }
            .filterKeys { it != owner }
            .map { (target, rs) ->
                val kinds = rs.mapTo(EnumSet.noneOf(RefKind::class.java)) { it.kind }
                TypeDependency(
                    from = owner, to = target, weight = rs.sumOf { it.count ?: 1 },
                    extends = RefKind.EXTENDS in kinds,
                    implements = RefKind.IMPLEMENTS in kinds,
                    annotatedBy = RefKind.ANNOTATED_BY in kinds,
                    other = kinds.any { it !in STRUCTURAL },
                )
            }
    }
```

Because refs target FQNs, internal and external annotations look identical. The stub → virtual type distinction and `RESOLVES_TO` disappear.

> **Open semantic decision:** the current `other` flag means "none of extends / implements / annotatedBy". The sketch means "also has non-structural refs". Decide which one Hierograph exposes before diffing backends.

### Derived layer

- Aggregations (package → module → layer), SCCs / cycles and rule checks live strictly apart from scanned facts.
- Recomputed after each update batch; local recompute only if full recompute proves too slow.
- Concurrency: copy-on-write snapshots or a read/write lock per update batch, so queries never see half-applied updates.

---

## 6. Incremental updates and snapshot cache

App-code increments go straight from the scanner into the GraphStore. Parquet stays off the hot path and serves only rebuild and cold start.

### Hot path

```
changed .class files → hash compare (in memory) → ASM scan (FULL) of changed types
                     → GraphStore.applyClass / removeClass → derived layer recompute
```

- Change detection: in-memory map FQN → class hash (sha256 of bytecode). Identical recompiles count as no change.
- Artifact-level shortcut: hash per jar or `target/classes` directory first; unchanged artifacts are skipped entirely.
- No previous snapshot is needed for the diff: changed types get their owned subgraph replaced; the store knows what to remove.
- Trigger: changes to `target/classes` after compile, or an explicit `rescan` tool. **Open:** Maven build, git diff between commits, or file watching during a Claude Code session.

### Expected cost per update (orders of magnitude, to be measured)

- Compile: seconds; outside Hierograph.
- ASM scan: very fast, tens of thousands of classes per second when only parsing.
- GraphStore apply: map and list operations; negligible.
- Derived recompute: likely the dominant Hierograph-side cost.

### Snapshot cache

- JDK and libraries: scanned once in API mode, written as Parquet, keyed by GAV + jar hash (JDK: vendor + exact version) + scanner/schema version. A scanner change invalidates the cache automatically.
- JDK snapshots can ship in the Hierograph Docker image or be downloaded on demand.
- App modules: FULL-mode snapshots written **asynchronously** (after a quiet period or at session end) for cold start.
- Library version bump: the artifact owns its subgraph; drop it, load the new snapshot, upgrade/downgrade placeholders.
- Cold start / rebuild: load all snapshots (types → members → refs), then `resolvePlaceholders()` once.

### Same FQN in several jars (shading, duplicates, multi-release jars)

- MVP: FQN = identity, first wins, log duplicates.
- Later: split `Type {fqn}` (logical identity) from `Definition {artifact, hash} -[:DEFINES]-> Type` to answer "which jar wins" and "where are duplicates".

### Libraries

- Parquet I/O: **Hardwood** (no Hadoop dependencies, parallel page decoding, Java 21+ runtime). Its writer is still in the 1.1 beta line – verify maturity before relying on it for writes.
- **DuckDB** (optional): CSV → Parquet conversion in the MVP, ad-hoc SQL over snapshots, API diffs between library versions.

---

## 7. MVP: Parquet from the existing jQA database

The MVP builds only the **read path** (Parquet → GraphStore) and uses the existing jQA database as a stand-in scanner. No ASM code is needed yet.

### Simplifications

- One snapshot for the whole database, with an extra `artifact` column instead of one snapshot per artifact.
- jQA's pre-aggregated type-level `DEPENDS_ON` is stored as `RefKind.DEPENDS_ON` with `count = weight`; the own scanner later replaces it with fine-grained kinds.
- jQA "required" types (no `CONTAINS` from an artifact) are not exported; they become placeholders through `refs`.

### Step 1: export queries (via the existing Bolt client, written as CSV)

```cypher
// types — CASE order matters: jQA annotations are also :Interface
MATCH (a:Artifact)-[:CONTAINS]->(t:Type)
WHERE NOT t:Virtual
RETURN a.fileName AS artifact,
       t.fqn AS fqn,
       CASE WHEN t:Annotation THEN 'ANNOTATION' WHEN t:Enum THEN 'ENUM'
            WHEN t:Record THEN 'RECORD' WHEN t:Interface THEN 'INTERFACE'
            ELSE 'CLASS' END AS kind,
       t.visibility AS visibility, t.abstract AS abstract,
       t.sourceFileName AS source_file
```

```cypher
// members
MATCH (a:Artifact)-[:CONTAINS]->(t:Type)-[:DECLARES]->(m)
WHERE (m:Method OR m:Field) AND NOT t:Virtual
RETURN t.fqn AS owner_fqn,
       t.fqn + '#' + m.signature AS member_key,
       CASE WHEN m:Field THEN 'FIELD' ELSE 'METHOD' END AS kind,
       m.name AS name, m.signature AS signature, m.visibility AS visibility,
       m.firstLineNumber AS line_from, m.lastLineNumber AS line_to
```

```cypher
// refs
MATCH (t1:Type)-[:EXTENDS]->(t2:Type) WHERE NOT t1:Virtual
RETURN DISTINCT t1.fqn AS from_type, t2.fqn AS to_type, 'EXTENDS' AS kind, 1 AS count
UNION ALL
MATCH (t1:Type)-[:IMPLEMENTS]->(t2:Type) WHERE NOT t1:Virtual
RETURN DISTINCT t1.fqn AS from_type, t2.fqn AS to_type, 'IMPLEMENTS' AS kind, 1 AS count
UNION ALL
MATCH (t1:Type)-[:ANNOTATED_BY]->(:Annotation)-[:OF_TYPE]->(at:Type) WHERE NOT t1:Virtual
RETURN DISTINCT t1.fqn AS from_type, at.fqn AS to_type, 'ANNOTATED_BY' AS kind, 1 AS count
UNION ALL
MATCH (t1:Type)-[r:DEPENDS_ON]->(t2:Type) WHERE NOT t1:Virtual AND NOT t2:Virtual
RETURN t1.fqn AS from_type, t2.fqn AS to_type, 'DEPENDS_ON' AS kind, r.weight AS count
```

Stub and virtual type share the same FQN, so the `RESOLVES_TO` logic of the current DependencyRule is not needed here.

### Step 2: CSV → Parquet (avoids depending on Hardwood's beta writer)

```bash
duckdb -c "COPY (FROM 'types.csv')   TO 'types.parquet'   (FORMAT parquet, COMPRESSION zstd)"
duckdb -c "COPY (FROM 'members.csv') TO 'members.parquet' (FORMAT parquet, COMPRESSION zstd)"
duckdb -c "COPY (FROM 'refs.csv')    TO 'refs.parquet'    (FORMAT parquet, COMPRESSION zstd)"
```

### Step 3: loader with Hardwood (pseudo-API – check exact method names against the Hardwood docs)

```kotlin
fun loadSnapshot(dir: Path, store: GraphStore) {
    readRows(dir / "types.parquet") { r ->
        store.registerArtifactIfAbsent(r.string("artifact"))
        store.upsertType(r.string("artifact"), r.string("fqn"), JavaKinds.valueOf(r.string("kind")))
    }
    readRows(dir / "members.parquet") { r ->
        store.addMember(r.string("owner_fqn"), r.string("member_key"), r.string("kind"), r.string("signature"))
    }
    readRows(dir / "refs.parquet") { r ->
        store.addRef(r.string("from_type"), r.string("to_type"), RefKind.valueOf(r.string("kind")), r.int("count"))
    }
    store.resolvePlaceholders()   // all to_types without a type row -> "External Types"
}
```

Order matters: types → members → refs → placeholders, so placeholder resolution runs once.

### Step 4: diff against GraphDbMapping

- The GraphStore implements the **same source interface** that `GraphDbMapping` serves today (hierarchy rows + dependency rows).
- Run both against the same jQA database and compare by **fqn** (Neo4j ids do not exist on the GraphStore side): hierarchy as `kind, fqn, parent fqn`; dependencies as `from, to, flags, weight`.
- **Acceptance:** the diff is empty apart from documented, expected differences (e.g. the `other` flag semantics).

---

## 8. Roadmap, open decisions and references

### Implementation status (2026-11-06)

Started on step 1, **core-first with synthetic fixtures** (no Neo4j or Parquet dependency yet). New reactor module group `hierograph-graphstore`:

- `io.hierograph.graphstore.model` — the scanned-fact contract: `RefKind` (+ `STRUCTURAL`), `Ref` (with `count`), `MemberFacts`, `ClassFacts`, `Origin`, `ArtifactInfo`, `Node`. Kinds reuse `io.hierograph.mcp.javaspec` `JavaNodeKind` / `JavaKinds.EXTERNAL_TYPE`.
- `io.hierograph.graphstore.core` — `GraphStore` (`registerArtifact` / `applyClass` / `removeClass` / `resolvePlaceholders`) plus the query surface `hierarchyRows(): HierarchyRow(kind, fqn, parentFqn)` and `typeDependencies(): TypeDependency`. Placeholder upgrade-in-place, owned-subgraph replacement, and still-referenced downgrade are implemented. 8 unit tests pass against hand-built `ClassFacts`.

Decisions taken: GraphStore lives in its own top-level module group (parallel to the Neo4j `graphdb` stack); the FQN-keyed row shapes above are the diff seam (the existing SPI `IHierarchyDefinitionProvider` / `IDependencyDefinitionProvider` is `Long`-id-based and Neo4j-coupled, so the MVP diffs by FQN rather than forcing the GraphStore through that SPI). `TypeDependency.other` currently uses the spec-sketch semantic ("also has a non-structural ref"); this is the documented divergence from the jQA `DependencyRule` flag and is still the open decision below.

**Next:** `io.hierograph.graphstore.parquet` loader (CSV→Parquet via DuckDB, read via Hardwood) + the jQA CSV export (§7) + the FQN diff harness against `GraphDbMapping`.

### Implementation order

1. MVP: jQA export → Parquet → loader → GraphStore, diffed against `GraphDbMapping`.
2. `ClassFacts` + ASM scanner (API and FULL modes), tested against hand-written fixture classes.
3. Snapshot round-trip: `ClassFacts` → Parquet (Hardwood or DuckDB) → `ClassFacts`, identical.
4. JDK and library snapshot cache.
5. Change detector + incremental `applyClass` / `removeClass` + derived-layer recompute.
6. Port the Hierograph API (MCP tools, GraphQL, code mode) onto the GraphStore; keep Neo4j in parallel as reference until diffs are clean on the ~76-module codebase.
7. Optional: Neo4j as export target for visual exploration; JavaParser enrichment for inlined constants.

### Open decisions

- [ ] Semantics of the `other` dependency flag ("none of the structural kinds" vs "also non-structural refs").
- [ ] Update trigger: Maven build, git diff, or file watching during a Claude Code session.
- [ ] Parquet writer: Hardwood (beta writer) vs DuckDB JDBC.
- [ ] Duplicate FQNs: first-wins in MVP vs `Type` / `Definition` split.
- [ ] Concurrency model for reads during updates (copy-on-write vs read/write lock).
- [ ] Ship JDK snapshots in the Docker image or download on demand (depends on measured size).

### Known blind spots of bytecode (for later enrichment)

- Inlined `static final` primitive and String constants leave no reference in bytecode.
- `SOURCE`-retention annotations (`@Override`, `@SuppressWarnings`, Lombok) are gone.
- Javadoc and comments are not available.

### Backend decision (background)

- Neo4j stays usable as an optional export target, but is no longer the primary store.
- Embedded Kùzu forks (e.g. Ladybug) were considered: technically a good fit, but the original project was archived in October 2025 and lives on in community forks.
- DuckDB + graph extension: Parquet-native, but path/recursive queries are clumsy.
- Chosen direction: **own in-memory GraphStore backed by Parquet**, behind a small port interface.

### References

- Neo4j Operations Manual – neo4j-admin import: https://neo4j.com/docs/operations-manual/current/tools/neo4j-admin/neo4j-admin-import
- Neo4j Operations Manual – Incremental import: https://neo4j.com/docs/operations-manual/current/import/incremental-import/
- Hardwood (GitHub): https://github.com/hardwood-hq/hardwood
- Hardwood 1.0 announcement: https://morling.dev/blog/hardwood-1-0-fast-lightweight-apache-parquet-reader-for-the-jvm/
- KuzuDB status and forks: https://www.puppygraph.com/blog/what-is-kuzudb
