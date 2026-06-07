# GraphDB Model — Kotlin Reimplementation Specification

This document specifies the GraphDB-backed node and dependency sources for the
hierarchical graph. These types implement the core model's `INodeSource` and
`IDependencySource` and extend them with Neo4j-specific data (properties, labels,
relationship type) and lazy loading from a graph database via `IBoltClient`.

Depends on:
- `io.hierograph.hierarchicalgraph.core.model` (Kotlin model)
- `io.hierograph.boltclient` (existing module, `IBoltClient`)
- Neo4j Java driver (`org.neo4j.driver`)

---

## 1. Overview

When the hierarchical graph is populated from a Neo4j database, each node and dependency
needs to carry its Neo4j properties and labels/type. These are **lazily loaded** — not
fetched at graph construction time, but on first access. This avoids loading property data
for thousands of nodes upfront when only a few will be inspected.

Three source types are defined:

- **GraphDbNodeSource** — properties and labels for a regular node, lazily loaded.
  Holds the `IBoltClient` used for lazy loading.
- **GraphDbRootNodeSource** — the root node's source. A thin subclass of
  `GraphDbNodeSource` that adds no behaviour of its own; it exists so the root node's
  source can be type-identified distinctly.
- **GraphDbDependencySource** — properties and type for a dependency (Neo4j relationship).
  Properties are lazily loaded; the relationship type is set at construction. Also holds an
  optional `userObject` and the `IBoltClient`.

---

## 2. Entities

### 2.1 GraphDbNodeSource

An `open` class that implements `INodeSource`. Represents a Neo4j node.

| Member | Type | Description |
|--------|------|-------------|
| `identifier` | `Any` | The Neo4j node ID (Long). Constructor parameter; overrides `INodeSource.identifier`. |
| `node` | `HGNode?` | Back-reference. Overrides `INodeSource.node`. Defaults to `null`. |
| `boltClient` | `IBoltClient?` | The Bolt client used for lazy loading. Set after construction. Defaults to `null`. |
| `properties` | `Map<String, String>` | Neo4j node properties. **Lazily loaded** on first access. Read-only. |
| `labels` | `List<String>` | Neo4j node labels. **Lazily loaded** on first access. Read-only. |

The backing fields `_properties` and `_labels` are private and nullable; they act as the
"not yet loaded" sentinel.

**Lazy loading behavior:**

When `properties` or `labels` is accessed for the first time (backing field is `null`),
`loadNodeData()` runs and populates *both* fields:

1. `checkNotNull(boltClient)` — fails with
   "No bolt client set on GraphDbNodeSource for node $identifier." if unset.
2. Call `boltClient.getNode(identifier as Long)` to fetch the Neo4j `Node`.
3. Populate `labels` from `node.labels().toList()`.
4. Populate `properties` from `node.asMap()`, converting every value to a string via
   `toString()`.

### 2.2 GraphDbRootNodeSource

```kotlin
class GraphDbRootNodeSource(identifier: Any) : GraphDbNodeSource(identifier = identifier)
```

A subclass of `GraphDbNodeSource` that passes its `identifier` through to the superclass
constructor and adds nothing else. It therefore inherits the full lazy-loading machinery,
`boltClient`, `properties`, and `labels` from `GraphDbNodeSource`.

There is no separate `HGRootNode` type and no special "empty properties / empty labels"
behaviour — the root is a plain `HGNode` whose source is a `GraphDbRootNodeSource`. The
subclass exists only so the root node's source can be distinguished by type.

| Member | Type | Description |
|--------|------|-------------|
| `identifier` | `Any` | The root identifier. Constructor parameter, forwarded to `GraphDbNodeSource`. |
| (inherited) | | `node`, `boltClient`, `properties`, `labels` are all inherited from `GraphDbNodeSource`. |

### 2.3 GraphDbDependencySource

Implements `IDependencySource`. Represents a Neo4j relationship.

| Member | Type | Description |
|--------|------|-------------|
| `identifier` | `Any` | The Neo4j relationship ID (Long). Constructor parameter; overrides `IDependencySource.identifier`. |
| `type` | `String` | The Neo4j relationship type. Constructor parameter; set at construction (not lazy). |
| `dependency` | `HGCoreDependency?` | Back-reference. Overrides `IDependencySource.dependency`. Defaults to `null`. |
| `boltClient` | `IBoltClient?` | The Bolt client used for lazy loading. Set after construction. Defaults to `null`. |
| `userObject` | `Any?` | Optional application-defined object attached to this dependency. Defaults to `null`. |
| `properties` | `Map<String, String>` | Neo4j relationship properties. **Lazily loaded** on first access. Read-only. |

**Lazy loading behavior:**

When `properties` is accessed for the first time (backing field `_properties` is `null`),
`loadRelationshipData()` runs:

1. `checkNotNull(boltClient)` — fails with
   "No bolt client set on GraphDbDependencySource for dependency $identifier." if unset.
2. Call `boltClient.getRelationship(identifier as Long)` to fetch the Neo4j `Relationship`.
3. Populate `properties` from `relationship.asMap()`, converting every value to a string
   via `toString()`.

**Operations:**

| Operation | Return | Description |
|-----------|--------|-------------|
| `getUserObject<T : Any>(clazz: Class<T>)` | `T?` | Returns `userObject` cast to `T` if it is an instance of `clazz`, null otherwise (and null if `userObject` is null). |

---

## 3. Obtaining the IBoltClient

Lazy loading does **not** walk the graph to find the client. Each source holds its own
`boltClient` field directly:

- `GraphDbNodeSource.boltClient` (inherited by `GraphDbRootNodeSource`)
- `GraphDbDependencySource.boltClient`

The client is expected to be assigned to each source after construction (e.g. by the
mapping/graph-building service).

**Invariant:** The `boltClient` must be set on a source before any lazy loading on that
source occurs. If it is `null` when lazy loading is triggered, `checkNotNull` throws an
`IllegalStateException` with a message naming the offending identifier.

---

## 4. Scope Exclusions

- **HGNodeUtils, GraphTraversalUtil, GraphUtil** — debug/utility classes. Not part of
  this spec. Can be added later if needed.
- **EMF factory and package classes** — not needed in Kotlin.
- **CustomFactoryStandaloneSupport** — not needed (no EMF registry).
