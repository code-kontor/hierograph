# Hierograph Hierarchical Graph Serialization — Specification

`io.hierograph.hierarchicalgraph.serialization` writes a hierarchical graph model
(`HGModel`) to **JSON** and reads it back. Its single entry point, `HGGraphJson`,
turns a model into one flat, self-describing document: the tree as a list of node
records, the core dependencies as a list of edge records, and nothing that can be
recomputed. Reading the document rebuilds an equivalent model through the model's
own factories.

This document explains why the serializer exists, what it stores and leaves out,
the JSON document format field by field, and how to work with it.

---

## Contents

1. [Purpose](#1-purpose)
2. [Where it sits](#2-where-it-sits)
3. [What is serialized](#3-what-is-serialized)
4. [Document format](#4-document-format)
   - [4.1 `GraphSnapshot`](#41-graphsnapshot)
   - [4.2 `NodeRecord`](#42-noderecord)
   - [4.3 `DepRecord`](#43-deprecord)
   - [4.4 `KindRef`](#44-kindref)
   - [4.5 `SourceRef`](#45-sourceref)
   - [4.6 Example](#46-example)
5. [Identifiers](#5-identifiers)
6. [Kinds](#6-kinds)
7. [Source codecs](#7-source-codecs)
8. [Writing](#8-writing)
9. [Reading](#9-reading)
10. [Errors](#10-errors)
11. [API reference](#11-api-reference)
12. [Working with the serializer](#12-working-with-the-serializer)
13. [Guarantees](#13-guarantees)
14. [Boundaries](#14-boundaries)

---

## 1. Purpose

A hierarchical graph model lives in memory, as a web of objects. Nodes point at
their dependencies and dependencies point back at their nodes. The tree is held by
a `Hierarchy`, node identity and metadata are held by pluggable source objects, and
the hierarchy keeps lazy caches of derived results. A generic object-graph
serializer would have to be taught about every one of these cycles, interfaces and
caches, and its output would mirror the implementation instead of the model.

The serializer instead **projects** the model onto the few facts that define it,
and writes those as plain records. Its design goals:

**Flat and acyclic.** Every node and every core dependency is one record, and
records refer to each other only by identifier strings. The document has no object
cycles however densely the graph is connected, and it can be read with any JSON
tool.

**Facts, not derived results.** Only what cannot be recomputed is stored: the tree,
each node's kind and source, and each core dependency with its type, weight and
attributes. Accumulated and aggregated dependencies, predecessor chains and every
other cached result are left out. They rebuild on first access after reading.

**Rebuilt through the factories.** Reading never constructs model objects directly.
It calls `HGGraphFactory` and `HierarchyFactory`, exactly as any other builder does,
so a read model satisfies every invariant of the
[hierarchical graph model](../../io.hierograph.hierarchicalgraph.model/spec/hierarchicalgraph-model.md).

**Open to new source types.** Node and dependency sources are an extension point of
the model. The serializer doesn't know them statically. It delegates each source
type to a **codec** that encodes the source as a string map, and the document
records which codec wrote it.

### The JSON snapshot and the Parquet snapshot

Hierograph has two persistent forms that both carry the word *snapshot*. They store
different models for different purposes.

| | Hierarchical graph JSON snapshot | GraphStore Parquet snapshot |
|---|---|---|
| Model | `HGModel`: generic nodes, a tree and weighted edges | the GraphStore's JVM facts: types, members, references |
| Granularity | one document for a whole model | one archive per artifact |
| References | resolved edges between node identifiers | names (FQNs, member keys), bound at load time |
| Member-level detail | none | calls, field accesses, parameters, line numbers |
| Format | a single JSON document | Parquet tables in a ZIP archive |
| Specification | this document | [Parquet snapshot format](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md) |

The Parquet snapshot is the system of record for scanned code. The JSON snapshot
stores a hierarchical graph as such, independent of where it came from: a
hand-built model, a test fixture, or a model to exchange with another tool.

---

## 2. Where it sits

```
                 ┌───────────────────┐
                 │      HGModel      │   core graph + one hierarchy
                 └─────────┬─────────┘
                           │ HGGraphJson.write
                           ▼
                 ┌───────────────────┐
                 │    GraphWriter    │   pre-order walk, codec per source
                 └─────────┬─────────┘
                           ▼
                    GraphSnapshot  ──── Jackson ────►  JSON document
                           ▲
                 ┌─────────┴─────────┐
                 │    GraphReader    │   two passes, through the factories
                 └─────────┬─────────┘
                           │ HGGraphJson.read
                           ▼
                 ┌───────────────────┐
                 │      HGModel      │   caches cold, rebuilt lazily
                 └───────────────────┘
```

The module depends only on the hierarchical graph model and on Jackson (with its
Kotlin module). It knows nothing about the GraphStore, the JVM or the server.

**Producers** are callers holding an `HGModel` they want to keep or hand on.
**Consumers** are callers that need that model back, in the same process or
another one.

---

## 3. What is serialized

| Part of the model | Serialized? | Notes |
|-------------------|-------------|-------|
| The hierarchy's tree, from `rootNode` down | **yes** | one `NodeRecord` per node, with its parent |
| Child order | **yes** | records are written in child-iteration order and read back in the same order |
| Node identifier | **yes** | as a string, with its original type restored by the codec (§5) |
| Node kind | **yes** | enum constants and strings (§6) |
| Node source | **yes** | through its codec (§7) |
| Core dependencies | **yes** | type, weight, attribute bitmap and source |
| Hierarchy `name` | no | the read hierarchy's name is `null` |
| Further hierarchies over the same core graph | no | only `model.hierarchy` is written |
| Core-graph nodes that are not in the tree | no | the writer walks the tree; nodes outside it are dropped |
| Local nodes | as ordinary nodes | a local node in the tree is written like any other node and read back as a core-graph node |
| Accumulated and aggregated dependencies, predecessors | no | derived; they rebuild lazily |
| Graph extensions (`registerExtension`) | no | runtime objects owned by the caller |
| Object identity | no | a read model has new objects; identifiers are equal, references are not (`===` does not hold) |

Two consequences matter in practice:

- **Every dependency must stay inside the tree.** Dependencies are collected from
  the outgoing dependencies of the nodes in the tree. A dependency whose source is
  outside the tree is silently lost. A dependency whose *target* is outside the tree
  is written, but reading the document then fails, because the target has no
  record (§10).
- **A scenario round-trips as a plain model.** A forked hierarchy with moved nodes
  and local nodes can be written. The read model has the scenario's structure as its
  only hierarchy, and its local nodes have become ordinary core-graph nodes.

---

## 4. Document format

A document is one JSON object, a `GraphSnapshot`. The field names below are the
JSON property names. Field order within an object follows the tables.

### 4.1 `GraphSnapshot`

The document root.

| Field | Type | Null? | Description |
|-------|------|-------|-------------|
| `schemaVersion` | integer | no | Version of this format. The current and only version is `1`. |
| `root` | `NodeRecord` | no | The hierarchy's root node. |
| `nodes` | array of `NodeRecord` | no | Every other node of the tree, in pre-order. |
| `deps` | array of `DepRecord` | no | Every core dependency, each exactly once. |

**`schemaVersion`** lets a reader refuse a document it can't interpret instead of
misreading it. A reader accepts only its own version (§9). A document without the
field is read as version `1`.

**`root`** is kept apart from `nodes` because it is the one node without a parent,
and because a hierarchy is created from its root before any other node can be
attached.

**`nodes`** is in **pre-order**: every node appears after its parent, and siblings
appear in the hierarchy's child order. This lets the reader attach each node to its
parent in a single pass, and it preserves child order.

**`deps`** follows the order in which the writer meets the dependencies: for each
node in pre-order, the node's outgoing dependencies in their list order.

### 4.2 `NodeRecord`

One node of the tree.

| Field | Type | Null? | Description |
|-------|------|-------|-------------|
| `id` | string | no | The node's identifier, as a string (§5). |
| `parentId` | string | yes | The parent's `id`. `null` for the root, and only for the root. |
| `kind` | `KindRef` | yes | The node's kind (§6). `null` when the node has no kind. |
| `source` | `SourceRef` | no | The node's source, encoded by its codec (§7). |

**`id`** is the node's identity in the document. `parentId`, `fromId` and `toId`
refer to it. It is also the input from which the codec restores the source's
original identifier.

**`parentId`** carries the tree. It is the only structural information in the
document; children lists, predecessors and depths all follow from it.

**`kind`** is the application's classification of the node (module, package,
class, …). The model treats it as opaque, but nearly every consumer filters on it.

**`source`** carries the node's identity and metadata in the form its source type
defines. Without it, a read node would have an identifier and nothing else.

### 4.3 `DepRecord`

One core dependency.

| Field | Type | Null? | Description |
|-------|------|-------|-------------|
| `id` | string | no | The dependency source's identifier, as a string (§5). |
| `fromId` | string | no | `id` of the node the dependency starts at. |
| `toId` | string | no | `id` of the node it points to. |
| `type` | string | no | The dependency's type classifier, e.g. `DEPENDS_ON`. |
| `weight` | integer | no | The dependency's weight. |
| `attributesBitmap` | integer | no | The dependency's attribute bits, as stored on the dependency. |
| `source` | `SourceRef` | no | The dependency's source, encoded by its codec (§7). |

**`id`** restores the dependency source's identifier. It is not used as a
reference and need not be unique.

**`fromId`** and **`toId`** are the edge. Both must name a node record of the same
document.

**`type`** distinguishes kinds of edges that a model may hold side by side.

**`weight`** is what accumulated and aggregated dependencies sum. Without it every
rollup in the read model would count edges instead of references.

**`attributesBitmap`** is stored as the raw integer. The serializer doesn't
interpret the bits; their meaning belongs to whoever built the model. In models
built from a GraphStore, the bits are the `JavaEdgeAttributes` flags *extends*,
*implements*, *annotated by* and *other use*.

### 4.4 `KindRef`

A node kind, reduced to two strings.

| Field | Type | Null? | Description |
|-------|------|-------|-------------|
| `type` | string | no | Fully-qualified name of the kind's class, e.g. `io.hierograph.graphstore.jvm.model.JavaNodeKind`. |
| `value` | string | no | The kind's value: the constant's `name` for an enum, the string itself for a `String`. |

The class name makes any enum round-trip without the serializer depending on the
module that defines it (§6).

### 4.5 `SourceRef`

A node or dependency source, as written by its codec.

| Field | Type | Null? | Description |
|-------|------|-------|-------------|
| `type` | string | no | The `typeId` of the codec that wrote the source, e.g. `default-node`. |
| `payload` | object of string → string | no | The codec's encoding of the source. Its keys and values mean something only to that codec. |

**`type`** selects the codec on read. It names a codec, not a class, so a class can
be renamed or replaced without invalidating documents.

**`payload`** keeps the format open: any source type can be stored as long as a
codec can express it as a flat string map. The codecs in this module also use it
for the identifier's type marker, `_idType` (§5).

### 4.6 Example

A module with one package, two classes and one dependency:

```
root
 └── com.example:billing-core:1.4.0        (MODULE)
      ├── com.example.billing              (PACKAGE)
      │    └── com.example.billing.Invoice (CLASS)    ── DEPENDS_ON, weight 3 ──┐
      └── com.example.money.Money          (CLASS)    ◄─────────────────────────┘
```

All sources are `DefaultNodeSource` / `DefaultDependencySource` with `Long` node
identifiers, a `String` dependency identifier, and an `fqn` property:

```json
{
  "schemaVersion" : 1,
  "root" : {
    "id" : "1",
    "parentId" : null,
    "kind" : null,
    "source" : { "type" : "default-node", "payload" : { "_idType" : "long" } }
  },
  "nodes" : [ {
    "id" : "2",
    "parentId" : "1",
    "kind" : { "type" : "io.hierograph.graphstore.jvm.model.JavaNodeKind", "value" : "MODULE" },
    "source" : { "type" : "default-node",
                 "payload" : { "_idType" : "long", "fqn" : "com.example:billing-core:1.4.0" } }
  }, {
    "id" : "3",
    "parentId" : "2",
    "kind" : { "type" : "io.hierograph.graphstore.jvm.model.JavaNodeKind", "value" : "PACKAGE" },
    "source" : { "type" : "default-node",
                 "payload" : { "_idType" : "long", "fqn" : "com.example.billing" } }
  }, {
    "id" : "4",
    "parentId" : "3",
    "kind" : { "type" : "io.hierograph.graphstore.jvm.model.JavaNodeKind", "value" : "CLASS" },
    "source" : { "type" : "default-node",
                 "payload" : { "_idType" : "long", "fqn" : "com.example.billing.Invoice" } }
  }, {
    "id" : "5",
    "parentId" : "2",
    "kind" : { "type" : "io.hierograph.graphstore.jvm.model.JavaNodeKind", "value" : "CLASS" },
    "source" : { "type" : "default-node",
                 "payload" : { "_idType" : "long", "fqn" : "com.example.money.Money" } }
  } ],
  "deps" : [ {
    "id" : "Invoice -> Money",
    "fromId" : "4",
    "toId" : "5",
    "type" : "DEPENDS_ON",
    "weight" : 3,
    "attributesBitmap" : 8,
    "source" : { "type" : "default-dep", "payload" : { "_idType" : "string" } }
  } ]
}
```

`attributesBitmap` `8` is bit 3, *other use*: `Invoice` uses `Money` in a
signature or method body, without extending or implementing it. The pretty-printed
output puts every field on its own line; the example folds the short objects to
keep it readable.

---

## 5. Identifiers

The model types identifiers as `Any`. In the document they are strings, which gives
every identifier one stable wire form and lets records refer to each other with
plain string comparison.

Turning the string back into the original value is the codec's job. The codecs in
this module store the identifier's type in the payload under `_idType`:

| `_idType` | Identifier type | Written as | Read back with |
|-----------|-----------------|------------|----------------|
| `long` | `Long` | `toString()` | `String.toLong()` |
| `int` | `Int` | `toString()` | `String.toInt()` |
| `string` | `String` | the string | as is |

Writing a source whose identifier has any other type fails (§10). The marker is
what makes the identifiers in a read model *equal* to the original ones, so lookups
such as `model.lookupNode(id)` keep working with identifiers held from before the
round-trip.

**Node identifiers must be unique by their string form.** `id`, `parentId`,
`fromId` and `toId` are matched as strings. Two nodes whose identifiers differ only
in type (`1L` and `"1"`) are both read back, but parent and edge references to
`"1"` resolve to whichever of them was read last.

---

## 6. Kinds

A node's `kind` is written as a `KindRef` and restored as follows:

| Kind on the node | `KindRef` written | Read back as |
|------------------|-------------------|--------------|
| `null` | `null` | `null` |
| an enum constant | `type` = enum class name, `value` = constant `name` | the constant of that name, looked up on the class loaded by `Class.forName(type)` |
| a `String` | `type` = `java.lang.String`, `value` = the string | the string |

Enums are restored by **constant name**, never by `toString()` or ordinal. Kind
enums often override `toString()` (`JavaNodeKind.CLASS` prints as `java.class`), and
reordering constants mustn't change the meaning of stored documents.

The enum class has to be loadable on the reader's classpath. No registration is
needed. Kinds of any other type can't be written (§10).

---

## 7. Source codecs

### 7.1 Codecs

A codec converts one source type to a `SourceRef` payload and back. There are two
codec interfaces, one for node sources and one for dependency sources:

```kotlin
interface NodeSourceCodec<S : INodeSource> {
    val typeId: String                       // written to SourceRef.type
    val sourceClass: Class<S>                // the source type this codec writes
    fun write(source: S): Map<String, String>
    fun read(identifier: String, payload: Map<String, String>): INodeSource
}

interface DepSourceCodec<S : IDependencySource> {
    val typeId: String
    val sourceClass: Class<S>
    fun write(source: S): Map<String, String>
    fun read(identifier: String, payload: Map<String, String>): IDependencySource
}
```

`read` returns the SPI type, not `S`. A codec may therefore write one source type
and read back a different, simpler one. A source that wraps a live resource can be
stored as its identifier and metadata, and come back as a plain source.

A codec is responsible for its source's identifier: `read` receives it as the
record's `id` string and must restore the original value.

### 7.2 The registry

A `CodecRegistry` holds the codecs and resolves them:

- **On write**, by the source's class: the exact class first, then each superclass
  in turn. A codec registered for a class also serves its subclasses, unless a
  subclass has a codec of its own.
- **On read**, by `SourceRef.type`, matched exactly against the registered
  `typeId`s.

Either lookup fails when no codec matches (§10).

### 7.3 Codecs provided

`HGGraphJson` uses `CodecRegistry.defaults()`, which contains:

| `typeId` | Source type | Payload | Reads back as |
|----------|-------------|---------|---------------|
| `default-node` | `DefaultNodeSource` (and subclasses) | `_idType`, then every entry of `properties` | `DefaultNodeSource(identifier, properties)` |
| `default-dep` | `DefaultDependencySource` (and subclasses) | `_idType`, then every entry of `properties` | `DefaultDependencySource(identifier, properties)` |

`properties` keeps its order. A property named `_idType` can't be stored, because
the key is reserved for the identifier's type.

Every other source type, including `SyntheticNodeSource` and the server's
`GraphStoreNodeSource`, has no codec in the default registry. A model containing
such a source can't be written with `HGGraphJson` (§10). In particular, models built
from a GraphStore can't be written, because all their nodes except the root carry a
`GraphStoreNodeSource`.

---

## 8. Writing

`HGGraphJson.write` hands the model to a `GraphWriter` and the resulting
`GraphSnapshot` to Jackson.

The writer makes **one pre-order walk** of `model.hierarchy`, starting at
`rootNode` and descending through `childrenOf`. For each node it:

1. encodes the node as a `NodeRecord`, with its parent's identifier, its kind and
   its source;
2. adds the node's outgoing core dependencies to an insertion-ordered set.

Each core dependency appears in exactly one node's outgoing list, so collecting
from the outgoing side alone yields every dependency inside the tree exactly once.
The set then becomes `deps`.

The writer reads only the tree, the nodes and their outgoing dependencies. It never
touches the hierarchy's derived results, so writing doesn't warm, evaluate or
invalidate any cache, and it doesn't modify the model.

**Determinism.** For the same model, the output is identical byte for byte. All
orders come from the model: child order, outgoing-list order and property order.

---

## 9. Reading

`HGGraphJson.read` parses the document into a `GraphSnapshot` and hands it to a
`GraphReader`, which rebuilds the model in **two passes**.

**Validation.** The reader first checks `schemaVersion`. A document of any other
version is rejected. Jackson ignores unknown fields, so a document may carry
additional fields that this version doesn't define. Missing required fields are
rejected.

**Pass 1: nodes.**

1. Create an empty core graph with `HGGraphFactory.createHGGraph()`.
2. Create the root with `HGGraphFactory.createNode`, its source from the root
   record's codec, and set its kind.
3. Create the hierarchy over the core graph and the root with
   `HierarchyFactory.createHierarchy`.
4. For each record in `nodes`, in order: look up the parent by `parentId`, create
   the node the same way, and attach it with `HierarchyFactory.addChild`.

**Pass 2: dependencies.** For each record in `deps`, look up both endpoints by
`fromId` and `toId`, create the dependency with
`HGGraphFactory.createCoreDependency`, and set its `weight` and `attributesBitmap`.

The result is `HGModel(coreGraph, hierarchy)`. Because everything was built through
the factories, every node is registered in the core graph and every source has its
back-reference to its node or dependency. Nothing was computed during the build, so
the hierarchy's caches are cold and fill on first access.

---

## 10. Errors

Both directions fail on the first problem and produce no partial result.

| Situation | Direction | Exception |
|-----------|-----------|-----------|
| A node or dependency source has no codec | write | `IllegalStateException` (`No NodeSourceCodec registered for <class>`) |
| A source identifier is not a `Long`, `Int` or `String` | write | `UnsupportedOperationException` |
| A node kind is not `null`, an enum or a `String` | write | `UnsupportedOperationException` |
| `schemaVersion` is not `1` | read | `IllegalArgumentException` |
| The document is not valid JSON, or a required field is missing | read | a Jackson exception |
| `SourceRef.type` names no registered codec | read | `IllegalStateException` |
| A payload has no or an unknown `_idType` | read | `IllegalArgumentException` |
| A non-root node has no `parentId`, or a parent that appears later or not at all | read | `IllegalArgumentException` |
| A dependency's `fromId` or `toId` names no node record | read | `IllegalArgumentException` |
| A kind's enum class can't be loaded | read | `ClassNotFoundException` |
| A kind's enum has no constant of that name | read | `IllegalArgumentException` |

---

## 11. API reference

`HGGraphJson` is the module's public API. Everything in the `.internal` package
(records, writer, reader, codecs, registry) is an implementation detail and is not
meant to be used directly.

```kotlin
object HGGraphJson {
    fun write(model: HGModel, prettyPrint: Boolean = false): String
    fun write(model: HGModel, sink: OutputStream, prettyPrint: Boolean = false)

    fun read(json: String): HGModel
    fun read(source: InputStream): HGModel
}
```

| Method | Description |
|--------|-------------|
| `write(model, prettyPrint)` | Returns the document as a string. Compact by default; `prettyPrint = true` indents it. |
| `write(model, sink, prettyPrint)` | Writes the document to `sink`. The stream is left open. |
| `read(json)` | Reads a document from a string. |
| `read(source)` | Reads a document from `source`. |

`HGGraphJson` is a stateless singleton. It holds two preconfigured Jackson mappers
(compact and indented) and one writer and one reader over the default codecs. All of
them are safe to share, so `HGGraphJson` may be called from several threads at
once, as long as no thread modifies a model while it is being written.

The codec registry is fixed. `HGGraphJson` offers no way to add codecs.

---

## 12. Working with the serializer

### 12.1 Save and load a model

```kotlin
Files.newOutputStream(path).use { HGGraphJson.write(model, it) }

val restored: HGModel = Files.newInputStream(path).use { HGGraphJson.read(it) }
```

Use the stream variants for large models; they don't build the whole document as a
string first.

### 12.2 Inspect a model

```kotlin
println(HGGraphJson.write(model, prettyPrint = true))
```

The indented form is meant for reading and diffing. Since output is deterministic,
two documents of the same model are identical, and a diff of two models shows
exactly their differences.

### 12.3 Build a model for the serializer

Use `DefaultNodeSource` and `DefaultDependencySource`, with `Long`, `Int` or
`String` identifiers, and put metadata into their `properties`. Give nodes an enum
or `String` kind. Attach every node to the tree, and keep every dependency between
nodes of the tree.

```kotlin
val graph = HGGraphFactory.createHGGraph()
val root = HGGraphFactory.createNode(graph) { DefaultNodeSource(1L) }
val hierarchy = HierarchyFactory.createHierarchy(graph, root)

val invoice = HGGraphFactory.createNode(graph) {
    DefaultNodeSource(2L, mutableMapOf("fqn" to "com.example.billing.Invoice"))
}.also { it.kind = JavaNodeKind.CLASS }
HierarchyFactory.addChild(hierarchy, root, invoice)

val json = HGGraphJson.write(HGModel(graph, hierarchy))
```

### 12.4 After a round-trip

- Look nodes up by identifier, `restored.lookupNode(id)`. Don't compare node
  objects across the round-trip.
- Set the hierarchy's `name` again if the caller relies on it.
- Expect the first structural query to take longer: the caches are cold.

---

## 13. Guarantees

For a model whose sources all have codecs, whose identifiers are unique by string
form, and whose dependencies all lie inside the tree:

1. **Structure.** The read hierarchy has the same root, the same parent of every
   node and the same child order, compared by identifier.
2. **Identity.** Every node and dependency identifier is equal to the original and
   of the same type.
3. **Attributes.** Every node's kind, and every dependency's endpoints, type,
   weight and attribute bitmap, are equal to the original.
4. **Sources.** Every source is restored by its codec. For the provided codecs:
   same source class and the same `properties` in the same order.
5. **Equivalent results.** Because the core facts are equal, every derived result
   (accumulated and aggregated dependencies, DSMs, cycles) computed on the read
   model equals the one computed on the original.
6. **Valid model.** The read model is built by the model's factories and satisfies
   all of the model's invariants.
7. **Determinism.** Writing the same model always produces the same document.
8. **Non-interference.** Writing doesn't modify the model or its caches.

---

## 14. Boundaries

| Not in the serializer | Where it lives |
|-----------------------|----------------|
| Persisting scanned code and member-level detail | the GraphStore's Parquet snapshot |
| Building a model from a GraphStore | the server's mapping layer |
| Several hierarchies, hierarchy names, extensions | the caller; only the facts of one model are stored |
| Derived results | the hierarchy, which recomputes them on demand |
| Interpreting kinds, attribute bits or properties | whoever built the model |
| Migrating documents between format versions | none; a reader accepts its own version only |
| Compression | the caller; the document compresses well with any general-purpose codec |

Storing only the facts of one tree, in flat records, is what keeps the format
small, stable and readable by any JSON tool, and what lets the model rebuild
everything else itself.
