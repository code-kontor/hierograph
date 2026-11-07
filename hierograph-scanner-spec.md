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

- Type node: artifact-scoped key `type:<artifact>:<fqn>` — a *definition*. The same FQN defined in two artifacts is **two distinct nodes** (the key was a bare FQN before; scoping it stops duplicates/shaded classes from collapsing). Packages are `pkg:<artifact>:<fqn>`, modules the artifact key.
- Member: `<owner fqn>#<signature>` – same format the current mapping uses.
- Artifact / snapshot: `maven/<g>/<a>/<v>/<jar-sha256>`, `jdk/temurin/21.0.5`, `module/<g>/<a>/<content-hash>`.
- Keys are content-derived and stable across rescans, restarts and snapshots. They replace Neo4j's internal `id()`.
- References carry a **bare FQN**, never a node — the owning definition is chosen at resolution time (see *Reference resolution* below). Internal and external targets look the same.
- **Node handle** (the agent-facing id): a content-addressed hash of the key — `NodeHandle.of(key)`, SHA-256 truncated to 53 bits (JSON/JS-safe). Compact and opaque for the MCP protocol, yet durable across rescans/restarts because it is a pure function of the stable key; distinct per artifact because the key is; a build-time collision check fails fast rather than serving an ambiguous id.

### Types vs type references (resolution model)

Strictly separate **a type that was parsed** from **a mention of a type by name**:

- **Type (definition)** – something actually scanned: kind, members, modifiers, source file, bytecode hash. It exists in a snapshot as a `types` row.
- **Type reference** – a mention of a type by FQN from some context (a supertype, a parameter, an `INVOKES` target …). A name, not a thing; it may or may not correspond to a scanned type.

Three layers, kept strictly apart:

- **Facts (persisted):** snapshots contain only **types** (definitions: `types` + `members`) and **references** (`refs`, FQN-valued). No `RESOLVES_TO`, no stub/placeholder rows, no virtual nodes — those are computed results, not facts.
- **Resolution (in-memory, derived):** map each reference's FQN to a scanned type, or mark it unresolved. Done globally after load, **never at scan time** — a class cannot know what else was scanned, which is also what keeps the scanner stateless and trivially parallel. On an incremental update a reference flips `unresolved ↔ resolved` in place rather than triggering a rebuild.
- **Presentation (in-memory, derived):** choose which unresolved references to surface, and how (the External Types view below).

Resolution is **relative to the loaded snapshot set**: `java.util.List` is unresolved with only app code, and becomes resolved once a JDK API snapshot is loaded — so status is recomputed when the set changes, never stored. References are to **symbols**: type references resolve in the type-level graph; member references (`INVOKES` / `READS` / `WRITES`) resolve in the detail layer.

This is the same axis as the `Type` / `Definition` split (section 6): a reference resolves to a logical `Type {fqn}` that has 0..N `Definition`s — **0 = unresolved** (external), 1 = normal, >1 = duplicate/shaded. jQAssistant instead materializes per-artifact stub `:Type` nodes and reconciles them post-scan via `RESOLVES_TO`; owning the model lets references stay references and drops `RESOLVES_TO` entirely. The code vocabulary should make the distinction explicit — `Type` / `TypeDefinition` vs `TypeReference`.

**Reference resolution and the classpath.** A reference carries a bare FQN; binding it to a concrete *definition* when several artifacts define that FQN is **classpath shadowing** — the first artifact on the consumer's classpath that defines the FQN wins. This is a **per-owner** decision (each module resolves against its own classpath), not a global one. The GraphStore routes every reference through a `TypeResolver` seam for exactly this. **Phase 1 (current)** ships a deterministic owner-first stand-in (the owner's own artifact if it defines the FQN, else the sole definer, else the lexicographically-smallest) — behaviour-neutral while no FQN is duplicated. **Phase 2** feeds the seam real per-module dependency order from the build; the call sites do not change. `typeDependencies()` exposes the resolved edges as FQN pairs (for diffing/display); `typeDependencyEdges()` exposes them as artifact-scoped node keys (for the hierarchical graph, so an edge binds to the correct distinct node).

### External Types view (unresolved references)

An unresolved reference is not a type, so it needs no type node. For navigability the GraphStore materializes a **placeholder view** of unresolved references under the synthetic module **External Types** (replacing jQA's `:Virtual:Type` + `RESOLVES_TO`). The view is derived, never persisted.

- When the real class is later scanned, the placeholder is **upgraded in place** – incoming edges stay valid (the reference flips to resolved).
- When a scanned class disappears but is still referenced, it is **downgraded** back to a placeholder (resolved → unresolved).
- Orphan placeholders (no incoming references) are cleaned up periodically.
- A `PlaceholderPolicy` decides **which** unresolved references to surface — the jQA-aligned policy excludes primitives, array descriptors, default-package names, `java.lang.*` / `kotlin.*`, and the two nullability annotations. This is a presentation choice over references; the underlying refs stay in the snapshot regardless.

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

### parameters.parquet (sorted by `method_key, index`)

Method/constructor parameters, one row per parameter — the 1:N companion to `members.parquet` that
`method_details` reads for named, ordered parameters. (MVP-implemented; parameter *types* are also
reachable as `parameter_type` edges in `detail_refs`, but names/positions live only here.)

```
message parameter {
  required binary method_key   (STRING);   // owning member key: owner#signature
  required int32  index;                   // 0-based position
  required binary name         (STRING);   // 'argN' when javac -parameters was absent
  required binary type_fqn     (STRING);   // erased parameter type
}
```

### refs.parquet (sorted by `from_type, kind`)

Type-level references only — both endpoints are types:

```
message ref {
  required binary from_type    (STRING);   // owning type
  required binary to_type      (STRING);   // target type
  required int32  kind;                    // RefKind: EXTENDS | IMPLEMENTS | ANNOTATED_BY | DEPENDS_ON
  optional int32  count;                   // pre-aggregated duplicates / jQA weight
}
```

The member-grained `from_key` / `to_key` / `line` of the original §4 superset are **not** in this table — type→type edges have no member source, so `from_key`/`to_key` would just repeat the type FQNs. Member-level references live in a separate `detail_refs.parquet` (see below).

### detail_refs.parquet (member-level; sorted by `from_type`)

```
message detail_ref {
  required binary from_key      (STRING);   // member key (owner#signature), or type fqn for a type source
  required binary from_type     (STRING);   // owning type (sort key → scoped row-group skipping)
  required binary to_key        (STRING);   // member key, or type fqn for a type-reference target
  required binary to_type       (STRING);   // target type
  required binary relationship  (STRING);   // calls | reads_field | writes_field | overrides | throws | raises | …
  optional int32  line;                     // FULL-mode source line
}
```

### Decision: type-level vs detail refs — unify the concept, partition the storage

Keep **two physical ref tables** (`refs.parquet` type-level, `detail_refs.parquet` member-level) rather than one:

- **Access patterns diverge and should drive physical layout.** `refs` is *fully loaded* into the in-memory graph at cold start and must stay small/fast (the ~80 ms cold start / ~0.6 MB); `detail_refs` is *scoped, on-demand, never fully loaded* and is ~8× larger. Merging would make cold start read or row-group-filter member data it never uses.
- **Incremental updates favour separate files** — rewrite only the partition whose granularity changed.
- **In the MVP they are distinct sources, not two views of one.** jQAssistant's type-level `DEPENDS_ON` is pre-aggregated over usages the member export doesn't fully capture, so it is *not* today a roll-up of the member edges.

The **end state** (with the ASM FULL scanner) unifies the *logical model* — one reference concept at finest granularity, with type dependencies as a derived aggregation (`GROUP BY from_type, to_type`) over member edges plus the intrinsically type-level structural edges — while still materializing the type-level roll-up as its own file for the cheap cold-start load. So: **one concept, two materializations.** Until then the two tables stay separate in both schema and file.

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

- **Phase 1 (done):** the type node key is artifact-scoped (`type:<artifact>:<fqn>`), so each definition is a distinct node — duplicates/shaded classes no longer collapse. References resolve to a definition through the `TypeResolver` seam (§3), with a deterministic owner-first stand-in for classpath order.
- **Phase 2:** feed the seam the real **per-owner classpath order** (per-module dependency order from the build) so "which jar wins" follows actual shadowing; expose "where are duplicates" via `typesWithFqn(fqn)` and the logical `Type {fqn}` ↔ `Definition {artifact, hash}` view. Member-level `detail_refs` are still FQN-keyed, so detail queries don't yet distinguish duplicate-FQN members across artifacts.

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
- `io.hierograph.graphstore.core` — `GraphStore` (`registerArtifact` / `applyClass` / `removeClass` / `resolvePlaceholders`) plus the query surface `hierarchyRows(): HierarchyRow(kind, fqn, parentFqn)` and `typeDependencies(): TypeDependency`. **The in-memory graph is type-level**: `applyClass` does *not* materialize member nodes (members live in the snapshot and are a strictly on-demand/detail concern — matching the production in-memory graph, which also keeps members out and serves detail from Neo4j today). A configurable `PlaceholderPolicy` decides which unscanned ref targets become placeholders; `JQA_ALIGNED` mirrors jQAssistant's virtual-type exclusions (primitives, arrays, default-package names, `java.lang.*` / `kotlin.*`, the two nullability annotations) — a projection policy only, so the refs stay in the snapshot regardless. Placeholder upgrade-in-place, owned-subgraph replacement, and still-referenced downgrade are implemented. 9 unit tests pass against hand-built `ClassFacts`.
- `io.hierograph.graphstore.parquet` — `ParquetSnapshotLoader` reads the `types` / `members` / `refs` tables (via **Hardwood** `dev.hardwood:hardwood-core:1.1.0.Beta3`, zero mandatory deps, Java 21+), reconstructs one `ClassFacts` per type, and feeds it through `GraphStore.applyClass` + a single `resolvePlaceholders()` — the same ingest path a live scan uses. `SnapshotWriter` writes the same tables from `ClassFacts` (ZSTD default; `SnapshotSchema` holds the flat layout, string kinds = enum names, explicit `artifact` column). 4 tests: loader fixtures + a writer→loader round-trip (uncompressed and ZSTD). Hardwood's row accessors are index-based (the loader resolves names→ordinals via the file schema); compression codecs are optional, ZSTD declared explicitly.
- `io.hierograph.graphstore.jqa` — the MVP stand-in scanner + diff harness. `JqaSnapshotExporter` runs the §7 export Cypher (`JqaExportCypher`) over an `IBoltClient` and reconstructs `ClassFacts`; `GraphDbMappingReference` runs the existing `jQAssistantMapping` rules against the same store and resolves Neo4j ids→FQNs into the same `HierarchyRow` / `TypeDependency` shapes (type-level: the FIELD/METHOD rules are skipped by default to match the type-level GraphStore); `SnapshotDiff` compares the two by value with `DiffOptions` to absorb the documented `other`/`weight` divergences. 3 pure unit tests cover the diff engine.

Decisions taken: GraphStore lives in its own top-level module group (parallel to the Neo4j `graphdb` stack); the FQN-keyed row shapes above are the diff seam (the existing SPI `IHierarchyDefinitionProvider` / `IDependencyDefinitionProvider` is `Long`-id-based and Neo4j-coupled, so the MVP diffs by FQN rather than forcing the GraphStore through that SPI). The in-memory graph is **type-level; members are on-demand** (carried in the snapshot, not materialized) — the GraphStore can later serve detail directly from the snapshot, replacing today's Neo4j side-query bypass. Parquet I/O: **Hardwood** (reader + a real `SnapshotWriter`; watch the beta-writer maturity). `TypeDependency.other` uses the spec-sketch semantic and is absorbed by `DiffOptions.ignoreDependencyOther`.

**Diff result (validated against a full Spring scan, Testcontainers jQA image, 2026-11-06):** type **dependencies match exactly**; the hierarchy matches except **10 rows** in two documented selection-seam categories:
- *only in GraphDbMapping* — `PACKAGE` rows. jQA models every jar directory (incl. resource dirs like `mockito-extensions`) and empty packages as `:Package`; the GraphStore derives packages from scanned type FQNs, so type-less packages never appear.
- *only in GraphStore* — type rows. A few types are reachable via `(:Artifact)-[:CONTAINS]->(:Type)` (the export seam) but not linked under their `:Package` in jQA, so the package-based reference rules miss them.

`GraphStoreVsGraphDbMappingIT` encodes this as the acceptance criterion **by category** (stable across scans): dependencies must be identical, reference-only rows must all be `PACKAGE`, GraphStore-only rows must all be type kinds — anything else fails. Run with `mvn -Pitest -pl hierograph-itest/io.hierograph.itest.tests -am verify -Dit.test=GraphStoreVsGraphDbMappingIT` (needs Docker); the IT prints the full diff before asserting.

**Read performance (`GraphStoreReadPerformanceIT`, same full Spring scan):** building the type-level in-memory graph from the Parquet snapshot takes a **median ~80 ms** vs **~71 s** from Neo4j via the mapping Cypher — **~900×** faster. (The Neo4j build is dominated by the `EXISTS`-heavy `DEPENDS_ON` dependency query.) Snapshot ≈ 3.6 MB (types 142 KB · members 2.86 MB · refs 592 KB); one-time Neo4j→Parquet export ≈ 2.9 s. Note the loader currently reads `members.parquet` (the bulk) only to discard it — a type-level cold start could skip it and be faster still. Timings are printed, not asserted (wall-clock thresholds would be flaky).

**Serving the production graph from Parquet (2026-11-06).** The GraphStore now feeds the existing Hierograph API with no Neo4j in the serving path:
- `io.hierograph.graphstore.hgmodel` — `GraphStoreToHGModel.build(store)` bridges a GraphStore to a production `HGModel` (root → modules → packages → types; type dependencies as core edges with the `JavaEdgeAttributes` bitmap), via a `GraphStoreNodeSource` that carries name/fqn so the existing `JQAssistantNodeMetadataProvider` resolves metadata off `INodeSource` — no bolt.
- **Node handles** (`NodeHandle`, in `hierarchicalgraph.core.model`): the `HGNode` identifier is a content-addressed hash of the GraphStore key (SHA-256 → 53-bit, JSON-safe), replacing Neo4j's allocation-order `id()`. Durable across rescans/restarts/backends; distinct per artifact because the key is; collision-checked at build.
- **Artifact-scoped type keys + `TypeResolver` seam** (see §3): type nodes are `type:<artifact>:<fqn>`; references resolve through the seam (owner-first stand-in for classpath order). `typeDependencyEdges()` added for the bridge; the FQN projections (`hierarchyRows`, `typeDependencies`) are unchanged, so the jQA diff and the detail engine are untouched.
- **Server switch:** `HierarchicalGraphService` gains `hierograph.graph.source=jqa|parquet` (+ `parquetDir`); in `parquet` mode it builds the model from files and never touches Neo4j. `ParquetModeServerIT` boots the service in parquet mode with bolt pointed at a dead port and drives real MCP tools end to end — the id-taking tools work because handles round-trip.
- **Detail tools on Parquet:** `detail_dependencies` (via a `@Primary` `DetailDependenciesRouter` over the `DetailQueryEngine`) and the single-entity `method_details` / `field_details` (each `@Tool` branches to a Parquet impl when `isParquetBacked`) now serve from `detail_refs.parquet` / `members.parquet` / `parameters.parquet` with no Neo4j. Member endpoints use `NodeHandle.of(memberKey)`; the service builds a `handle → member key` reverse index at load (members aren't nodes in the bridged model). To support full parity, the member schema gained a packed `modifiers` column (`MemberModifiers`) and a new `parameters.parquet` table (owner, index, name, type_fqn), both exported from jQA. Parquet-mode detail limitations: scopes for `detail_dependencies` must be containers (no member-as-scope); ~~member endpoint handles are stable but not navigable via the type-level hierarchy~~ (resolved by member materialization, below).
- **Members materialized in the in-memory graph (dependencies stay on demand):** reverses the earlier "type-level; members are on-demand" decision for *structure only*. `GraphStore.applyClass` now creates one node per declared member (`ClassFacts.members`, i.e. `members.parquet`) as a child of its type, keyed artifact-scoped like types — `member:<len>:<artifact>:<owner fqn>#<signature>` — so same-FQN types in different artifacts get distinct members; constructors are `java.method` nodes. Member-level *references* are **not** materialized: they stay in `detail_refs.parquet` and are served on demand by the detail engine, and member nodes carry no dependency edges. Member handles are `NodeHandle.of(<member node key>)`; `GraphStore.memberByKey(<owner#signature>)` resolves a bare detail-ref key to its node under the owner `typeByFqn` picks, and `HierarchicalGraphService.memberHandle` uses it for every detail-tool endpoint (members of unscanned owners fall back to a non-navigable `NodeHandle.of(memberKey)`). The handle→member-key index is now derived from the member nodes instead of a separate `members.parquet` scan. Parity with `GraphDbMapping` (members included): every declared member matches; the only reference-only rows are jQA's referenced-but-not-declared member stubs (library members, inherited members invoked through a subtype), which exist only via member refs and are deliberately not materialized (`GraphStoreVsGraphDbMappingIT`).
- **Type modifiers in the snapshot:** `types.parquet` gains an `int32 modifiers` column — packed with `TypeModifiers` (same bit layout as `MemberModifiers`: visibility, abstract, final, static), exported from jQA's `t.visibility` / `t.abstract` / `t.final` / `t.static`, and loaded into `ClassFacts.modifiers` (previously hard-coded 0). The writer always sets it, so a snapshot *without* the column is unambiguously older: it still loads, and `ParquetReader.TypeRow.modifiers` is `null` ("unknown") rather than guessing package-private. Served on demand via the Parquet-mode GraphQL `Node.properties` (`visibility`, `modifiers`, `abstract`, `final`, `static`). The values are jQA's: interfaces are not flagged `abstract` and nested types do not report `static` (jQA reads the class-level access flags, not the `InnerClasses` attribute) — the ASM scanner can tighten both.
- **`type_details` on Parquet:** the `@Tool` branches to `ParquetTypeDetails` when `isParquetBacked` (Cypher path unchanged otherwise), same response shape. Identity/parent/inner types from the model; modifiers + source file from `types.parquet`; superclass / interfaces / annotations from the type's `refs.parquet` rows via a scoped `ParquetReader.readRefsFrom` (targets the placeholder policy keeps out of the model, e.g. `java.lang.Object`, appear as id-less `external.type` refs); member counts from `members.parquet` (constructors counted separately). Not in the snapshot, so reported as `null`: `is_generic`, the type's source line, and modifiers of pre-column snapshots. With this, all four entity/detail tools (`type_details`, `method_details`, `field_details`, `detail_dependencies`) serve from Parquet.
- **Own bytecode scanner (step 2, API + FULL):** new module `io.hierograph.graphstore.scanner` (ASM 9.9.1). `BytecodeScanner.scan(artifacts)` reads JARs / class directories (input: explicit list; artifact key = file name, as jQA) into the existing `ClassFacts` + `DetailRef`; `ScanResult.writeTo(dir)` writes the standard snapshot via `ParquetWriter`; CLI `BytecodeScannerKt --out <dir> <jar-or-dir>...`. Key and naming conventions are jQA's (member keys `<owner>#<ret> <name>(<args>)`, `[]` in signatures, element types as reference targets, calls on the instruction's owner, lambdas linked to their implementation method), so node handles are unchanged. Type-level refs are fine-grained with counts — **weight = number of references** — using the kinds `EXTENDS`/`IMPLEMENTS`/`ANNOTATED_BY`, `FIELD_TYPE`/`PARAM_TYPE`/`RETURN_TYPE`/`THROWS`, new `MEMBER_ANNOTATED_BY`/`TYPE_ARGUMENT`/`ANNOTATION_VALUE`/`CLASS_LITERAL`, and `INVOKES`/`READS`/`WRITES`/`INSTANTIATES`/`CAST`/`CATCHES`; `DEPENDS_ON` is no longer produced. A post-scan `HierarchyResolver` adds `overrides` (same signature, else same name + parameters for covariant returns; bridges and real methods both linked), virtual calls (implementations with the exact called signature in scanned subtypes of a scanned owner) and calls resolved to the declaring supertype. Multi-release JARs: each class once, newest version wins. Real `classHash` (sha256 of the class file) and parameter names (MethodParameters → local-variable table → `argN`).
  **Parity on the 21 Spring 7.0.8 JARs** (scan + write: ~2.2 s): types 7,707 = 7,707; every declared member, `annotated_by`, `reads_field`, `writes_field` identical; type-dependency pairs: all of the scanner's are in jQA's; `calls` 98.3 %, `overrides` 97.8 % of jQA's pairs. Remaining differences are deliberate or jQA artefacts: jQA's referenced-but-undeclared stub members (4,967); correct modifiers (interfaces `abstract`, nested `static`, `default`, `synchronized`, top-level records not `static`); element types instead of jQA's `java.lang.Object` for arrays and generic signatures (for `returns`/`has_type`/`parameter_type` targets) and synthetic enum/anonymous-constructor params that jQA strips; no `InnerClasses`-attribute pseudo-dependencies (e.g. `MethodHandles$Lookup`) and no annotation-value *Java* types (`String`/`Integer` of `@Metadata`); `throws` = the declared clause, with body-thrown `throw new X(...)` split into a separate `raises` edge (`throws`∪`raises` ⊆ jQA's `throws`, which folds both together; the ~1k jQA-only remainder is rethrown/factory-produced/field-held exceptions, not constructor-thrown); `overrides` is the transitive closure (a method overrides the matching method in *every* supertype) where jQA records the direct override and chains, and covariant overrides are keyed to the compiler bridge method rather than jQA's user-method-against-erased-`Object` form; no `<clinit>` "overrides"; jQA bugs (package-less nested names like `Task`). The server runs end to end on a scanner-made snapshot (MCP and GraphQL), with unchanged node IDs.

**How close is a fully Neo4j-free MVP?** The **entire serving path is now Parquet** — type-level navigation/dependencies and all three detail tools (`detail_dependencies`, `method_details`, `field_details`), proved by `ParquetModeServerIT` with bolt pointed at a dead port. The **one remaining tie is the snapshot origin**: the Parquet files are still produced by exporting the existing jQA/Neo4j DB (`JqaParquetExporter` / `JqaDetailExporter`); the own ASM scanner (step 2) is not built yet, so Neo4j is still the data *source*. Building that scanner is what makes the MVP end-to-end Neo4j-free.

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
- [x] Duplicate FQNs: **resolved** — artifact-scoped type keys make each definition a distinct node (phase 1); reference binding goes through a `TypeResolver` seam (owner-first stand-in now).
- [ ] Classpath-order source for the resolver (phase 2): per-owner (per-module) dependency order from the Maven/Gradle build, vs a single global order.
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
