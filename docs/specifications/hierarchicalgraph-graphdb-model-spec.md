# GraphDB Model — Kotlin Reimplementation Specification

This document specifies the GraphDB-backed node and dependency sources for the
hierarchical graph. These types extend the core model's `INodeSource` and
`IDependencySource` with Neo4j-specific data (properties, labels) and lazy loading
from a graph database via `IBoltClient`.

Depends on:
- `io.hierograph.hierarchicalgraph.core.model` (Kotlin model)
- `org.slizaa.core.boltclient` (existing Java module, `IBoltClient`)
- Neo4j Java driver (`org.neo4j.driver`)

---

## 1. Overview

When the hierarchical graph is populated from a Neo4j database, each node and dependency
needs to carry its Neo4j properties and labels. These are **lazily loaded** — not fetched
at graph construction time, but on first access. This avoids loading property data for
thousands of nodes upfront when only a few will be inspected.

Three source types are defined:

- **GraphDbNodeSource** — properties and labels for a regular node, lazily loaded.
- **GraphDbRootNodeSource** — the root node's source, holds the `IBoltClient` connection.
  Properties and labels are always empty (the root is a virtual container, not a Neo4j node).
- **GraphDbDependencySource** — properties and type for a dependency (Neo4j relationship),
  lazily loaded. Also holds an optional `userObject`.

---

## 2. Entities

### 2.1 GraphDbNodeSource

Implements `INodeSource`. Represents a Neo4j node.

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The Neo4j node ID (Long). Inherited from `INodeSource`. |
| `node` | `HGNode?` | Back-reference. Inherited from `INodeSource`. |
| `properties` | `Map<String, String>` | Neo4j node properties. **Lazily loaded** on first access. |
| `labels` | `List<String>` | Neo4j node labels. **Lazily loaded** on first access. |

**Lazy loading behavior:**

When `properties` or `labels` is accessed for the first time:
1. Obtain the `IBoltClient` from the root node's `GraphDbRootNodeSource`.
2. Call `boltClient.getNode(identifier as Long)` to fetch the Neo4j `Node`.
3. Populate `labels` from `node.labels()`.
4. Populate `properties` from `node.asMap()`, converting all values to strings.

### 2.2 GraphDbRootNodeSource

Extends `GraphDbNodeSource`. The root node's source — holds the database connection.

| Property | Type | Description |
|----------|------|-------------|
| `boltClient` | `IBoltClient?` | The Bolt client for Neo4j access. Set after graph construction. |

**Overridden behavior:**

- `properties` always returns an empty map (the root is not a real Neo4j node).
- `labels` always returns an empty list.

### 2.3 GraphDbDependencySource

Implements `IDependencySource`. Represents a Neo4j relationship.

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The Neo4j relationship ID (Long). Inherited from `IDependencySource`. |
| `dependency` | `HGCoreDependency?` | Back-reference. Inherited from `IDependencySource`. |
| `properties` | `Map<String, String>` | Neo4j relationship properties. **Lazily loaded** on first access. |
| `type` | `String` | The Neo4j relationship type. Set at construction time (not lazy). |
| `userObject` | `Any?` | Optional application-defined object attached to this dependency. |

**Lazy loading behavior:**

When `properties` is accessed for the first time:
1. Obtain the `IBoltClient` from the root node's `GraphDbRootNodeSource`
   (via `dependency.from.rootNode.nodeSource`).
2. Call `boltClient.getRelationship(identifier as Long)` to fetch the Neo4j `Relationship`.
3. Populate `properties` from `relationship.asMap()`, converting all values to strings.

**Operations:**

| Operation | Return | Description |
|-----------|--------|-------------|
| `getUserObject<T>(clazz: Class<T>)` | `T?` | Returns `userObject` cast to `T` if it is an instance, null otherwise. |

---

## 3. Obtaining the IBoltClient

All lazy loading requires access to the `IBoltClient`. The access path is always:

```
node.rootNode.nodeSource  →  (cast to GraphDbRootNodeSource)  →  .boltClient
```

For dependency sources:
```
dependency.from.rootNode.nodeSource  →  (cast to GraphDbRootNodeSource)  →  .boltClient
```

**Invariant:** The `boltClient` must be set on the `GraphDbRootNodeSource` before any
lazy loading occurs. If it is null when lazy loading is triggered, an error is thrown.

---

## 4. Scope Exclusions

- **HGNodeUtils, GraphTraversalUtil, GraphUtil** — debug/utility classes. Not part of
  this spec. Can be added later if needed.
- **EMF factory and package classes** — not needed in Kotlin.
- **CustomFactoryStandaloneSupport** — not needed (no EMF registry).
