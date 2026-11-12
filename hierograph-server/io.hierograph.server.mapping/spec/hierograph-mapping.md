# Hierograph GraphStore Mapping — Specification

`io.hierograph.server.mapping` is Hierograph's **bridge from the ingest model to the
analysis model**. It turns a populated `GraphStore` into an `HGModel`: one node per
GraphStore node, the GraphStore's tree as the hierarchy, and one weighted core
dependency per resolved type-to-type dependency. Every navigation, dependency and
view tool the server offers answers against the model this bridge builds.

This document explains why the bridge exists, what it reads, what it builds, how
it identifies nodes, and how to work with it.

---

## Contents

1. [Purpose](#1-purpose)
2. [Where it sits](#2-where-it-sits)
3. [Inputs](#3-inputs)
4. [Output](#4-output)
   - 4.1 [Nodes](#41-nodes)
   - 4.2 [Hierarchy](#42-hierarchy)
   - 4.3 [Core dependencies](#43-core-dependencies)
   - 4.4 [`GraphStoreNodeSource`](#44-graphstorenodesource)
5. [Identity and handles](#5-identity-and-handles)
6. [What it does not produce](#6-what-it-does-not-produce)
7. [Determinism, errors and concurrency](#7-determinism-errors-and-concurrency)
8. [Example](#8-example)
9. [API reference](#9-api-reference)
10. [Working with the mapping](#10-working-with-the-mapping)
11. [Guarantees](#11-guarantees)
12. [Boundaries](#12-boundaries)

---

## 1. Purpose

Hierograph keeps two graph models. The **GraphStore** is the ingest model: it is
JVM-aware, takes class files' facts one class at a time, keeps references by name
and binds them to nodes only when they are read. The **hierarchical graph**
(`HGModel`) is the analysis model: it is generic, holds resolved node-to-node edges,
lays hierarchies over its nodes and rolls dependencies up subtrees. The
[GraphStore specification](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore.md)
explains why both are needed.

Something has to carry the facts from one to the other. The GraphStore must not
know the hierarchical graph, because it would then depend on an analysis library
it never uses. The hierarchical graph must not know the GraphStore, because it
would lose its genericity (see the
[hierarchical graph model specification](../../../hierograph-hierarchicalgraph/io.hierograph.hierarchicalgraph.model/spec/hierarchicalgraph-model.md)).
The mapping module depends on both and is the only place where their vocabularies
meet.

The mapping has three design goals:

**A pure projection.** `build` reads the store and returns a fresh model. It keeps
no state between calls and never writes to the store. When the code changes, the
owner updates the store and calls `build` again; there is no incremental path to
keep consistent.

**Stable identity.** Every node's identifier is a handle computed from its
GraphStore key alone (§5). The same type gets the same handle in every build, in
every process and on every machine, so a handle an agent received before a rescan
still addresses the same type afterwards.

**Everything a tool needs, in memory.** Each node carries its display name,
qualified name, GraphStore key and modifier bits on its source (§4.4). Navigation
and filtering read them directly, without going back to the store or the snapshot.

---

## 2. Where it sits

```
   ParquetReader              ManifestGraphSource
   (stored snapshot)          (fresh scan)
          │                          │
          └────────────┬─────────────┘
                       ▼
            ┌─────────────────────┐
            │     GraphStore      │   nodes() + typeDependencies()
            └──────────┬──────────┘
                       ▼
            ┌─────────────────────┐
            │ GraphStoreToHGModel │   this module
            └──────────┬──────────┘
                       ▼
            ┌─────────────────────┐
            │       HGModel       │   core graph + hierarchy
            └──────────┬──────────┘
        ┌──────────────┼───────────────────┐
        ▼              ▼                   ▼
  graph service    MCP tools          view service
  (snapshot,       (navigation,       (forks with
   search index)    detail, filters)   group nodes)
```

**Producers.** The `HierarchicalGraphService` fills a GraphStore, either from a
stored Parquet snapshot (`ParquetReader`) or from a fresh scan of the build's
modules (`ManifestGraphSource`), and passes it to `GraphStoreToHGModel.build`. Both
paths use the external-type filter `EXCLUDE_UBIQUITOUS`.

**Consumers** read the built model and the node sources on it:

- The **graph service** wraps the model in an immutable snapshot with a search
  index and a data hash, and serves it to every tool.
- **`ParquetNodePropertiesProvider`** reads `GraphStoreNodeSource.key` to find a
  node's owning artifact and `qualifiedName` to look up its members.
- **`ParquetTypeDetails`** reads `key` to locate a type's row in the snapshot
  tables.
- **`NodeRefFactory`** and **`ModifierFilter`** read `modifiers` to report and filter
  by visibility, `static`, `abstract` and similar flags.
- The **view service** forks the model and adds group nodes; the forks share this
  model's core graph.

---

## 3. Inputs

`build` reads exactly two projections of the store:

| Projection | Type | What the bridge takes from it |
|------------|------|-------------------------------|
| `store.nodes()` | `Collection<Node>` | every module, package, type, member and external-type placeholder, with key, kind, name, FQN, parent key and modifiers |
| `store.typeDependencies()` | `Sequence<TypeDependency>` | one aggregated dependency per (source type, resolved target type), with both endpoints as node keys, a weight and four flags |

```kotlin
data class Node(
    val key: String,          // stable, artifact-scoped identity
    val kind: Any,            // JavaNodeKind, or the string "external.type"
    val name: String,         // display name: simple name, package segment, signature
    val fqn: String,          // qualified name; for members, "<owner fqn>#<signature>"
    val parent: String?,      // parent's key; null for top-level modules
    val placeholder: Boolean, // referenced but not scanned
    val modifiers: Int,       // packed access flags of a type or member; 0 otherwise
)

data class TypeDependency(
    val fromKey: String,      // the referencing type's node key
    val toKey: String,        // the resolved target's node key
    val fromFqn: String,      // the same endpoints by name (not read by the bridge)
    val toFqn: String,
    val weight: Int,          // sum of the references' counts
    val extends: Boolean,     // at least one EXTENDS reference
    val implements: Boolean,  // at least one IMPLEMENTS reference
    val annotatedBy: Boolean, // at least one ANNOTATED_BY reference
    val other: Boolean,       // at least one non-structural reference
)
```

**Why node keys and not FQNs.** Each `TypeDependency` names its endpoints both as
FQNs and as node keys. An FQN can be defined in more than one artifact (shaded
jars, duplicate classes), and each definition is a distinct node. Only the node
keys, already resolved through the store's `TypeResolver`, say which of those nodes
an edge connects. The bridge therefore reads only `fromKey` and `toKey`, and never
resolves a name itself.

The bridge does not read references, member-level facts or any snapshot table.

---

## 4. Output

`build` returns an `HGModel` with a single hierarchy over a single core graph.

### 4.1 Nodes

The core graph holds one `HGNode` per GraphStore node plus one synthetic **root**.
Each GraphStore node is mapped field by field:

| `HGNode` | Taken from | Why |
|----------|-----------|-----|
| `identifier` | `NodeHandle.of(node.key)` | the stable, JSON-safe handle tools pass around (§5) |
| `kind` | `node.kind` | lets tools and algorithms tell modules, packages, types and members apart |
| `nodeSource` | a `GraphStoreNodeSource` (§4.4) | carries name, qualified name, key and modifiers |

The kinds the bridge passes through:

| GraphStore node | `kind` | `name` | `qualifiedName` |
|-----------------|--------|--------|-----------------|
| Artifact | `java.module` | the artifact's name | the artifact's FQN |
| External module | `java.module` | `External Types` | `External Types` |
| Package | `java.package` | the last segment (`billing`) | the package FQN (`com.example.billing`) |
| Scanned type | `java.class`, `java.interface`, `java.enum`, `java.record`, `java.annotation` | simple name (`Invoice`) | type FQN |
| External-type placeholder | `external.type` | simple name (`BigDecimal`) | type FQN |
| Method or constructor | `java.method` | the signature | `<owner fqn>#<signature>` |
| Field | `java.field` | the signature | `<owner fqn>#<signature>` |

The **root** has the identifier `NodeHandle.of("<root>")`, a `DefaultNodeSource` and
no kind. It exists because a hierarchy needs exactly one root, while the GraphStore
has several top-level modules. `NodeMetadata.getKind` reports it as `Unknown`, and
its name and qualified name are empty.

### 4.2 Hierarchy

The bridge places every node under the node its `parent` key names. A node without
a parent (a registered artifact or the external module) goes under the root. The
result has the same shape as the GraphStore's tree, one level deeper:

```
<root>
├── <external>                       External Types
│   └── packages of placeholders
│       └── external types
└── <artifact key>                   one per registered artifact
    └── packages, one per segment
        └── types
            └── members
```

Placement does not depend on the order of `store.nodes()`: the hierarchy accepts a
child before its parent is itself placed. A node whose `parent` key names no node
in the store goes under the root. In a store built by the GraphStore's own ingest
operations this does not occur (see the GraphStore's *closed hierarchy* invariant).

### 4.3 Core dependencies

Each `TypeDependency` becomes one `HGCoreDependency`:

| `HGCoreDependency` | Value | Why |
|--------------------|-------|-----|
| `from`, `to` | the nodes for `fromKey` and `toKey` | edges connect artifact-scoped nodes, so two same-FQN types stay distinct |
| `type` | `DEPENDS_ON` | the hierarchical graph needs a type string; the four flags carry the distinction |
| `weight` | `TypeDependency.weight` | how strongly the source depends on the target; rollups sum it |
| `attributesBitmap` | the four flags, encoded with `JavaEdgeAttributes` | lets tools tell "inherits from" from "uses" without a second edge type |
| `dependencySource` | `DefaultDependencySource("<fromKey> -> <toKey>")` | a readable identifier for diagnostics |

The bitmap uses these bit positions:

| Flag | Bit | Value | Set when |
|------|-----|-------|----------|
| `extends` | `IS_EXTENDS` = 0 | 1 | the source extends the target |
| `implements` | `IS_IMPLEMENTS` = 1 | 2 | the source implements the target |
| `annotatedBy` | `IS_ANNOTATED_BY` = 2 | 4 | the source type is annotated with the target |
| `other` | `IS_DEPENDS_ON_OTHER` = 3 | 8 | the source uses the target in a signature or a body |

A class that extends `Base` and calls one of its methods gets one edge with bitmap
`1 + 8 = 9`. Tools turn the bitmap into JSON with `JavaEdgeAttributes.toMap`, which
names the bits `is_extends`, `is_implements`, `is_annotated_by` and
`is_depends_on_other`.

**Edges to absent nodes are dropped.** A `TypeDependency` whose `fromKey` or `toKey` names
no node in the store produces no core dependency. This happens when the external-type
filter keeps an unscanned target out of the store (`java.lang.String` under
`EXCLUDE_UBIQUITOUS`): the store still reports the edge, because it keeps references
as facts, but the model has no node to attach it to. The reference stays available
to detail queries, which read the snapshot.

### 4.4 `GraphStoreNodeSource`

```kotlin
class GraphStoreNodeSource(
    override val identifier: Any,     // the node handle, a Long (§5)
    override val name: String,        // GraphStore Node.name
    override val qualifiedName: String, // GraphStore Node.fqn
    val key: String,                  // GraphStore Node.key
    val modifiers: Int = 0,           // GraphStore Node.modifiers
) : INodeSource {
    override var node: HGNode?        // set by HGGraphFactory: source ↔ node
}
```

**`name`** and **`qualifiedName`** implement `INodeSource`, so `NodeMetadata` and the
search index read them without knowing the source type. They are copied when the
model is built, which keeps display lookups free of store access.

**`key`** maps a node back to the GraphStore and the snapshot. Detail tools walk
from a type's key up to its module to find the artifact a `types.parquet` row is
filed under. The key survives in a view fork even when a restructuring moves the
node under a different parent, so a moved type still reads its facts from the
artifact that defines it.

**`modifiers`** holds the packed access flags of a scanned type (`TypeModifiers`) or
a member (`MemberModifiers`), and `0` for every other node. Tools read it to report
modifiers and to filter by them, for example "public types only", without reading
the snapshot.

Consumers recognise a mapped node with `node.nodeSource as? GraphStoreNodeSource`.
The root and the group nodes a view adds have other source types; for them the cast
yields `null`.

---

## 5. Identity and handles

A node's **handle** is `NodeHandle.of(key)`: the first 64 bits of the SHA-256 of its
GraphStore key, masked to 53 bits. The
[hierarchical graph model specification](../../../hierograph-hierarchicalgraph/io.hierograph.hierarchicalgraph.model/spec/hierarchicalgraph-model.md#8-node-handles)
defines the function; this section describes what the bridge feeds it.

| Node | Key the handle is computed from | Example key |
|------|---------------------------------|-------------|
| Root | `<root>` | `<root>` |
| Artifact | the artifact key | `com.example:billing-core:1.4.0` |
| Package | `pkg:<artifact key>:<package fqn>` | `pkg:com.example:billing-core:1.4.0:com.example.billing` |
| Type | `type:<n>:<artifact key>:<type fqn>` | `type:30:com.example:billing-core:1.4.0:com.example.billing.Invoice` |
| Member | `member:<n>:<artifact key>:<member key>` | `member:30:com.example:billing-core:1.4.0:com.example.billing.Invoice#java.math.BigDecimal total()` |
| External module | `<external>` | `<external>` |
| External type | `type:10:<external>:<type fqn>` | `type:10:<external>:java.math.BigDecimal` |

`<n>` is the length of the artifact key in characters (30 for
`com.example:billing-core:1.4.0`, 10 for `<external>`). The
[GraphStore specification](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore.md#4-identity-and-keys)
explains why it is there.

Three properties follow from hashing the key:

- **Stable across builds.** Building twice from equal stores yields equal handles.
  A type that survives a rescan keeps its handle; a removed type's handle simply no
  longer resolves (`lookupNode` returns `null`).
- **Distinct across artifacts.** The artifact is part of the key, so
  `com.example.billing.Invoice` in `billing-core` and the same FQN in a shaded jar
  get two handles and two nodes.
- **Collision-checked.** While building, the bridge records every handle with
  `NodeHandle.assertNoCollision` and fails if two different keys produce the same
  one (§7). Serving an ambiguous handle would send a tool to the wrong node; failing
  the build keeps the previous model in service instead.

The key, not the handle, is what the bridge stores on the node source. A handle
cannot be turned back into a key; the key is needed to reach the store and the
snapshot (§4.4).

---

## 6. What it does not produce

| Not produced | Why |
|--------------|-----|
| Member-to-member or member-to-type dependencies | the store keeps member-level references in the snapshot only; detail tools read them on demand |
| Edges to targets the external-type filter excludes | the model has no node for them (§4.3) |
| Self-edges | the store drops a reference that resolves to its own type |
| Parallel edges between the same pair of types | the store aggregates to one edge per pair; the four flags record the reference kinds |
| A second hierarchy | views fork the model and add their own groups (view service) |
| Node properties beyond name, qualified name, key and modifiers | the graph service's properties provider reads them from the snapshot on demand |

---

## 7. Determinism, errors and concurrency

**Determinism.** Handles depend on keys alone. Node creation follows the order of
`store.nodes()`, and edge creation the order of `store.typeDependencies()`; both
are deterministic for a given sequence of store operations. Equal stores therefore
yield models with equal identifiers, equal hierarchies and equal edges.

**Errors.**

| Situation | Effect |
|-----------|--------|
| Two different keys hash to the same handle | `IllegalStateException` from `NodeHandle.assertNoCollision`, naming both keys; no model is returned |
| A node's `parent` names no node | the node is placed under the root |
| A `TypeDependency` endpoint names no node | the edge is skipped |
| An empty store | a model with the root and the external module only |

The graph service catches a failed build and keeps serving the last good snapshot.

**Concurrency.** `build` reads the store without locking. The GraphStore is not
thread-safe, so the caller must not modify the store while `build` runs. The
returned model is not shared with the store and can be published to any number of
reader threads.

---

## 8. Example

A store holds the artifact `com.example:billing-core:1.4.0` with three scanned
types, built with `EXCLUDE_UBIQUITOUS`:

| Type | References |
|------|-----------|
| `com.example.billing.Invoice` | `com.example.money.Money` (3 uses), `java.math.BigDecimal` (2 uses) |
| `com.example.billing.Ledger` | `com.example.billing.Invoice` (4 uses) |
| `com.example.money.Money` | `java.lang.String` (2 uses) |

The store has 12 nodes. `java.math.BigDecimal` is unscanned and included by the
filter, so it becomes a placeholder under the external module. `java.lang.String`
is excluded, so it has no node. The bridge builds 13 nodes (the 12 plus the root):

```
<root>
├── <external>                          java.module
│   └── java                            java.package
│       └── java.math                   java.package
│           └── BigDecimal              external.type
└── com.example:billing-core:1.4.0      java.module
    └── com                             java.package
        └── com.example                 java.package
            ├── com.example.billing     java.package
            │   ├── Invoice             java.class
            │   └── Ledger              java.class
            └── com.example.money       java.package
                └── Money               java.class
```

The store reports four type edges. The bridge turns three of them into core
dependencies and drops the one whose target has no node:

| From | To | Weight | Bitmap | In the model |
|------|----|--------|--------|--------------|
| `Ledger` | `Invoice` | 4 | 8 (`other`) | yes |
| `Invoice` | `Money` | 3 | 8 (`other`) | yes |
| `Invoice` | `BigDecimal` (external) | 2 | 8 (`other`) | yes |
| `Money` | `java.lang.String` | 2 | 8 (`other`) | no: no target node |

`Invoice`'s identifier is
`NodeHandle.of("type:30:com.example:billing-core:1.4.0:com.example.billing.Invoice")`,
and its source carries `name = "Invoice"`,
`qualifiedName = "com.example.billing.Invoice"` and that key. Rolled up in the
hierarchy, `com.example.billing` depends on `com.example.money` with weight 3 and on
the external module with weight 2.

---

## 9. API reference

```kotlin
object GraphStoreToHGModel {
    fun build(store: GraphStore): HGModel
}

class GraphStoreNodeSource(
    identifier: Any,
    name: String,
    qualifiedName: String,
    key: String,
    modifiers: Int = 0,
) : INodeSource
```

| Member | Description |
|--------|-------------|
| `GraphStoreToHGModel.build(store)` | Builds a fresh `HGModel` from the store's nodes and type edges (§4). Reads the store, never modifies it. Throws `IllegalStateException` on a handle collision. |
| `GraphStoreNodeSource.key` | The node's GraphStore key, for mapping back to the store and the snapshot. |
| `GraphStoreNodeSource.modifiers` | Packed access flags of a type or member; `0` for every other node. |
| `GraphStoreNodeSource.name`, `qualifiedName` | Display name and qualified name, as `INodeSource` defines them. |

The module has no configuration. Which unscanned types appear as nodes is decided
by the `ExternalTypeFilter` the store was created with; which artifact a name
resolves to is decided by the store's `TypeResolver`.

---

## 10. Working with the mapping

### 10.1 Build a model from a snapshot

```kotlin
val store = GraphStore(ExternalTypeFilter.EXCLUDE_UBIQUITOUS)
ParquetReader().load(snapshotDir, store)
val model: HGModel = GraphStoreToHGModel.build(store)
```

### 10.2 Rebuild after a change

The model is not updated in place. Apply the change to the store, then build again
and swap the result in:

```kotlin
store.applyClass("com.example:billing-core:1.4.0", invoiceFacts)
val next = GraphStoreToHGModel.build(store)
current.set(next)            // e.g. an AtomicReference read by the tools
```

Handles of unchanged nodes are the same in `next` as in the previous model.

### 10.3 Look up a node by its GraphStore key

```kotlin
val key = store.typeKey("com.example:billing-core:1.4.0", "com.example.billing.Invoice")
val invoice: HGNode? = model.lookupNode(NodeHandle.of(key))
```

### 10.4 Get from a node back to the store

```kotlin
val source = node.nodeSource as? GraphStoreNodeSource ?: return null  // root or view group
val stored: Node? = store.node(source.key)
val visibility = MemberModifiers.visibility(source.modifiers)  // types share the bit layout
```

### 10.5 Read an edge's flags

```kotlin
for (dep in invoice.outgoingCoreDependencies) {
    val inherits = JavaEdgeAttributes.isSet(dep.attributesBitmap, JavaEdgeAttributes.IS_EXTENDS)
    val flags: Map<String, Boolean> = JavaEdgeAttributes.toMap(dep.attributesBitmap)
}
```

---

## 11. Guarantees

For any store built by the GraphStore's ingest operations, the model `build`
returns satisfies:

1. **One node per store node.** The core graph has exactly `store.nodes().size + 1`
   nodes: one per GraphStore node and the root.
2. **Same tree.** Every node's parent in the hierarchy is the node for its
   GraphStore parent; top-level modules are children of the root.
3. **Key-derived identity.** Every mapped node's identifier is
   `NodeHandle.of(key)`, and no two nodes share an identifier.
4. **One edge per materialised type dependency.** For every `TypeDependency` whose endpoints
   both exist in the store, the model has exactly one core dependency between the
   two nodes, with the same weight and the four flags in its bitmap. There are no
   other core dependencies.
5. **Type-level edges only.** Every core dependency connects two type nodes (scanned
   types or external-type placeholders), never a module, package or member.
6. **Independence.** The model shares no mutable state with the store. Changing the
   store afterwards does not change the model.

**Caller obligations.**

- Do not modify the store while `build` runs.
- Treat a failed build as fatal for that build only: keep serving the previous
  model.

---

## 12. Boundaries

| Not in the mapping | Where it lives |
|--------------------|----------------|
| Name resolution, shadowing, duplicate FQNs | the GraphStore's `TypeResolver` |
| Which unscanned types become nodes | the GraphStore's `ExternalTypeFilter` |
| Aggregating references into weighted, flagged edges | the GraphStore's `typeDependencies()` |
| Rollups, dependency matrices, cycles, layering | the hierarchical graph and its algorithms module |
| Snapshot lifecycle, search index, reload and file watching | the graph service |
| Views and restructurings | the view service, which forks the built model |
| Member-level references and node properties | the Parquet snapshot, read on demand by the detail tools |

Keeping the bridge to a single stateless function is what lets the server rebuild
the analysis model after every change instead of maintaining it incrementally.
