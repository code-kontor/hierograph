# Hierograph GraphStore — Specification

`io.hierograph.graphstore.jvm.GraphStore` is Hierograph's **in-memory structural model**
of a JVM codebase. It holds what you navigate and analyse: artifacts, packages,
types and their members, arranged as a hierarchy, together with the type-to-type
dependencies between them. Every architectural view Hierograph offers is computed
from it, including the module tree, dependency matrices, layering and cycle
analysis, and impact analysis.

This document explains why the GraphStore exists, what it holds, how it behaves,
and how to work with it.

---

## Contents

1. [Purpose](#1-purpose)
2. [Where it sits](#2-where-it-sits)
3. [The model](#3-the-model)
4. [Identity and keys](#4-identity-and-keys)
5. [References and type resolution](#5-references-and-type-resolution)
6. [External types and placeholders](#6-external-types-and-placeholders)
7. [API reference](#7-api-reference)
8. [Working with the GraphStore](#8-working-with-the-graphstore)
9. [Invariants](#9-invariants)
10. [Boundaries](#10-boundaries)

---

## 1. Purpose

Architectural analysis needs two kinds of information at very different scales:

- **Structure:** which artifacts, packages, types and members exist, how they
  nest, and which types depend on which. Every query touches it, and it has to be
  traversed fast and in full.
- **Detail:** each individual call, field access, parameter and line number. This
  information is large, but any single query needs only a small slice of it.

The GraphStore holds the **structure**, and only the structure, in memory. Detail
stays on disk in the Parquet snapshot (see the
[snapshot format specification](hierograph-graphstore-format.md)) and is read on
demand, scoped to the types a query is about. This split keeps the in-memory
footprint proportional to the number of *declarations* rather than the number of
*instructions*, and it keeps whole-graph operations, such as rolling dependencies
up the hierarchy, entirely in memory.

The GraphStore has three design goals:

**One ingest path.** The store is fed one `ClassFacts` record per class, the same
record the bytecode scanner produces and the Parquet reader reconstructs. Building
from a fresh scan and building from a stored snapshot run identical code, so the
two can never diverge.

**Incremental by construction.** A class is the unit of change. Applying a new
version of a class replaces exactly what that class owns: its members and its
outgoing references. Nothing else is touched. A rescan of one changed class costs
time proportional to that class, not to the codebase.

**Stable identity.** Every node has a deterministic, content-derived key. Keys do
not depend on load order or process lifetime, so the handles built from them stay
the same across rescans and restarts.

### The GraphStore and the hierarchical graph

Hierograph has two graph models, and at first sight they look alike: both hold
modules, packages, types and members in a tree, with dependencies between types.
They answer different questions, though.

The **hierarchical graph** (`HGModel`) is the **analysis model**. It is generic: its
nodes have opaque identities, its edges are already resolved from node to node, and
it knows nothing about class files, artifacts, FQNs or classpaths. What it adds is
structure-aware computation. It can lay several hierarchies over the same nodes,
for example to try out a refactoring virtually. It rolls dependencies up subtrees
into accumulated and aggregated dependencies, and it runs the algorithms behind
dependency matrices, cycle detection and layering.

The **GraphStore** is the **ingest model**. It is JVM-aware: it takes class files'
facts as they come, in terms of artifacts, FQNs and member keys, and turns them
into a consistent structure that can be maintained one class at a time.

| | GraphStore | Hierarchical graph |
|---|---|---|
| Role | ingest and maintenance | analysis and navigation |
| Domain knowledge | JVM: artifacts, FQNs, member keys, classpaths | none: opaque nodes and edges |
| References | stored as facts, by **name**, per owning type, with their kind | **resolved** edges, one per node pair, with a weight and four flags |
| Duplicate FQNs, shadowing | resolved by the `TypeResolver` | already decided |
| Unscanned types | placeholders, managed by policy | ordinary nodes |
| Hierarchies | exactly one, given by the code's structure | any number over the same nodes |
| Change | per class: `applyClass` / `removeClass` | rebuilt from the GraphStore |
| Algorithms | none | rollups, DSM, SCC, feedback-arc ordering |

The hierarchical graph is **derived** from the GraphStore. A bridge creates one
node for each GraphStore node, with a handle computed from its key, places it under
its parent, and turns each resolved type dependency (`typeDependencyEdges()`) into a
weighted edge. When the code changes, the GraphStore is updated and a fresh
hierarchical graph is built from it.

The hierarchical graph is not enough on its own, for four reasons:

1. **Class files name their dependencies; they do not point at them.** A reference
   says `com.example.billing.Money`, not "that node". Binding names to nodes means
   knowing which artifacts define a name, which one the referencing artifact's
   classpath picks, and what to do when no artifact defines it. That is JVM
   knowledge, and keeping it in the GraphStore keeps the hierarchical graph generic.
2. **Incremental change needs late binding.** In the hierarchical graph, replacing a
   class would mean finding and rewiring every edge that points at it, including
   edges from classes that did not change. The GraphStore keeps references as names
   and binds them only when they are read, so applying or removing a class touches
   that class alone. The hierarchical graph can then simply be rebuilt.
3. **Projection loses information.** The hierarchical graph keeps one edge per pair
   of nodes, with a weight and four flags. The GraphStore keeps each reference's
   exact kind. It also keeps references to targets the placeholder policy excludes,
   which supertype walks rely on: following `extends` reaches `java.lang.Object`.
   And it can look up a type by its FQN, which detail queries need to map their
   results back to the structure.
4. **Separate concerns stay simple.** The GraphStore has no analysis algorithms, and
   the hierarchical graph has no ingest rules. Each can change without the other,
   and the hierarchical graph remains usable for any model that can describe itself
   as nodes, a tree and weighted edges.

---

## 2. Where it sits

```
   .class / .jar                       snapshot/*.hierograph
        │                                       │
        ▼                                       ▼
 ┌──────────────┐                      ┌────────────────┐
 │ BytecodeScan │                      │ ParquetReader  │
 └──────┬───────┘                      └───────┬────────┘
        │          ClassFacts (one per class)  │
        └──────────────────┬───────────────────┘
                           ▼
                 ┌───────────────────┐
                 │    GraphStore     │   hierarchy + type-level refs
                 └─────────┬─────────┘
          ┌────────────────┼──────────────────┐
          ▼                ▼                  ▼
  hierarchical graph   DetailScope       derived facts
  (navigation, DSM,    (scope → type     (overrides,
   layering, cycles)    FQNs for detail   virtual calls)
                        queries)
```

**Producers** feed the store `ClassFacts`. The `ParquetReader` loads a stored
snapshot. The server's build-watching mode ingests a fresh scan directly, without
first writing it to disk.

**Consumers** read the store's two projections, the node hierarchy and the
aggregated type dependencies:

- The **hierarchical-graph bridge** turns them into the graph model behind
  Hierograph's navigation and dependency tools.
- **`DetailScope`** expands a scope (a module, package or type) into the set of
  type FQNs whose member-level references a detail query should read.
- **Load-time resolution** combines the store's supertype edges with the
  snapshot's members to derive overrides and virtual-call targets.

---

## 3. The model

### 3.1 Nodes

The store is a forest of `Node`s. Each node knows its parent; the store keeps the
child index.

```kotlin
data class Node(
    val key: String,          // stable identity (§4)
    val kind: Any,            // a JavaNodeKind, or the string "external.type"
    val name: String,         // display name
    val fqn: String,          // qualified name, the human-facing identity
    val parent: String?,      // parent node's key; null for top-level modules
    val placeholder: Boolean, // true for an external-type placeholder (§6)
    val modifiers: Int,       // a type's or member's packed access flags; 0 otherwise
)
```

| Node | `kind` | `name` | `fqn` | Parent |
|------|--------|--------|-------|--------|
| Artifact | `MODULE` | the artifact's display name (`billing-core`) | as registered (normally the artifact key) | — |
| Package | `PACKAGE` | last segment (`billing`) | full package name (`com.example.billing`) | parent package, or the artifact |
| Type | `CLASS` · `INTERFACE` · `ENUM` · `RECORD` · `ANNOTATION` | the part after the last `.` (`Invoice`, `Invoice$Line`) | the type FQN | its package, or the artifact for the default package |
| Member | `FIELD` · `METHOD` | the member's signature | the member key (`<owner fqn>#<signature>`) | its declaring type |
| External module | `MODULE` | `External Types` | `External Types` | — |
| External type | `"external.type"` | simple name | the type FQN | a package under the external module |

Constructors and static initializers are `METHOD` nodes. In the hierarchy, a
type's methods include its constructors.

**`modifiers`** is the one declaration detail a type or member node carries beyond its
identity: the packed access flags of `TypeModifiers` / `MemberModifiers` (visibility,
`static`, `final`, `abstract`, …), taken from the `ClassFacts` or `MemberFacts`. It is
`0` on packages, modules and external types. Navigation reads it: `list_children` and
`list_descendants` filter members by modifier, and every browse result shows a type's
or member's modifiers, without reading the snapshot, which a result over a large
subtree could not afford per node.

Nested types are **not** nested in the hierarchy. `Invoice$Line` is a sibling of
`Invoice` in package `com.example.billing`. This mirrors the class-file view, in
which every nested type is a class of its own.

Packages are created implicitly, one node per segment, when the first type of a
package is applied. Each artifact has its own package nodes, so the same package
name in two artifacts gives two distinct subtrees.

### 3.2 References

Each applied type owns a list of **type-level references** (`Ref`), taken directly
from its `ClassFacts`:

```kotlin
data class Ref(val fromKey: String, val toKey: String, val kind: RefKind, val line: Int?, val count: Int?)
```

A reference names its target **by FQN only**. It is a fact about the class file,
and the class file cannot know which artifact will provide the target. References
are not stored as links between nodes. They are kept per owning type and bound to
target nodes when they are read (§5). This late binding is what makes incremental
updates cheap: when a target appears, disappears or moves, no edge has to be
rewritten.

Member-level references are deliberately absent. They stay in the snapshot and are
read on demand.

### 3.3 Aggregated dependencies

The store exposes references aggregated to **one edge per (source type, resolved
target type)**:

| Field | Meaning |
|-------|---------|
| endpoints | the source type and the resolved target, as FQNs (`TypeDependency`) or as node keys (`TypeEdge`) |
| `weight` | the sum of the references' `count`s (a missing count counts as 1): how strongly the source depends on the target |
| `extends` | at least one `EXTENDS` reference |
| `implements` | at least one `IMPLEMENTS` reference |
| `annotatedBy` | at least one `ANNOTATED_BY` reference (an annotation on the type itself) |
| `other` | at least one reference of any **non-structural** kind (a signature or body use) |

The flags are not mutually exclusive. A class that extends `Base` and also calls
one of its methods has an edge with both `extends` and `other` set. This lets an
analysis distinguish "inherits from" from "uses", and "only inherits from" from
"inherits from and uses".

A reference whose target resolves to the source type itself is dropped. A type does
not depend on itself.

---

## 4. Identity and keys

Every node has a **key**: a string that is unique within the store and derived only
from the node's content. Keys are the store's primary index, the parent pointers,
and the input from which Hierograph's public node handles are computed.

| Node | Key | Example |
|------|-----|---------|
| Artifact | the artifact key | `com.example:billing-core:1.4.0` |
| Package | `pkg:<artifact key>:<package fqn>` | `pkg:com.example:billing-core:1.4.0:com.example.billing` |
| Type | `type:<n>:<artifact key>:<type fqn>` | `type:30:com.example:billing-core:1.4.0:com.example.billing.Invoice` |
| Member | `member:<n>:<artifact key>:<member key>` | `member:30:com.example:billing-core:1.4.0:com.example.billing.Invoice#java.math.BigDecimal total()` |
| External module | `<external>` | `<external>` |
| External type | `type:10:<external>:<type fqn>` | `type:10:<external>:org.slf4j.Logger` |

`<n>` is the length of the artifact key, in characters. Artifact keys may contain
`:` (a Maven GAV always does), and the length prefix keeps type and member keys
unambiguous to split however the artifact key is spelled.

**Example.** Take the test class `InvoiceTest` in the test artifact
`com.example:billing-core:1.4.0:test`. Without a length prefix its key would be:

```
type:com.example:billing-core:1.4.0:test:com.example.billing.InvoiceTest
```

Every `:` in this string is a candidate for the end of the artifact key. Splitting
at the first one gives artifact `com.example`. Splitting at the fourth gives
artifact `com.example:billing-core:1.4.0` and the "type" `test:com.example.billing.InvoiceTest`.
Only knowledge of which characters may appear in an FQN would tell the reader
which split is correct. With the prefix, the key says where the artifact ends:

```
type:35:com.example:billing-core:1.4.0:test:com.example.billing.InvoiceTest
     ── └───────── 35 characters ─────────┘ └───────── type FQN ──────────┘
```

Read `35`, take the next 35 characters as the artifact key, skip one `:`, and the
rest is the FQN. The split is exact whatever characters either part contains. Two
different (artifact, FQN) pairs therefore never produce the same key: artifact
`a:b` with type `c` gives `type:3:a:b:c`, while artifact `a` with type `b:c` gives
`type:1:a:b:c`.

**Identity is artifact-scoped.** The same FQN can be defined by more than one
artifact: a shaded copy, two versions of a library, a class duplicated between
modules. Each definition is its own node under its own artifact. The FQN alone
identifies a *name*. A type node is identified by the pair (artifact, FQN).

Use `typeKey(artifactKey, fqn)` and `memberKey(artifactKey, memberKey)` to build
keys rather than assembling the strings by hand.

---

## 5. References and type resolution

Because references carry bare FQNs and FQNs can be defined more than once, every
reference has to be **bound** to one concrete target node. The store does this
when it computes the aggregated dependencies, separately for each source type and
in the context of that type's artifact:

1. If **no** artifact defines the target FQN, the target is the external
   placeholder `type:10:<external>:<fqn>` (§6).
2. Otherwise, the store asks its **`TypeResolver`** which of the defining
   artifacts provides the FQN *to the source's artifact*.

```kotlin
fun interface TypeResolver {
    fun resolve(ownerArtifactKey: String, fqn: String, definers: Set<String>): String
}
```

| Resolver | Rule |
|----------|------|
| `TypeResolver.OWNER_FIRST` (default) | the owner's own artifact if it defines the FQN; else the definer whose key sorts first |
| `TypeResolver.classpathOrder(classpathByOwner)` | the first artifact on the owner's classpath that defines the FQN, the JVM's own rule; falls back to `OWNER_FIRST` for an owner with no recorded classpath |

The classpath-order resolver is built from each artifact's ordered classpath (the
snapshot's `classpath.parquet`, or the build manifest). Index 0 of each classpath is
the owner itself. `OWNER_FIRST` is the right default when classpaths are unknown:
it is deterministic, and it agrees with classpath order in the common case where a
module's own classes shadow everything else.

The resolver is consulted when dependencies are *read*, not when classes are
applied. Installing a different resolver with `setTypeResolver` therefore takes
effect at the next query, without re-ingesting anything. By convention it is
installed before ingest begins.

---

## 6. External types and placeholders

A type that is referenced but not defined by any applied class is **external**.
Typically it belongs to a library or the JDK that was not scanned. It still matters
to analysis: "which modules depend on Spring?" is a question about external types.
The store represents it with a **placeholder** node:

- Placeholders live under a synthetic top-level module, the **external module**
  (key `<external>`, name `External Types`), inside an ordinary package chain built
  from the FQN.
- A placeholder has kind `"external.type"` and `placeholder = true`. It has no
  members and owns no references.
- The external module always exists, even when it is empty.

**Lifecycle.**

| Event | Effect |
|-------|--------|
| A reference to an undefined FQN is applied | its placeholder is created (if the policy allows it) |
| A class defining that FQN is applied | the placeholder is removed, together with any packages it leaves empty under the external module; references now bind to the real type |
| The last defining class is removed while references remain | the placeholder is created again |

Because references bind to nodes at query time (§5), these transitions never leave
an edge pointing at the wrong node, and nothing outside the affected class has to
be updated.

**Placeholder policy.** Some types are referenced by almost every class and carry no
analytical value as nodes: `java.lang.String`, `java.lang.Object`, primitives. A
`PlaceholderPolicy` decides which external FQNs become placeholder nodes:

```kotlin
fun interface PlaceholderPolicy {
    fun shouldMaterialize(fqn: String): Boolean
}
```

| Policy | Materializes a placeholder for … |
|--------|-----------------------------------|
| `PlaceholderPolicy.ALL` (default) | every external FQN |
| `PlaceholderPolicy.EXCLUDE_UBIQUITOUS` | every external FQN **except** primitives, JVM array descriptors, default-package names, `java.lang.*`, `kotlin.*`, and the JetBrains `@NotNull` / `@Nullable` annotations |

The policy only shapes the **in-memory projection**. It never removes a reference:
the reference stays with its owner, still appears in `typeDependencies()`, and
stays in the snapshot. It only means that no node exists for the target. A type
that is actually *scanned* is never affected, so a scanned `java.lang.String` is a
regular type node under any policy.

---

## 7. API reference

### 7.1 Construction

```kotlin
class GraphStore(
    placeholderPolicy: PlaceholderPolicy = PlaceholderPolicy.ALL,
    resolver: TypeResolver = TypeResolver.OWNER_FIRST,
)
```

A new store contains only the external module.

| Method | Description |
|--------|-------------|
| `setTypeResolver(resolver)` | Replaces the type resolver (§5). Takes effect at the next query. |

### 7.2 Ingest

| Method | Description |
|--------|-------------|
| `registerArtifact(artifact: ArtifactInfo)` | Creates the artifact's `MODULE` node from its key, display name and FQN. Idempotent: the first registration of a key wins. Register an artifact before applying its classes. |
| `applyClass(artifactKey, facts: ClassFacts)` | Adds or replaces one type under the artifact. In one step it removes the type's previous members and outgoing references, ensures the package chain, creates or updates the type node, creates a node for each member, stores the new references, and creates placeholders for any newly referenced external FQNs. If the FQN had a placeholder, the placeholder is removed. |
| `removeClass(artifactKey, fqn)` | Removes one type with its members and outgoing references. If other types still reference the FQN and no other artifact defines it, a placeholder takes its place. The type's package nodes are kept, even if they are now empty. Does nothing for a type that is not present. |
| `resolvePlaceholders()` | Ensures that every referenced FQN without a definer has its placeholder (subject to the policy). Idempotent. Call it once after a bulk ingest. |

`ArtifactInfo` describes an artifact: `key`, display `name`, `fqn` and `origin`
(`MODULE_MAIN`, `MODULE_TEST`, `LIBRARY` or `JDK`).

### 7.3 Projections

| Method | Returns |
|--------|---------|
| `nodes()` | every node, in insertion order |
| `hierarchyRows()` | one `HierarchyRow(kind, fqn, parentFqn)` per node: the hierarchy expressed purely in qualified names, with `parentFqn = null` for top-level modules |
| `typeDependencies()` | the aggregated dependencies (§3.3) with **FQN** endpoints (`TypeDependency(from, to, …)`) |
| `typeDependencyEdges()` | the same aggregated dependencies with **node-key** endpoints (`TypeEdge(fromKey, toKey, …)`) |

Choose between the last two by what the caller needs:

- **`typeDependencyEdges()`** connects the exact nodes, so two same-named types in
  different artifacts stay distinct. Use it to build a graph. A `toKey` may name a
  node that does not exist, because the placeholder policy excluded it, and graph
  builders skip such edges.
- **`typeDependencies()`** speaks in names. Use it for display, for comparing two
  stores, and for reasoning that has to include excluded targets. Following
  `extends` and `implements` here reaches all the way up to `java.lang.Object`.

All projections are computed lazily from the current state and are not cached.

### 7.4 Lookups

| Method | Returns |
|--------|---------|
| `node(key)` | the node with this key, or `null` |
| `contains(key)` | whether a node with this key exists |
| `childKeysOf(key)` | the keys of the node's children, in insertion order |
| `nodeCount()` | the number of nodes, including the external module |
| `typeByFqn(fqn)` | a type or placeholder node with this FQN: the first one applied when several artifacts define it |
| `typesWithFqn(fqn)` | every type or placeholder node with this FQN |
| `memberByKey(memberKey)` | the member node for a bare member key, looked up on the type `typeByFqn` returns for its owner, so a member and its owner always resolve consistently; `null` when the owner is external or does not declare it |
| `incomingOwnersOf(fqn)` | the FQNs of the types that hold at least one reference to `fqn` |
| `typeKey(artifactKey, fqn)` | the key of a type node (§4) |
| `memberKey(artifactKey, memberKey)` | the key of a member node (§4) |

`GraphStore.EXTERNAL_ARTIFACT_KEY` (`<external>`) and
`GraphStore.EXTERNAL_ARTIFACT_NAME` (`External Types`) name the external module.

---

## 8. Working with the GraphStore

### 8.1 Load a snapshot

The usual way to get a populated store is to load a Parquet snapshot. The reader
registers the artifacts, installs a classpath-order resolver when the snapshot
carries classpaths, applies every type, and resolves placeholders:

```kotlin
val store = GraphStore(PlaceholderPolicy.EXCLUDE_UBIQUITOUS)
ParquetReader().load(Path.of("snapshot"), store)
```

### 8.2 Build from a scan

To build without a round trip through Parquet, feed scanner output directly. The
order of steps matters: **resolver → artifacts → classes → placeholders**.

```kotlin
val key = "com.example:billing-core:1.4.0"
val result = BytecodeScanner().scan(listOf(ScanArtifact(key, Path.of("billing-core/target/classes"))))

val store = GraphStore(PlaceholderPolicy.EXCLUDE_UBIQUITOUS)
store.setTypeResolver(TypeResolver.classpathOrder(classpathByOwner))   // when classpaths are known
store.registerArtifact(ArtifactInfo(key, "billing-core", key, Origin.MODULE_MAIN))
for (c in result.classes) store.applyClass(c.artifactKey, c.facts)
store.resolvePlaceholders()
```

### 8.3 Apply a change

When a class file changes, apply its new facts; when it is deleted, remove it.
Nothing else needs to be told:

```kotlin
store.applyClass(key, rescanned.facts)               // changed or added class
store.removeClass(key, "com.example.billing.Draft")  // deleted class
```

References from unchanged classes to the changed type stay valid. If its kind
changed (a class became an interface), the node is updated in place and keeps its
key.

### 8.4 Query

```kotlin
// Navigate: the packages of an artifact.
store.childKeysOf("com.example:billing-core:1.4.0").map { store.node(it)!! }

// Who depends on Money, and how strongly?
store.typeDependencies().filter { it.to == "com.example.billing.Money" }
    .sortedByDescending { it.weight }

// Everything that merely *uses* a type, as opposed to inheriting from it.
store.typeDependencies().filter { it.to == "com.example.billing.Base" && it.other && !it.extends }

// A method node, from a key found in a detail reference.
store.memberByKey("com.example.billing.Invoice#java.math.BigDecimal total()")
```

For member-level questions, such as which methods call `Money.add`, expand the
scope with `DetailScope.expand(store, rootKey)` and query the snapshot's detail
references with that set of type FQNs.

### 8.5 Concurrency

The GraphStore is **not thread-safe**: it is a plain, mutable, single-threaded
structure, and it does no locking of its own. The intended pattern is
**build, then publish**:

1. Populate a store on one thread.
2. Publish it, for example through an atomic reference, and from then on treat it
   as read-only. Concurrent reads of an unchanging store are safe.
3. To change the graph, either build a new store and swap it in, or apply the
   changes while no reader can see the store.

Readers must never observe a store while it is being modified.

---

## 9. Invariants

After any sequence of `applyClass`, `removeClass` and `resolvePlaceholders` calls,
the following hold:

1. **Unique keys.** No two nodes share a key, and a node's key is a pure function
   of its content (§4).
2. **Closed hierarchy.** Every node's parent exists, provided each artifact was
   registered before its classes were applied. Top-level nodes are exactly the
   registered artifacts and the external module.
3. **Owned subgraph.** A type's member nodes and outgoing references are exactly
   those of the last `ClassFacts` applied for it. Nothing survives from a previous
   version.
4. **Defined or external, never both.** An FQN has a placeholder only if no applied
   class defines it. An FQN that is referenced, undefined and permitted by the
   policy has a placeholder.
5. **No self-edges.** No aggregated dependency connects a type to itself.
6. **Deterministic projections.** For the same sequence of operations, every
   projection yields the same content in the same order.

---

## 10. Boundaries

What the GraphStore deliberately does **not** do, and where it happens instead:

| Not in the GraphStore | Where it lives |
|------------------------|----------------|
| Persistence | the Parquet snapshot, written by `ParquetWriter` and read by `ParquetReader` |
| Member-level references (calls, field access, line numbers) | the snapshot's `detail_refs` table, read on demand |
| Parameters, type modifiers, source files, line ranges | the snapshot's `members`, `parameters` and `types` tables, read on demand |
| Overrides and virtual-call targets | load-time resolution, computed from the store's supertype edges together with the snapshot's members |
| Rolling dependencies up to packages and modules | the hierarchical graph built from the store's projections |
| A query language | Hierograph's tool surface (MCP tools, GraphQL), built on the projections |
| Thread safety | the owner of the store (§8.5) |

Keeping the store to structure alone is what keeps it small, fast to rebuild, and
cheap to update one class at a time.
