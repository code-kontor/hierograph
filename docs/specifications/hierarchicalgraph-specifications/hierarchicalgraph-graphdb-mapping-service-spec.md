# GraphDB Mapping Service — Kotlin Reimplementation Specification

This document specifies the mapping service that builds an `HGRootNode` from a Neo4j
database using a `MappingProvider`. It merges functionality from the former
`org.slizaa.hierarchicalgraph.graphdb.mapping.cypher` and
`org.slizaa.hierarchicalgraph.graphdb.mapping.service` modules into a single module.

Package: `io.hierograph.hierarchicalgraph.graphdb.mapping.service`

Depends on:
- `io.hierograph.hierarchicalgraph.core.model`
- `io.hierograph.hierarchicalgraph.graphdb.model`
- `io.hierograph.hierarchicalgraph.graphdb.mapping.spi`
- `io.hierograph.boltclient` (`IBoltClient`)

---

## 1. Overview

The mapping service orchestrates the construction of a hierarchical graph from Neo4j:

1. Initialize the hierarchy provider, then build root-level nodes and the parent-child tree.
2. Drop nodes left orphaned by missing hierarchy entries.
3. Initialize the dependency provider, then create the dependency edges.
4. Register extensions on the root node and dispose the providers.

The providers themselves (`IHierarchyDefinitionProvider`, `IDependencyDefinitionProvider`),
their data classes, and the Cypher-backed abstract base classes are defined in the
`...graphdb.mapping.spi` module — see the **GraphDB Mapping SPI** spec. This module
contributes only the service interface and its default implementation.

---

## 2. IMappingService

The main service interface.

```kotlin
interface IMappingService {
    fun convert(mappingProvider: MappingProvider, boltClient: IBoltClient): HGRootNode
}
```

Note the parameter is the concrete `MappingProvider` data class (from the SPI module),
not an interface.

### 2.1 Conversion Procedure

`DefaultMappingService.convert(mappingProvider, boltClient)` performs the following steps
(see `DefaultMappingService.kt`):

1. **Create root node**
   - Create `GraphDbRootNodeSource(identifier = -1L)` and set its `boltClient`.
   - Create the `HGRootNode` via `HierarchicalGraphFactory.createRootNode { rootNodeSource }`.
   - Register the bolt client as an extension: `rootNode.registerExtension(IBoltClient::class.java, boltClient)`.
   - Maintain an `idToNodeMap: MutableMap<Long, HGNode>` (Neo4j id → node).

2. **Initialize hierarchy provider**
   - If `hierarchyDefinitionProvider is IBoltClientAware`, assign its `boltClient` property.
   - Call `hierarchyDefinitionProvider.initialize()`.

3. **Build root-level nodes**
   - For each `ToplevelNodeId(id, kind)` in `hierarchyDefinitionProvider.toplevelNodeIds`:
     create (or reuse) the node parented directly to the root, then set its `kind` if not
     already set.

4. **Build hierarchy**
   - For each `ParentChildNodeId(parentId, childId, childKind)` in
     `hierarchyDefinitionProvider.parentChildNodeIds`:
     - Create or look up the parent node.
     - Create or look up the child node, set its parent, and set its `kind` if not already set.

5. **Remove dangling nodes**
   - Remove from `idToNodeMap` every node whose `parent == null` (orphaned because no
     hierarchy entry placed it under a parent). The root itself is never in the map.

6. **Initialize dependency provider**
   - If `dependencyDefinitionProvider is IBoltClientAware`, assign its `boltClient` property.
   - Call `dependencyDefinitionProvider.initialize()`.

7. **Build dependencies**
   - For each `DependencyDefinition` in `dependencyDefinitionProvider.dependencies`:
     - Look up `from` (`idStart`) and `to` (`idTarget`) in `idToNodeMap`; if either is
       missing, skip the dependency.
     - Create the core dependency via
       `HierarchicalGraphFactory.createCoreDependency(from, to, type) { dependencyDefinitionProvider.createDependencySource(depDef) }`.
     - Set `weight` and `attributesBitmap` on the resulting dependency.

8. **Register extensions on root**
   - `rootNode.registerExtension(MappingProvider::class.java, mappingProvider)`.
   - (`IBoltClient` was already registered in step 1.)

9. **Dispose providers**
   - Call `dispose()` on both the hierarchy and dependency providers.

10. **Return the `HGRootNode`.**

### 2.2 Node Creation Helpers

`DefaultMappingService` factors node/dependency creation into private helpers:

- **`createNodeIfAbsent(id, rootNode, parent, idToNodeMap, hierarchyProvider)`** — returns
  the existing node for `id` if present (setting its parent if it had none and a parent is
  now known); otherwise creates a node via
  `hierarchyProvider.createNodeSource(id)`, using `HierarchicalGraphFactory.createNode` when
  a parent is known or `createOrphanNode` when it is not, and records it in `idToNodeMap`.
- **`setKindIfNull(node, kind)`** — assigns `node.kind = kind` only if the node's kind is
  currently `null` (first writer wins).
- **`createDependency(depDef, idToNodeMap, dependencyProvider)`** — performs the lookup,
  skip-if-missing, and dependency creation described in step 7.

---

## 3. Extensions Registered on the Root

After conversion, the `HGRootNode` carries these extensions:

| Extension key | Value |
|---------------|-------|
| `IBoltClient` | the bolt client used for the conversion (registered in step 1). |
| `MappingProvider` | the original mapping provider (registered in step 8). |

There is no `INodeMetadataProvider` extension — node metadata is not part of the current
SPI.

---

## 4. Cypher-Based Providers

The abstract base classes `AbstractQueryBasedHierarchyProvider` and
`AbstractQueryBasedDependencyProvider` — which implement the provider interfaces on top of
`IBoltClient` Cypher queries, including the dependency row/attribute-bitmap format — now
live in the SPI module's `...graphdb.mapping.spi.bolt` package. They are documented in the
**GraphDB Mapping SPI** spec (Section 3). The mapping service injects the bolt client into
any provider implementing `IBoltClientAware` (steps 2 and 6 above) before calling
`initialize()`.

---

## 5. Scope Exclusions

- **Proxy dependencies** — `IProxyDependencyDefinition`, `ProxyDependencyDefinitionImpl`,
  `ProxyDependencyQueriesHolder`, `CustomProxyDependencyResolver`, and all related
  `addProxyDependencyDefinitions()` methods are dropped. All dependencies are eagerly resolved.
- **IMappingParticipator** — post-create hooks. Can be added later if needed.
- **MappingFactory** — EMF registration helper. Not needed in Kotlin.
- **MappingException** — use standard Kotlin exceptions (`IllegalStateException`, etc.).
