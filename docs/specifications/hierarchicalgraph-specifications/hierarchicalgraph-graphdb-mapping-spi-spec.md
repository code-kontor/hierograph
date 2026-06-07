# GraphDB Mapping SPI — Kotlin Reimplementation Specification

This document specifies the Service Provider Interfaces (SPI) that define how a graph
database (Neo4j) is mapped into the hierarchical graph model. Implementations of these
interfaces are schema-specific (e.g., jQAssistant) and pluggable.

Package: `io.hierograph.hierarchicalgraph.graphdb.mapping.spi`

Depends on:
- `io.hierograph.hierarchicalgraph.core.model` (Kotlin model)
- `io.hierograph.hierarchicalgraph.graphdb.model` (`GraphDbNodeSource`, `GraphDbDependencySource`)
- `io.hierograph.boltclient` (`IBoltClient`) — only in the `bolt` subpackage

---

## 1. Overview

The mapping SPI defines two provider interfaces, aggregated by a single `MappingProvider`:

1. **IHierarchyDefinitionProvider** — defines the parent-child tree structure.
2. **IDependencyDefinitionProvider** — defines the inter-node dependencies.

Both providers follow a lifecycle: `initialize()` populates their backing data (typically
by running Cypher queries), the data is exposed as read-only properties, and `dispose()`
clears it. They are consumed by the mapping service (separate module) to construct an
`HGModel` — an `HGGraph` plus its `Hierarchy` of `HGNode`s — from a Neo4j database.

The `bolt` subpackage provides ready-made abstract base classes that implement both
providers on top of `IBoltClient` Cypher queries.

> **Note on naming.** The aggregator and metadata are plain Kotlin `data class`es
> (`MappingProvider`, `MappingProviderMetadata`), not interfaces. There is no
> `IMappingProvider` / `IMappingProviderMetadata` interface. Node metadata and search
> (`INodeMetadataProvider`, `ISearchProvider`) are **not** part of this module.

---

## 2. Interfaces

### 2.1 MappingProvider

The root aggregator for the providers. A simple immutable data class.

```kotlin
data class MappingProvider(
    val metadata: MappingProviderMetadata,
    val hierarchyDefinitionProvider: IHierarchyDefinitionProvider,
    val dependencyDefinitionProvider: IDependencyDefinitionProvider
)
```

### 2.2 MappingProviderMetadata

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

### 2.3 IHierarchyDefinitionProvider

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
| `ToplevelNodeId` | A top-level node. `id` is the Neo4j node ID, `kind` is the node kind (e.g. a `JavaNodeKind` enum value, or a kind string). |
| `ParentChildNodeId` | A parent-child pair. `childKind` is the child's kind. |

### 2.4 IDependencyDefinitionProvider

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

### 2.5 DependencyDefinition

A single dependency edge definition. A plain data class (there is no `IDependencyDefinition`
interface).

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

## 3. Bolt Subpackage

Package: `io.hierograph.hierarchicalgraph.graphdb.mapping.spi.bolt`

Provider implementations that need Neo4j access obtain it through `IBoltClientAware`.
The mapping service injects the client by assigning the `boltClient` property before
calling `initialize()`.

### 3.1 IBoltClientAware

```kotlin
interface IBoltClientAware {
    var boltClient: IBoltClient
}
```

The client is supplied via **property injection**, not a method call. A provider that
implements this interface has its `boltClient` set by the mapping service prior to
`initialize()`.

### 3.2 AbstractBoltClientAware

```kotlin
abstract class AbstractBoltClientAware : IBoltClientAware {
    override lateinit var boltClient: IBoltClient
}
```

Convenience base that backs `boltClient` with a `lateinit var`. Accessing it before the
mapping service injects the client throws `UninitializedPropertyAccessException`.

### 3.3 AbstractQueryBasedHierarchyProvider

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

    // Subclass provides Cypher queries
    protected abstract fun toplevelNodeIdQueries(): List<String>
    protected abstract fun parentChildNodeIdsQueries(): List<String>

    // Optional: parse kind string into a domain object (default: return the string as-is)
    protected open fun parseKind(kindString: String): Any = kindString
}
```

**`initialize()` behavior:**

1. Run every query from `toplevelNodeIdQueries()` (results are flat-mapped together).
   - Each row must return ≥ 2 columns: column 0 `id: Long`, column 1 `kindString: String`.
   - Transform to `ToplevelNodeId(id, kind = parseKind(kindString))`.
   - **De-duplicate by id** via `.distinctBy { it.id }`. Because the top-level set is built
     from multiple queries (and a single query may also surface the same node more than once),
     this guarantees a node is never returned as a top-level entry twice. The first occurrence
     of each id wins.
2. Run every query from `parentChildNodeIdsQueries()` (results are flat-mapped together).
   - Each row must return ≥ 3 columns: column 0 `parentId: Long`, column 1 `childId: Long`,
     column 2 `kindString: String`.
   - Transform to `ParentChildNodeId(parentId, childId, childKind = parseKind(kindString))`.

Queries are executed with `boltClient.asyncExecCypherQueryAndTransformResult(...).get()`
(synchronously awaited). `dispose()` resets both lists to empty.

### 3.4 AbstractQueryBasedDependencyProvider

Base class for dependency providers that derive their data from Cypher queries.

```kotlin
abstract class AbstractQueryBasedDependencyProvider :
    IDependencyDefinitionProvider, AbstractBoltClientAware() {

    final override var dependencies: List<DependencyDefinition> = emptyList()
        private set

    override fun initialize()
    override fun dispose()

    // Register a simple (eagerly resolved) dependency query — typically called from the
    // subclass constructor / setup
    protected fun addSimpleDependencyDefinitions(query: String)

    companion object {
        fun resolveDependencyQuery(boltClient: IBoltClient, query: String): List<DependencyDefinition>
    }
}
```

**`initialize()` behavior:**

1. For each registered simple dependency query, run it via `boltClient` and flat-map the
   rows into `dependencies`.
2. `dispose()` resets `dependencies` to empty.

Subclasses register their queries by calling `addSimpleDependencyDefinitions(query)` (for
example during construction); `initialize()` resolves whatever has been registered.

**Row format (per `resolveDependencyQuery`):** each row must return ≥ 5 columns.

| Column | Meaning |
|--------|---------|
| 0 | `idStart` (Long) — source node Neo4j ID |
| 1 | `idTarget` (Long) — target node Neo4j ID |
| 2 | `idRel` (Long) — relationship Neo4j ID |
| 3 | `type` (String) — relationship type |
| 4 | `weight` (Int) — dependency weight |
| 5+ | boolean attribute flags — each `true` value sets bit `(index − 5)` in `attributesBitmap` |

**Attribute bitmap construction.** For columns at index 5, 6, 7, …, if the value is `true`,
set bit `(index − 5)` in `attributesBitmap`. This encodes up to 32 boolean attributes in a
single int. Missing/non-boolean values are treated as `false`.

**Example:**

```cypher
MATCH (t1:Type)-[r:DEPENDS_ON]->(t2:Type)
RETURN id(t1), id(t2), id(r), type(r), r.weight,
       r.extends, r.implements, r.annotatedBy, r.dependsOnOnly
```

If a row returns `[10, 20, 30, "DEPENDS_ON", 1, true, false, true, false]`:
- `attributesBitmap = 0b0101 = 5` (bits 0 and 2 set).

---

## 4. Scope Exclusions

The following are **not** part of this module:

- **INodeMetadataProvider / ISearchProvider** — node name/kind metadata, Cypher query
  templates, and node search live elsewhere, not in this SPI.
- **IProxyDependencyDefinition** — proxy dependencies are removed from the Kotlin model.
  All dependencies are eagerly resolved.
- **ILabelDefinitionProvider / Label DSL** — the label/image rendering system is UI-specific
  and not part of the model SPI.
- **INodeSorter** (the SPI node-comparator, not the algorithms `INodeSorter`) — not used.
- **@SlizaaMappingProvider annotation** — discovery mechanism, can be added later if needed.
