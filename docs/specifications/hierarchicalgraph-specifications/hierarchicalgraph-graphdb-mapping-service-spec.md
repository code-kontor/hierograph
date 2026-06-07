# GraphDB Mapping Service — Kotlin Reimplementation Specification

This document specifies the mapping service that builds an `HGModel` from a Neo4j
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

1. Create the core graph (`HGGraph`) and the root node, then create the `Hierarchy`.
2. Initialize the hierarchy provider, then build the top-level nodes and the parent-child
   tree, each guarded so a node is never added to the hierarchy twice.
3. Initialize the dependency provider, then create the dependency edges (skipping any whose
   endpoints were never created).
4. Register extensions on the core graph, dispose the providers, and return the `HGModel`.

The providers themselves (`IHierarchyDefinitionProvider`, `IDependencyDefinitionProvider`),
their data classes, and the Cypher-backed abstract base classes are defined in the
`...graphdb.mapping.spi` module — see the **GraphDB Mapping SPI** spec. This module
contributes only the service interface and its default implementation.

---

## 2. IMappingService

The main service interface.

```kotlin
interface IMappingService {
    fun convert(mappingProvider: MappingProvider, boltClient: IBoltClient): HGModel
}
```

Note the parameter is the concrete `MappingProvider` data class (from the SPI module),
not an interface.

### 2.1 Conversion Procedure

`DefaultMappingService.convert(mappingProvider, boltClient)` performs the following steps
(see `DefaultMappingService.kt`):

1. **Create core graph**
   - Create the `HGGraph` via `HGGraphFactory.createHGGraph()`.
   - Register the bolt client as an extension on the graph:
     `coreGraph.registerExtension(IBoltClient::class.java, boltClient)`.

2. **Create root node**
   - Create `GraphDbRootNodeSource(identifier = -1L)` and set its `boltClient`.
   - Create the root `HGNode` via `HGGraphFactory.createNode(coreGraph) { rootNodeSource }`.

3. **Create hierarchy**
   - Create the `Hierarchy` via `HierarchyFactory.createHierarchy(coreGraph, rootNode)`.
   - Maintain a local `idToNodeMap: MutableMap<Long, HGNode>` (Neo4j id → node), used only
     during construction.

4. **Initialize hierarchy provider**
   - If `hierarchyDefinitionProvider is IBoltClientAware`, assign its `boltClient` property.
   - Call `hierarchyDefinitionProvider.initialize()`.

5. **Build top-level nodes**
   - For each `ToplevelNodeId(id, kind)` in `hierarchyDefinitionProvider.toplevelNodeIds`:
     - Get or create the node via `getOrCreateNode(...)`.
     - Add it under the root **only if** `hierarchy.parentOf(node) == null`, via
       `HierarchyFactory.addChild(hierarchy, rootNode, node)`. This guard means a node is
       never added to the hierarchy twice even if the provider returns the same top-level id
       more than once (e.g. one query row per Main/Test artifact of a module); the repeats
       are skipped.
     - Set its `kind` if it is still `null` (`if (node.kind == null) node.kind = rn.kind`).

6. **Build parent-child relationships**
   - For each `ParentChildNodeId(parentId, childId, childKind)` in
     `hierarchyDefinitionProvider.parentChildNodeIds`:
     - Get or create the parent node and the child node via `getOrCreateNode(...)`.
     - Add the child under the parent **only if** `hierarchy.parentOf(child) == null`, via
       `HierarchyFactory.addChild(hierarchy, parent, child)` — the same single-add guard as
       the top-level step.
     - Set the child's `kind` if it is still `null`.

7. **Initialize dependency provider**
   - If `dependencyDefinitionProvider is IBoltClientAware`, assign its `boltClient` property.
   - Call `dependencyDefinitionProvider.initialize()`.

8. **Build dependencies**
   - For each `DependencyDefinition` in `dependencyDefinitionProvider.dependencies`:
     - Look up `from` (`idStart`) and `to` (`idTarget`) in `idToNodeMap`; if either is
       missing, skip the dependency (`continue`). Endpoints that were never created — e.g.
       nodes the hierarchy never placed — therefore drop their dependencies here.
     - Create the core dependency via
       `HGGraphFactory.createCoreDependency(from, to, depDef.type) { ... }`, where the
       lambda calls `dependencyProvider.createDependencySource(depDef)` and sets the
       returned source's `boltClient` before returning it.
     - Set `weight` and `attributesBitmap` on the resulting dependency.

9. **Register mapping provider as extension**
   - `coreGraph.registerExtension(MappingProvider::class.java, mappingProvider)`.
   - (`IBoltClient` was already registered on the graph in step 1.)

10. **Dispose providers**
    - Call `dispose()` on both the hierarchy and dependency providers.

11. **Return `HGModel(coreGraph, hierarchy)`.**

### 2.2 Node Creation Helper

`DefaultMappingService` factors node creation into a single private helper:

- **`getOrCreateNode(id, coreGraph, boltClient, idToNodeMap, hierarchyProvider)`** — returns
  the existing node for `id` if present in `idToNodeMap`; otherwise creates a node source via
  `hierarchyProvider.createNodeSource(id)`, sets the source's `boltClient` (for lazy property
  loading), creates the node via `HGGraphFactory.createNode(coreGraph) { source }`, and
  records it in `idToNodeMap`. Creating the node and adding it to the hierarchy are separate
  concerns: this helper only creates/looks up the node; placement under a parent is done by
  the caller via `HierarchyFactory.addChild` under the no-double-add guard.

Kind assignment and dependency creation are inlined in `convert(...)` rather than extracted
into helpers; there is no separate `setKindIfNull` or `createDependency` helper, and no
`createOrphanNode` path — a node that no hierarchy entry places simply stays in `idToNodeMap`
without a parent in the hierarchy.

---

## 3. Extensions Registered on the Core Graph

After conversion, the `HGGraph` (`HGModel.coreGraph`) carries these extensions:

| Extension key | Value |
|---------------|-------|
| `IBoltClient` | the bolt client used for the conversion (registered in step 1). |
| `MappingProvider` | the original mapping provider (registered in step 9). |

There is no `INodeMetadataProvider` extension — node metadata is not part of the current
SPI.

---

## 4. Cypher-Based Providers

The abstract base classes `AbstractQueryBasedHierarchyProvider` and
`AbstractQueryBasedDependencyProvider` — which implement the provider interfaces on top of
`IBoltClient` Cypher queries, including the dependency row/attribute-bitmap format — now
live in the SPI module's `...graphdb.mapping.spi.bolt` package. They are documented in the
**GraphDB Mapping SPI** spec (Section 3). The mapping service injects the bolt client into
any provider implementing `IBoltClientAware` (steps 4 and 7 above) before calling
`initialize()`.

---

## 5. Scope Exclusions

- **Proxy dependencies** — `IProxyDependencyDefinition`, `ProxyDependencyDefinitionImpl`,
  `ProxyDependencyQueriesHolder`, `CustomProxyDependencyResolver`, and all related
  `addProxyDependencyDefinitions()` methods are dropped. All dependencies are eagerly resolved.
- **IMappingParticipator** — post-create hooks. Can be added later if needed.
- **MappingFactory** — EMF registration helper. Not needed in Kotlin.
- **MappingException** — use standard Kotlin exceptions (`IllegalStateException`, etc.).
