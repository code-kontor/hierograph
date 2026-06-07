# HierarchicalGraph GraphDB Specification

This document specifies the graph-database extension layer of the hierarchical graph: the
Neo4j-backed node and dependency sources, the mapping SPI that describes how a graph
database is projected into the model, and the mapping service that builds an `HGModel` from
a database via a `MappingProvider`. All database access goes through a bolt connection
(`io.hierograph.boltclient.IBoltClient`), and the underlying store is a jQAssistant / Neo4j
graph.

The layer is split across three modules:

| Module | Package | Contents |
|--------|---------|----------|
| graphdb model | `io.hierograph.hierarchicalgraph.graphdb.model` | `GraphDbNodeSource`, `GraphDbRootNodeSource`, `GraphDbDependencySource` — lazy property/label loading. |
| mapping SPI | `io.hierograph.hierarchicalgraph.graphdb.mapping.spi` (+ `.bolt`) | Provider interfaces, data classes, `MappingProvider`, and Cypher-backed abstract base classes. |
| mapping service | `io.hierograph.hierarchicalgraph.graphdb.mapping.service` | `IMappingService` and `DefaultMappingService.convert`. |

Depends on:
- `io.hierograph.hierarchicalgraph.core.model` (Kotlin model: `HGNode`, `HGGraph`,
  `HGCoreDependency`, `HGModel`, `Hierarchy`, `HGGraphFactory`, `HierarchyFactory`,
  `INodeSource`, `IDependencySource`)
- `io.hierograph.boltclient` (`IBoltClient`)
- Neo4j Java driver (`org.neo4j.driver`)

---

## 1. Overview

When the hierarchical graph is populated from a Neo4j database, each node and dependency
carries its Neo4j properties and labels/type. These are **lazily loaded** — fetched on
first access rather than at graph construction time — so property data is not pulled for
thousands of nodes when only a few are inspected.

The graphdb model defines three source types that implement the core model's `INodeSource`
and `IDependencySource`. The mapping SPI defines two provider interfaces — one for the
parent-child tree, one for the dependencies — aggregated by a single `MappingProvider`
data class, plus Cypher-backed abstract base classes in the `.bolt` subpackage. The mapping
service drives the conversion: it initializes the providers, materializes the nodes,
hierarchy, and dependency edges, and returns an `HGModel` (an `HGGraph` plus its
`Hierarchy` of `HGNode`s).

---

## 2. GraphDB Model

Each source holds its own `IBoltClient` directly and uses it for lazy loading; lazy loading
does not walk the graph to find a client. The client is assigned to each source after
construction by the mapping service.

**Invariant:** the `boltClient` must be set on a source before any lazy loading occurs on
it. If it is `null` when lazy loading is triggered, `checkNotNull` throws an
`IllegalStateException` naming the offending identifier.

### 2.1 GraphDbNodeSource

An `open` class implementing `INodeSource`. Represents a Neo4j node.

```kotlin
open class GraphDbNodeSource(
    override val identifier: Any
) : INodeSource {
    override var node: HGNode? = null
    var boltClient: IBoltClient? = null
    val properties: Map<String, String>   // lazily loaded
    val labels: List<String>              // lazily loaded
}
```

| Member | Type | Description |
|--------|------|-------------|
| `identifier` | `Any` | The Neo4j node ID (a `Long`). Constructor parameter; overrides `INodeSource.identifier`. |
| `node` | `HGNode?` | Back-reference. Overrides `INodeSource.node`. Defaults to `null`. |
| `boltClient` | `IBoltClient?` | Bolt client used for lazy loading. Set after construction. Defaults to `null`. |
| `properties` | `Map<String, String>` | Neo4j node properties. Read-only, lazily loaded on first access. |
| `labels` | `List<String>` | Neo4j node labels. Read-only, lazily loaded on first access. |

The backing fields `_properties` and `_labels` are private and nullable; `null` is the
"not yet loaded" sentinel.

**Lazy loading.** When `properties` or `labels` is read while its backing field is `null`,
`loadNodeData()` runs and populates *both* fields:

1. `checkNotNull(boltClient)` — fails with
   `"No bolt client set on GraphDbNodeSource for node $identifier."` if unset.
2. `boltClient.getNode(identifier as Long)` fetches the Neo4j `Node`.
3. `labels` is populated from `node.labels().toList()`.
4. `properties` is populated from `node.asMap()`, converting every value to a string via
   `toString()`.

### 2.2 GraphDbRootNodeSource

```kotlin
class GraphDbRootNodeSource(identifier: Any) : GraphDbNodeSource(identifier = identifier)
```

A subclass of `GraphDbNodeSource` that forwards `identifier` to the superclass constructor
and adds nothing else. It inherits the full lazy-loading machinery, `boltClient`,
`properties`, and `labels`.

There is no separate `HGRootNode` type and no special "empty properties / empty labels"
behaviour: the root is a plain `HGNode` whose source is a `GraphDbRootNodeSource`. The
subclass exists only so the root node's source can be distinguished by type.

### 2.3 GraphDbDependencySource

Implements `IDependencySource`. Represents a Neo4j relationship.

```kotlin
class GraphDbDependencySource(
    override val identifier: Any,
    val type: String
) : IDependencySource {
    override var dependency: HGCoreDependency? = null
    var boltClient: IBoltClient? = null
    var userObject: Any? = null
    val properties: Map<String, String>   // lazily loaded

    fun <T : Any> getUserObject(clazz: Class<T>): T?
}
```

| Member | Type | Description |
|--------|------|-------------|
| `identifier` | `Any` | The Neo4j relationship ID (a `Long`). Constructor parameter; overrides `IDependencySource.identifier`. |
| `type` | `String` | The Neo4j relationship type. Constructor parameter; set at construction (not lazy). |
| `dependency` | `HGCoreDependency?` | Back-reference. Overrides `IDependencySource.dependency`. Defaults to `null`. |
| `boltClient` | `IBoltClient?` | Bolt client used for lazy loading. Set after construction. Defaults to `null`. |
| `userObject` | `Any?` | Optional application-defined object attached to this dependency. Defaults to `null`. |
| `properties` | `Map<String, String>` | Neo4j relationship properties. Read-only, lazily loaded on first access. |

**Lazy loading.** When `properties` is read while its backing field `_properties` is
`null`, `loadRelationshipData()` runs:

1. `checkNotNull(boltClient)` — fails with
   `"No bolt client set on GraphDbDependencySource for dependency $identifier."` if unset.
2. `boltClient.getRelationship(identifier as Long)` fetches the Neo4j `Relationship`.
3. `properties` is populated from `relationship.asMap()`, converting every value to a
   string via `toString()`.

**Operations.**

| Operation | Return | Description |
|-----------|--------|-------------|
| `getUserObject<T : Any>(clazz: Class<T>)` | `T?` | Returns `userObject` cast to `T` if it is an instance of `clazz`; otherwise `null` (also `null` when `userObject` is `null`). |

---

## 3. Mapping SPI

Package: `io.hierograph.hierarchicalgraph.graphdb.mapping.spi`

The mapping SPI defines two provider interfaces, aggregated by a single `MappingProvider`:

1. **IHierarchyDefinitionProvider** — defines the parent-child tree structure.
2. **IDependencyDefinitionProvider** — defines the inter-node dependencies.

Both providers follow a lifecycle: `initialize()` populates their backing data (typically
by running Cypher queries), the data is exposed as read-only properties, and `dispose()`
clears it. The `.bolt` subpackage supplies abstract base classes that implement both
providers on top of `IBoltClient` Cypher queries.

The aggregator and metadata are plain Kotlin `data class`es (`MappingProvider`,
`MappingProviderMetadata`), not interfaces. Implementations of the provider interfaces are
schema-specific (for example, jQAssistant) and pluggable.

### 3.1 MappingProvider

The root aggregator. An immutable data class.

```kotlin
data class MappingProvider(
    val metadata: MappingProviderMetadata,
    val hierarchyDefinitionProvider: IHierarchyDefinitionProvider,
    val dependencyDefinitionProvider: IDependencyDefinitionProvider
)
```

### 3.2 MappingProviderMetadata

Descriptive metadata for a mapping provider.

```kotlin
data class MappingProviderMetadata(
    val identifier: String,
    val name: String,
    val description: String? = null,
    val categories: Map<String, String> = emptyMap()
)
```

| Property | Description |
|----------|-------------|
| `identifier` | Unique ID (e.g. `"io.hierograph.jqassistant.hierarchicalgraph"`). |
| `name` | Human-readable name (e.g. `"Hierograph jQAssistant"`). |
| `description` | Optional longer description. Default `null`. |
| `categories` | Arbitrary key-value metadata. Default empty. |

### 3.3 IHierarchyDefinitionProvider

Defines the parent-child relationships that form the node tree.

```kotlin
interface IHierarchyDefinitionProvider {
    fun initialize()
    fun dispose()
    val toplevelNodeIds: List<ToplevelNodeId>
    val parentChildNodeIds: List<ParentChildNodeId>

    fun createNodeSource(id: Long): GraphDbNodeSource =
        GraphDbNodeSource(identifier = id)
}
```

| Member | Description |
|--------|-------------|
| `initialize()` | Populate `toplevelNodeIds` and `parentChildNodeIds`. Called by the mapping service before the lists are read. |
| `dispose()` | Release the backing data (typically resets both lists to empty). |
| `toplevelNodeIds` | Read-only list of top-level nodes. Valid after `initialize()`. |
| `parentChildNodeIds` | Read-only list of parent-child pairs. Valid after `initialize()`. |
| `createNodeSource(id)` | Factory for the `GraphDbNodeSource` attached to each created node. Default returns `GraphDbNodeSource(identifier = id)`; override to customize. |

**Data classes:**

```kotlin
data class ToplevelNodeId(val id: Long, val kind: Any)
data class ParentChildNodeId(val parentId: Long, val childId: Long, val childKind: Any)
```

| Type | Description |
|------|-------------|
| `ToplevelNodeId` | A top-level node. `id` is the Neo4j node ID; `kind` is the node kind (e.g. a `JavaNodeKind` enum value, or a kind string). |
| `ParentChildNodeId` | A parent-child pair. `childKind` is the child's kind. |

### 3.4 IDependencyDefinitionProvider

Provides all inter-node dependency definitions.

```kotlin
interface IDependencyDefinitionProvider {
    fun initialize()
    fun dispose()
    val dependencies: List<DependencyDefinition>

    fun createDependencySource(depDef: DependencyDefinition): GraphDbDependencySource =
        GraphDbDependencySource(identifier = depDef.idRel, type = depDef.type)
}
```

| Member | Description |
|--------|-------------|
| `initialize()` | Populate `dependencies`. Called by the mapping service before the list is read. |
| `dispose()` | Release the backing data (typically resets the list to empty). |
| `dependencies` | Read-only list of dependency definitions. Valid after `initialize()`. |
| `createDependencySource(depDef)` | Factory for the `GraphDbDependencySource` attached to each created dependency. Default returns `GraphDbDependencySource(identifier = depDef.idRel, type = depDef.type)`; override to customize. |

### 3.5 DependencyDefinition

A single dependency edge definition. A plain data class.

```kotlin
data class DependencyDefinition(
    val idStart: Long,
    val idTarget: Long,
    val idRel: Long,
    val type: String,
    val weight: Int = 1,
    val attributesBitmap: Int = 0
)
```

| Property | Description |
|----------|-------------|
| `idStart` | Source node Neo4j ID. |
| `idTarget` | Target node Neo4j ID. |
| `idRel` | Relationship Neo4j ID. |
| `type` | Relationship type string (e.g. `"DEPENDS_ON"`). |
| `weight` | Dependency weight. Default 1. |
| `attributesBitmap` | Bit flags for domain-specific attributes (e.g. EXTENDS, IMPLEMENTS). Default 0. |

---

## 4. Bolt Subpackage

Package: `io.hierograph.hierarchicalgraph.graphdb.mapping.spi.bolt`

Provider implementations that need Neo4j access obtain it through `IBoltClientAware`. The
mapping service injects the client by assigning the `boltClient` property before calling
`initialize()`.

### 4.1 IBoltClientAware

```kotlin
interface IBoltClientAware {
    var boltClient: IBoltClient
}
```

The client is supplied via **property injection**, not a method call. A provider that
implements this interface has its `boltClient` set by the mapping service prior to
`initialize()`.

### 4.2 AbstractBoltClientAware

```kotlin
abstract class AbstractBoltClientAware : IBoltClientAware {
    override lateinit var boltClient: IBoltClient
}
```

Convenience base that backs `boltClient` with a `lateinit var`. Accessing it before the
mapping service injects the client throws `UninitializedPropertyAccessException`.

### 4.3 AbstractQueryBasedHierarchyProvider

Base class for hierarchy providers that derive their data from Cypher queries.

```kotlin
abstract class AbstractQueryBasedHierarchyProvider :
    IHierarchyDefinitionProvider, AbstractBoltClientAware() {

    final override var toplevelNodeIds: List<ToplevelNodeId> = emptyList()
        private set
    final override var parentChildNodeIds: List<ParentChildNodeId> = emptyList()
        private set

    override fun initialize()
    override fun dispose()

    // Subclass supplies the Cypher queries
    protected abstract fun toplevelNodeIdQueries(): List<String>
    protected abstract fun parentChildNodeIdsQueries(): List<String>

    // Optional: parse a kind string into a domain object (default: return the string as-is)
    protected open fun parseKind(kindString: String): Any = kindString
}
```

**`initialize()` behavior.**

1. Run every query from `toplevelNodeIdQueries()` (results are flat-mapped together).
   - Each row must return ≥ 2 columns: column 0 `id: Long`, column 1 `kindString: String`.
   - Transform to `ToplevelNodeId(id, kind = parseKind(kindString))`.
   - De-duplicate by id via `.distinctBy { it.id }`. The top-level set is built from
     multiple queries (and a single query may also surface the same node more than once);
     `distinctBy` guarantees a node never appears as a top-level entry twice. The first
     occurrence of each id wins.
2. Run every query from `parentChildNodeIdsQueries()` (results are flat-mapped together).
   - Each row must return ≥ 3 columns: column 0 `parentId: Long`, column 1 `childId: Long`,
     column 2 `kindString: String`.
   - Transform to `ParentChildNodeId(parentId, childId, childKind = parseKind(kindString))`.

Queries run via `boltClient.asyncExecCypherQueryAndTransformResult(...).get()`
(synchronously awaited). `dispose()` resets both lists to empty.

### 4.4 AbstractQueryBasedDependencyProvider

Base class for dependency providers that derive their data from Cypher queries.

```kotlin
abstract class AbstractQueryBasedDependencyProvider :
    IDependencyDefinitionProvider, AbstractBoltClientAware() {

    final override var dependencies: List<DependencyDefinition> = emptyList()
        private set

    override fun initialize()
    override fun dispose()

    // Register an (eagerly resolved) dependency query — typically called from the
    // subclass constructor / setup
    protected fun addSimpleDependencyDefinitions(query: String)

    companion object {
        fun resolveDependencyQuery(
            boltClient: IBoltClient,
            query: String
        ): List<DependencyDefinition>
    }
}
```

**`initialize()` behavior.** For each registered simple dependency query, run it via
`boltClient` and flat-map the rows into `dependencies`. `dispose()` resets `dependencies`
to empty. Subclasses register queries by calling `addSimpleDependencyDefinitions(query)`
(for example during construction); `initialize()` resolves whatever has been registered.

**Row format (per `resolveDependencyQuery`).** Each row must return ≥ 5 columns.

| Column | Meaning |
|--------|---------|
| 0 | `idStart` (Long) — source node Neo4j ID |
| 1 | `idTarget` (Long) — target node Neo4j ID |
| 2 | `idRel` (Long) — relationship Neo4j ID |
| 3 | `type` (String) — relationship type |
| 4 | `weight` (Int) — dependency weight |
| 5+ | boolean attribute flags — each `true` value sets bit `(index − 5)` in `attributesBitmap` |

**Attribute bitmap construction.** For columns at index 5, 6, 7, …, if the value is `true`,
bit `(index − 5)` is set in `attributesBitmap`. This encodes up to 32 boolean attributes in
a single int. Missing/non-boolean values are treated as `false`.

**Example:**

```cypher
MATCH (t1:Type)-[r:DEPENDS_ON]->(t2:Type)
RETURN id(t1), id(t2), id(r), type(r), r.weight,
       r.extends, r.implements, r.annotatedBy, r.dependsOnOnly
```

For a row `[10, 20, 30, "DEPENDS_ON", 1, true, false, true, false]`:
- `attributesBitmap = 0b0101 = 5` (bits 0 and 2 set).

---

## 5. Mapping Service

Package: `io.hierograph.hierarchicalgraph.graphdb.mapping.service`

The mapping service orchestrates building a hierarchical graph from Neo4j: it creates the
core graph and root node, initializes the hierarchy provider and builds the tree,
initializes the dependency provider and builds the edges, registers extensions on the core
graph, disposes the providers, and returns the `HGModel`.

### 5.1 IMappingService

```kotlin
interface IMappingService {
    fun convert(mappingProvider: MappingProvider, boltClient: IBoltClient): HGModel
}
```

The parameter is the concrete `MappingProvider` data class from the SPI, not an interface.

### 5.2 Conversion Procedure

`DefaultMappingService.convert(mappingProvider, boltClient)` performs:

1. **Create core graph.**
   - Create the `HGGraph` via `HGGraphFactory.createHGGraph()`.
   - Register the bolt client as an extension:
     `coreGraph.registerExtension(IBoltClient::class.java, boltClient)`.

2. **Create root node.**
   - Create `GraphDbRootNodeSource(identifier = -1L)` and set its `boltClient`.
   - Create the root `HGNode` via `HGGraphFactory.createNode(coreGraph) { rootNodeSource }`.

3. **Create hierarchy.**
   - Create the `Hierarchy` via `HierarchyFactory.createHierarchy(coreGraph, rootNode)`.
   - Maintain a local `idToNodeMap: MutableMap<Long, HGNode>` (Neo4j id → node), used only
     during construction.

4. **Initialize hierarchy provider.**
   - If `hierarchyDefinitionProvider is IBoltClientAware`, assign its `boltClient`.
   - Call `hierarchyDefinitionProvider.initialize()`.

5. **Build top-level nodes.** For each `ToplevelNodeId(id, kind)` in
   `hierarchyDefinitionProvider.toplevelNodeIds`:
   - Get or create the node via `getOrCreateNode(...)`.
   - Add it under the root **only if** `hierarchy.parentOf(node) == null`, via
     `HierarchyFactory.addChild(hierarchy, rootNode, node)`.
   - Set its `kind` if still `null` (`if (node.kind == null) node.kind = rn.kind`).

6. **Build parent-child relationships.** For each
   `ParentChildNodeId(parentId, childId, childKind)` in
   `hierarchyDefinitionProvider.parentChildNodeIds`:
   - Get or create the parent and the child via `getOrCreateNode(...)`.
   - Add the child under the parent **only if** `hierarchy.parentOf(child) == null`, via
     `HierarchyFactory.addChild(hierarchy, parent, child)`.
   - Set the child's `kind` if still `null`.

   The guards in steps 5 and 6 enforce the invariant that **a node is added to the
   hierarchy at most once**. If the same id is presented again (e.g. one query row per
   Main/Test artifact of a module), the node already has a parent and the repeat is skipped.

7. **Initialize dependency provider.**
   - If `dependencyDefinitionProvider is IBoltClientAware`, assign its `boltClient`.
   - Call `dependencyDefinitionProvider.initialize()`.

8. **Build dependencies.** For each `DependencyDefinition` in
   `dependencyDefinitionProvider.dependencies`:
   - Look up `from` (`idStart`) and `to` (`idTarget`) in `idToNodeMap`; if either is
     missing, skip the dependency (`continue`). Endpoints that were never created — e.g.
     nodes the hierarchy never placed — therefore drop their dependencies here.
   - Create the core dependency via
     `HGGraphFactory.createCoreDependency(from, to, depDef.type) { ... }`, where the lambda
     calls `dependencyProvider.createDependencySource(depDef)` and sets the returned
     source's `boltClient` before returning it.
   - Set `weight` and `attributesBitmap` on the resulting dependency.

9. **Register mapping provider as extension.**
   - `coreGraph.registerExtension(MappingProvider::class.java, mappingProvider)`.
   - (`IBoltClient` was registered in step 1.)

10. **Dispose providers.** Call `dispose()` on both the hierarchy and dependency providers.

11. **Return `HGModel(coreGraph, hierarchy)`.**

### 5.3 Node Creation Helper

`DefaultMappingService` factors node creation into a single private helper:

```kotlin
private fun getOrCreateNode(
    id: Long,
    coreGraph: HGGraphImpl,
    boltClient: IBoltClient,
    idToNodeMap: MutableMap<Long, HGNode>,
    hierarchyProvider: IHierarchyDefinitionProvider,
): HGNode
```

It returns the existing node for `id` if present in `idToNodeMap`; otherwise it creates a
node source via `hierarchyProvider.createNodeSource(id)`, sets the source's `boltClient`
(for lazy property loading), creates the node via
`HGGraphFactory.createNode(coreGraph) { source }`, and records it in `idToNodeMap`.

Creating a node and placing it in the hierarchy are separate concerns: this helper only
creates or looks up the node; placement under a parent is done by the caller via
`HierarchyFactory.addChild` under the at-most-once guard. Kind assignment and dependency
creation are inlined in `convert(...)` rather than extracted into helpers. A node that no
hierarchy entry places simply stays in `idToNodeMap` without a parent in the hierarchy.

### 5.4 Extensions Registered on the Core Graph

After conversion, the `HGGraph` (`HGModel.coreGraph`) carries these extensions:

| Extension key | Value |
|---------------|-------|
| `IBoltClient` | the bolt client used for the conversion (registered in step 1). |
| `MappingProvider` | the original mapping provider (registered in step 9). |

Node metadata is not part of this SPI, so there is no node-metadata extension.

---

## 6. Scope

The following are not part of this layer:

- **Node metadata and search providers** — node name/kind metadata, Cypher query
  templates, and node search live elsewhere, not in this SPI.
- **Proxy dependencies** — all dependencies are eagerly resolved; there is no proxy
  dependency type or resolver.
- **Label / image rendering** — the label/image system is UI-specific and not part of the
  model SPI.
- **Mapping participators / post-create hooks** — not present.
- **Debug/utility classes** (graph traversal, graph dumping) — not part of this spec.

Errors are signalled with standard Kotlin exceptions (`IllegalStateException`,
`UninitializedPropertyAccessException`, etc.).
