# GraphDB Mapping Service — Kotlin Reimplementation Specification

This document specifies the combined mapping service that builds an `HGRootNode` from
a Neo4j database using an `IMappingProvider`. It merges functionality from the former
`org.slizaa.hierarchicalgraph.graphdb.mapping.cypher` and
`org.slizaa.hierarchicalgraph.graphdb.mapping.service` modules into a single module.

Depends on:
- `io.hierograph.hierarchicalgraph.core.model`
- `io.hierograph.hierarchicalgraph.graphdb.model`
- `io.hierograph.hierarchicalgraph.graphdb.mapping.spi`
- `org.slizaa.core.boltclient` (`IBoltClient`)

---

## 1. Overview

The mapping service orchestrates the construction of a hierarchical graph from Neo4j:

1. Execute Cypher queries to determine hierarchy (root nodes + parent-child relationships).
2. Execute Cypher queries to determine dependencies.
3. Build the `HGRootNode` with all nodes, dependencies, and registered extensions.

The module provides:
- **`IMappingService`** — the service interface with a single `convert` method.
- **Abstract Cypher-based providers** — base classes for implementing hierarchy and
  dependency providers using Cypher queries.
- **Query utilities** — for executing Cypher and transforming results into dependency definitions.

---

## 2. IMappingService

The main service interface.

```kotlin
interface IMappingService {
    fun convert(mappingProvider: IMappingProvider, boltClient: IBoltClient): HGRootNode
}
```

### 2.1 Conversion Procedure

When `convert` is called:

1. **Create root node**
   - Create `HGRootNode` with a `GraphDbRootNodeSource(identifier = -1)`.
   - Set `rootNodeSource.boltClient = boltClient`.

2. **Initialize hierarchy provider**
   - If the provider implements `IBoltClientAware`, call `initialize(boltClient)`.

3. **Build root-level nodes**
   - Call `hierarchyProvider.getToplevelNodeIds()`.
   - For each `RootNode(id, kind)`: create an `HGNode` with a `GraphDbNodeSource(identifier = id)`,
     set its `kind`, parent it to the root.

4. **Build hierarchy**
   - Call `hierarchyProvider.getParentChildNodeIds()`.
   - For each `ParentChildNode(parentId, childId, childKind)`:
     - Look up or create the parent node.
     - Look up or create the child node, set its parent and kind.

5. **Remove dangling nodes**
   - Remove any node that has no parent and is not the root (orphaned by missing hierarchy entries).

6. **Initialize dependency provider**
   - If the provider implements `IBoltClientAware`, call `initialize(boltClient)`.

7. **Build dependencies**
   - Call `dependencyProvider.getDependencies()`.
   - For each `IDependencyDefinition`:
     - Look up `from` and `to` nodes by their IDs.
     - If either node is missing, skip (dependency is orphaned).
     - Create `HGCoreDependency` with `GraphDbDependencySource(identifier = idRel, type = type)`.
     - Set `weight` and `attributesBitmap` on the dependency.

8. **Register extensions on root**
   - `IMappingProvider` → the original mapping provider.
   - `INodeMetadataProvider` → from the mapping provider.

9. **Return the `HGRootNode`.**

---

## 3. IBoltClientAware

Interface for providers that need Neo4j initialization.

```kotlin
interface IBoltClientAware {
    fun initialize(boltClient: IBoltClient)
}
```

If a hierarchy or dependency provider implements this interface, the mapping service
calls `initialize(boltClient)` before calling `getToplevelNodeIds()` / `getDependencies()`.

---

## 4. AbstractQueryBasedHierarchyProvider

Base class for hierarchy providers that use Cypher queries.

```kotlin
abstract class AbstractQueryBasedHierarchyProvider :
    IHierarchyDefinitionProvider, IBoltClientAware {

    // Subclass provides Cypher queries
    protected abstract fun toplevelNodeIdQueries(): Array<String>
    protected abstract fun parentChildNodeIdsQueries(): Array<String>

    // Optional: parse kind string into a domain object (default: return as-is)
    protected open fun parseKind(kindString: String): Any = kindString

    // IBoltClientAware
    override fun initialize(boltClient: IBoltClient)

    // IHierarchyDefinitionProvider
    override fun getToplevelNodeIds(): List<RootNode>
    override fun getParentChildNodeIds(): List<ParentChildNode>
}
```

### 4.1 Initialization

When `initialize(boltClient)` is called:

1. For each query in `toplevelNodeIdQueries()`:
   - Execute via `boltClient`.
   - Each row must return 2 columns: `nodeId: Long`, `kindString: String`.
   - Transform to `RootNode(id = nodeId, kind = parseKind(kindString))`.
   - Collect all results.

2. For each query in `parentChildNodeIdsQueries()`:
   - Execute via `boltClient`.
   - Each row must return 3 columns: `parentId: Long`, `childId: Long`, `kindString: String`.
   - Transform to `ParentChildNode(parentId, childId, childKind = parseKind(kindString))`.
   - Collect all results.

Results are cached. Subsequent calls to `getToplevelNodeIds()` / `getParentChildNodeIds()`
return the cached lists.

---

## 5. AbstractQueryBasedDependencyProvider

Base class for dependency providers that use Cypher queries.

```kotlin
abstract class AbstractQueryBasedDependencyProvider :
    IDependencyDefinitionProvider, IBoltClientAware {

    // Subclass registers queries during initialize()
    protected abstract fun initialize()

    // Register a simple (eagerly resolved) dependency query
    protected fun addSimpleDependencyDefinitions(query: String)

    // IBoltClientAware
    override fun initialize(boltClient: IBoltClient)

    // IDependencyDefinitionProvider
    override fun getDependencies(): List<IDependencyDefinition>
}
```

### 5.1 Initialization

When `initialize(boltClient)` is called:

1. Call the abstract `initialize()` method — subclass registers queries via
   `addSimpleDependencyDefinitions(query)`.
2. For each registered simple dependency query:
   - Execute via `boltClient`.
   - Each row must return 5+ columns:
     - Column 0: `idStart` (Long) — source node Neo4j ID
     - Column 1: `idTarget` (Long) — target node Neo4j ID
     - Column 2: `idRel` (Long) — relationship Neo4j ID
     - Column 3: `type` (String) — relationship type
     - Column 4: `weight` (Int) — dependency weight
     - Columns 5+: boolean attribute flags (each `true` sets bit at position `i - 5` in `attributesBitmap`)
   - Transform each row to `DefaultDependencyDefinition`.
3. Cache results.

### 5.2 Cypher Query Format

**Required columns (minimum 5):**
```
RETURN id(source), id(target), id(rel), type(rel), weight [, attr0, attr1, ...]
```

**Attribute bitmap construction:**
For columns at index 5, 6, 7, ...: if the value is `true`, set bit `(index - 5)` in the
`attributesBitmap`. This allows encoding up to 32 boolean attributes in a single int.

**Example:**
```cypher
MATCH (t1:Type)-[r:DEPENDS_ON]->(t2:Type)
RETURN id(t1), id(t2), id(r), type(r), r.weight,
       r.extends, r.implements, r.annotatedBy, r.dependsOnOnly
```
If row returns `[10, 20, 30, "DEPENDS_ON", 1, true, false, true, false]`:
- `attributesBitmap = 0b0101 = 5` (bits 0 and 2 set)

---

## 6. Scope Exclusions

- **Proxy dependencies** — `IProxyDependencyDefinition`, `ProxyDependencyDefinitionImpl`,
  `ProxyDependencyQueriesHolder`, `CustomProxyDependencyResolver`, and all related
  `addProxyDependencyDefinitions()` methods are dropped. All dependencies are eagerly resolved.
- **IMappingParticipator** — post-create hooks. Can be added later if needed.
- **MappingFactory** — EMF registration helper. Not needed in Kotlin.
- **MappingException** — use standard Kotlin exceptions (`IllegalStateException`, etc.).
