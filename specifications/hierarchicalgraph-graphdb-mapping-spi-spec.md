# GraphDB Mapping SPI — Kotlin Reimplementation Specification

This document specifies the Service Provider Interfaces (SPI) that define how a graph
database (Neo4j) is mapped into the hierarchical graph model. Implementations of these
interfaces are schema-specific (e.g., jQAssistant) and pluggable.

Depends on:
- `io.hierograph.hierarchicalgraph.core.model` (Kotlin model)

---

## 1. Overview

The mapping SPI defines four provider interfaces, aggregated by a single `IMappingProvider`:

1. **IHierarchyDefinitionProvider** — defines the parent-child tree structure
2. **IDependencyDefinitionProvider** — defines the inter-node dependencies
3. **INodeMetadataProvider** — provides names, kinds, and Cypher query templates for nodes
4. **ISearchProvider** — provides node search by name

These providers are used by a mapping service (separate module) to construct an
`HGRootNode` from a Neo4j database.

---

## 2. Interfaces

### 2.1 IMappingProvider

The root aggregator for all mapping providers.

```kotlin
interface IMappingProvider {
    val metadata: IMappingProviderMetadata
    val hierarchyDefinitionProvider: IHierarchyDefinitionProvider
    val dependencyDefinitionProvider: IDependencyDefinitionProvider
    val nodeMetadataProvider: INodeMetadataProvider
}
```

### 2.2 IMappingProviderMetadata

Descriptive metadata for a mapping provider.

```kotlin
interface IMappingProviderMetadata {
    val identifier: String
    val name: String
    val description: String?
    val categories: Map<String, String>
}
```

| Property | Description |
|----------|-------------|
| `identifier` | Unique ID (e.g. `"io.hierograph.jqassistant.hierarchicalgraph"`). |
| `name` | Human-readable name (e.g. `"Hierograph jQAssistant"`). |
| `description` | Optional longer description. |
| `categories` | Arbitrary key-value metadata. |

### 2.3 IHierarchyDefinitionProvider

Defines the parent-child relationships that form the node tree.

```kotlin
interface IHierarchyDefinitionProvider {
    fun getToplevelNodeIds(): List<RootNode>
    fun getParentChildNodeIds(): List<ParentChildNode>
}
```

**Data classes:**

```kotlin
data class RootNode(val id: Long, val kind: Any)
data class ParentChildNode(val parentId: Long, val childId: Long, val childKind: Any)
```

| Type | Description |
|------|-------------|
| `RootNode` | A top-level node. `id` is the Neo4j node ID, `kind` is the node kind (e.g. a `JavaNodeKind` enum value). |
| `ParentChildNode` | A parent-child pair. `childKind` is the child's kind. |

### 2.4 IDependencyDefinitionProvider

Provides all inter-node dependency definitions.

```kotlin
interface IDependencyDefinitionProvider {
    fun getDependencies(): List<IDependencyDefinition>
}
```

### 2.5 IDependencyDefinition

A single dependency edge definition.

```kotlin
interface IDependencyDefinition {
    val idStart: Long
    val idTarget: Long
    val idRel: Long
    val type: String
    val weight: Int          // default: 1
    val attributesBitmap: Int // default: 0
}
```

| Property | Description |
|----------|-------------|
| `idStart` | Source node Neo4j ID. |
| `idTarget` | Target node Neo4j ID. |
| `idRel` | Relationship Neo4j ID. |
| `type` | Relationship type string (e.g. `"DEPENDS_ON"`). |
| `weight` | Dependency weight. Default 1. |
| `attributesBitmap` | Bit flags for domain-specific attributes (e.g. EXTENDS, IMPLEMENTS). Default 0. |

**Default implementation:**

```kotlin
data class DefaultDependencyDefinition(
    override val idStart: Long,
    override val idTarget: Long,
    override val idRel: Long,
    override val type: String,
    override val weight: Int = 1,
    override val attributesBitmap: Int = 0
) : IDependencyDefinition
```

### 2.6 INodeMetadataProvider

Provides structured metadata for nodes. Schema-specific (e.g., jQAssistant).
Registered as an extension on the root node.

```kotlin
interface INodeMetadataProvider {
    // Per-node metadata
    fun getName(node: HGNode): String
    fun getQualifiedName(node: HGNode): String
    fun getKind(node: HGNode): String
    fun getKindFromLabels(labels: List<String>): String
    fun getKnownKinds(): List<String>

    // Cypher query delegation
    fun getFindNodeCypherQuery(kind: String?, limit: Int): String
    fun getNodeCountCypherQuery(scopeId: Long?): String
    fun getDepthStatsCypherQuery(scopeId: Long?): String
    fun getDependencyKindDistributionCypherQuery(scopeId: Long?): String
    fun getScanMetadataCypherQuery(): String
    fun getScannerName(): String
}
```

| Method | Description |
|--------|-------------|
| `getName(node)` | Display name (e.g. `"ClusterService"`). |
| `getQualifiedName(node)` | Fully qualified name (e.g. `"org.example.ClusterService"`). |
| `getKind(node)` | Primary kind (e.g. `"Class"`, `"Package"`). |
| `getKindFromLabels(labels)` | Determines kind from raw Neo4j labels. Fallback for nodes not in the HG model. |
| `getKnownKinds()` | Valid kind values for filtering. |
| `getFindNodeCypherQuery(kind, limit)` | Cypher query for search. Must use `$query` parameter. Returns columns: `nodeId`, `name`, `fqn`, `labels`. |
| `getNodeCountCypherQuery(scopeId)` | Returns columns: `label`, `cnt`. |
| `getDepthStatsCypherQuery(scopeId)` | Returns columns: `maxDepth`, `avgDepth`. |
| `getDependencyKindDistributionCypherQuery(scopeId)` | Returns columns: `kind`, `cnt`. |
| `getScanMetadataCypherQuery()` | Returns column: `scannedAt`. |
| `getScannerName()` | Scanner identifier (e.g. `"jqassistant"`). |

### 2.7 ISearchProvider

Scanner-agnostic search abstraction for finding nodes by name.

```kotlin
interface ISearchProvider {
    fun search(name: String, kindFilter: List<String>?, limit: Int): List<SearchResult>
}
```

**Data class:**

```kotlin
data class SearchResult(
    val nodeId: Long,
    val name: String,
    val qualifiedName: String,
    val kind: String
)
```

| Property | Description |
|----------|-------------|
| `nodeId` | Neo4j node ID. |
| `name` | Simple (unqualified) name. |
| `qualifiedName` | Fully qualified name. |
| `kind` | Hierograph kind (e.g. `"java.class"`) — not raw scanner labels. |

**Search behavior:**
- Case-insensitive substring match on name and qualified name.
- `kindFilter` may contain Hierograph kinds or group aliases (`"types"`, `"members"`, `"packages"`).
- Results ordered by match quality: exact name > prefix > substring, shorter qualified names first within same tier.

---

## 3. Default Implementations

### 3.1 DefaultMappingProvider

Simple aggregator that holds the four providers.

```kotlin
class DefaultMappingProvider(
    override val metadata: IMappingProviderMetadata,
    override val hierarchyDefinitionProvider: IHierarchyDefinitionProvider,
    override val dependencyDefinitionProvider: IDependencyDefinitionProvider,
    override val nodeMetadataProvider: INodeMetadataProvider
) : IMappingProvider
```

### 3.2 DefaultMappingProviderMetadata

```kotlin
data class DefaultMappingProviderMetadata(
    override val identifier: String,
    override val name: String,
    override val description: String? = null,
    override val categories: Map<String, String> = emptyMap()
) : IMappingProviderMetadata
```

### 3.3 DefaultDependencyDefinition

See Section 2.5 above.

---

## 4. Scope Exclusions

The following are **not** part of this module's spec:

- **IProxyDependencyDefinition** — proxy dependencies are removed from the Kotlin model. All dependencies are eagerly resolved.
- **ILabelDefinitionProvider / Label DSL** — the label/image rendering system (`LabelMappingDsl`, `AbstractLabelDefinitionProvider`, `ILabelDefinitionProcessor`, etc.) is UI-specific and not needed for the Kotlin reimplementation.
- **INodeSorter** (from SPI, not to be confused with the algorithms `INodeSorter`) — node comparator interface, not used.
- **@SlizaaMappingProvider annotation** — discovery mechanism, can be added later if needed.
- **IBoltClientAware** — the `initialize(IBoltClient)` pattern from the cypher module is a separate concern (mapping.cypher module).
