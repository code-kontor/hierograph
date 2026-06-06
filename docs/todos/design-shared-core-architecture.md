# Design: Shared-Core + Hierarchy Architecture

**Status:** Design draft (no code changes yet)
**Goal:** Separate node identity from containment so multiple hierarchies (base + scenarios)
can share the same core graph.

---

## Overview

```
                    CoreGraph (shared, immutable after construction)
                    +------------------------------------------+
                    |  nodes: Map<Long, CoreNode>              |
                    |  extensions: Map<String, Any>             |
                    |                                          |
                    |  CoreNode                                |
                    |    identifier: Long                      |
                    |    nodeSource: INodeSource               |
                    |    kind: Any?                            |
                    |    outgoing: List<CoreDependency>        |
                    |    incoming: List<CoreDependency>        |
                    |                                          |
                    |  CoreDependency                          |
                    |    from: CoreNode                        |
                    |    to: CoreNode                          |
                    |    type, weight, attributesBitmap        |
                    +------------------------------------------+
                           ^                     ^
                           |                     |
                   +-------+------+      +-------+------+
                   |  Hierarchy   |      |  Hierarchy   |
                   |   (base)     |      |  (scenario)  |
                   |              |      |              |
                   | parentMap    |      | parentMap    |  <- copied or overlay
                   | childrenMap  |      | childrenMap  |
                   | caches       |      | caches       |  <- independent
                   +--------------+      +--------------+

                   HGModel = CoreGraph + Hierarchy
```

---

## Part 1: Interfaces

### 1.1 CoreNode

Shared node identity. No parent, no children, no aggregation. One instance per node in the
entire system, shared across all hierarchies.

```kotlin
interface CoreNode {
    val identifier: Any
    val nodeSource: INodeSource
    var kind: Any?

    val outgoingCoreDependencies: List<CoreDependency>
    val incomingCoreDependencies: List<CoreDependency>

    fun <T : Any> getNodeSource(clazz: Class<T>): T?
}
```

### 1.2 CoreDependency

Replaces `HGCoreDependency`. References `CoreNode` (shared), not hierarchy-specific nodes.

```kotlin
interface CoreDependency {
    val from: CoreNode
    val to: CoreNode
    val type: String
    var weight: Int
    var attributesBitmap: Int
    val dependencySource: IDependencySource

    fun <T : Any> getDependencySource(clazz: Class<T>): T?
}
```

### 1.3 CoreGraph

Shared container for all nodes and core dependencies. Owns the node lookup map and the
extension registry (both are identity-level concerns, not hierarchy-level).

```kotlin
interface CoreGraph {
    val nodes: Collection<CoreNode>

    fun lookupNode(identifier: Any): CoreNode?

    // Extension registry (moved from HGRootNode — shared resources like IBoltClient)
    fun <T : Any> registerExtension(clazz: Class<T>, extension: T)
    fun registerExtension(key: String, extension: Any)
    fun <T : Any> getExtension(clazz: Class<T>): T?
    fun <T : Any> getExtension(key: String, clazz: Class<T>): T?
    fun <T : Any> hasExtension(clazz: Class<T>): Boolean
}
```

### 1.4 AggregatedDependency

Unchanged in shape. References `CoreNode` instead of `HGNode`. Produced by the Hierarchy,
not stored on the node.

```kotlin
interface AggregatedDependency {
    val from: CoreNode
    val to: CoreNode
    val coreDependencies: List<CoreDependency>
    val aggregatedWeight: Int
}
```

### 1.5 Hierarchy

Owns the parent/child structure, aggregation caches, and all hierarchy-dependent operations.
One instance per view (base, scenario, filtered config).

```kotlin
interface Hierarchy {
    val coreGraph: CoreGraph
    val rootNode: CoreNode
    var name: String?

    // ---- structure ----

    fun parentOf(node: CoreNode): CoreNode?
    fun childrenOf(node: CoreNode): List<CoreNode>
    fun predecessorsOf(node: CoreNode): List<CoreNode>
    fun isPredecessorOf(ancestor: CoreNode, descendant: CoreNode): Boolean
    fun isSuccessorOf(descendant: CoreNode, ancestor: CoreNode): Boolean

    // ---- accumulated dependencies (hierarchy-dependent) ----

    fun accumulatedOutgoing(node: CoreNode): List<CoreDependency>
    fun accumulatedIncoming(node: CoreNode): List<CoreDependency>

    // ---- aggregated dependencies (hierarchy-dependent) ----

    fun getAggregatedDependency(from: CoreNode, to: CoreNode): AggregatedDependency?
    fun getAggregatedDependencies(from: CoreNode, targets: List<CoreNode>): List<AggregatedDependency>
    fun getAggregatedDependenciesFrom(to: CoreNode, sources: List<CoreNode>): List<AggregatedDependency>

    // ---- traversal ----

    fun traverse(node: CoreNode, action: (CoreNode) -> Unit)
    fun traverse(node: CoreNode, action: (CoreNode) -> Unit, filter: (CoreNode) -> Boolean)

    // ---- local nodes (scenario-only) ----

    val localNodes: Collection<CoreNode>
    fun createLocalNode(kind: Any?, nodeSourceSupplier: () -> INodeSource): CoreNode
    fun lookupNode(identifier: Any): CoreNode?   // local nodes first, then coreGraph

    // ---- mutation (for scenarios) ----

    fun addChild(parent: CoreNode, child: CoreNode)
    fun move(node: CoreNode, newParent: CoreNode)
    fun fork(): Hierarchy
}
```

### 1.6 HGModel

Consumer-facing entry point. Combines `CoreGraph` + `Hierarchy`. This is what tools and
controllers receive.

```kotlin
class HGModel(
    val coreGraph: CoreGraph,
    val hierarchy: Hierarchy,
) {
    // Delegates to hierarchy, which checks local nodes first, then coreGraph
    fun lookupNode(identifier: Any): CoreNode? = hierarchy.lookupNode(identifier)

    fun fork(): HGModel = HGModel(coreGraph, hierarchy.fork())

    fun scoped(block: HierarchyScope.() -> Unit) = HierarchyScope(hierarchy).block()

    fun <R> withScope(block: HierarchyScope.() -> R): R = HierarchyScope(hierarchy).block()
}
```

---

## Part 2: Scoped extensions (ergonomic API)

The `HierarchyScope` provides extension properties and functions on `CoreNode` that delegate
to the hierarchy. Inside a scope, code reads almost identically to the current API.

```kotlin
open class HierarchyScope(val hierarchy: Hierarchy) {

    val coreGraph: CoreGraph get() = hierarchy.coreGraph

    // ---- structure ----

    val CoreNode.parent: CoreNode?
        get() = hierarchy.parentOf(this)

    val CoreNode.children: List<CoreNode>
        get() = hierarchy.childrenOf(this)

    val CoreNode.predecessors: List<CoreNode>
        get() = hierarchy.predecessorsOf(this)

    val CoreNode.hasChildren: Boolean
        get() = hierarchy.childrenOf(this).isNotEmpty()

    fun CoreNode.isPredecessorOf(other: CoreNode): Boolean =
        hierarchy.isPredecessorOf(this, other)

    fun CoreNode.isSuccessorOf(other: CoreNode): Boolean =
        hierarchy.isSuccessorOf(this, other)

    // ---- accumulated dependencies ----

    val CoreNode.accumulatedOutgoingCoreDependencies: List<CoreDependency>
        get() = hierarchy.accumulatedOutgoing(this)

    val CoreNode.accumulatedIncomingCoreDependencies: List<CoreDependency>
        get() = hierarchy.accumulatedIncoming(this)

    // ---- aggregated dependencies ----

    fun CoreNode.outgoingTo(target: CoreNode): AggregatedDependency? =
        hierarchy.getAggregatedDependency(this, target)

    fun CoreNode.outgoingTo(targets: List<CoreNode>): List<AggregatedDependency> =
        hierarchy.getAggregatedDependencies(this, targets)

    fun CoreNode.incomingFrom(source: CoreNode): AggregatedDependency? =
        hierarchy.getAggregatedDependency(source, this)

    fun CoreNode.incomingFrom(sources: List<CoreNode>): List<AggregatedDependency> =
        hierarchy.getAggregatedDependenciesFrom(this, sources)

    // ---- traversal ----

    fun CoreNode.traverse(action: (CoreNode) -> Unit) =
        hierarchy.traverse(this, action)
}
```

**Usage in a tool (before and after):**

```kotlin
// ═══════ TODAY ═══════
class ListChildrenTool(private val graphService: HierarchicalGraphService) {
    fun execute(nodeId: Long): List<Map<String, Any?>> {
        val node = graphService.rootNode.lookupNode(nodeId) ?: return emptyList()
        return node.children.map { child ->
            mapOf(
                "id" to child.identifier,
                "name" to getName(child),
                "kind" to child.kind,
                "parent_id" to child.parent?.identifier,
                "has_children" to child.children.isNotEmpty(),
            )
        }
    }
}

// ═══════ AFTER ═══════
class ListChildrenTool(private val model: HGModel) {
    fun execute(nodeId: Long): List<Map<String, Any?>> = model.withScope {
        val node = model.lookupNode(nodeId) ?: return@withScope emptyList()
        node.children.map { child ->
            mapOf(
                "id" to child.identifier,
                "name" to getName(child),
                "kind" to child.kind,
                "parent_id" to child.parent?.identifier,
                "has_children" to child.hasChildren,
            )
        }
    }
}
```

The diff is: `graphService.rootNode.lookupNode` -> `model.lookupNode`, method body wrapped
in `model.withScope { }`. Inside the scope, `node.children`, `node.parent`, etc. resolve via
extensions to the hierarchy.

---

## Part 3: Implementations

### 3.1 CoreNodeImpl

```kotlin
class CoreNodeImpl(
    override val nodeSource: INodeSource,
) : CoreNode {
    override var kind: Any? = null

    override val identifier: Any get() = nodeSource.identifier

    internal val _outgoing: MutableList<CoreDependency> = mutableListOf()
    internal val _incoming: MutableList<CoreDependency> = mutableListOf()

    override val outgoingCoreDependencies: List<CoreDependency> get() = _outgoing
    override val incomingCoreDependencies: List<CoreDependency> get() = _incoming

    override fun <T : Any> getNodeSource(clazz: Class<T>): T? =
        if (clazz.isInstance(nodeSource)) clazz.cast(nodeSource) else null
}
```

### 3.2 CoreDependencyImpl

```kotlin
class CoreDependencyImpl(
    override val from: CoreNode,
    override val to: CoreNode,
    override val type: String,
    override val dependencySource: IDependencySource,
) : CoreDependency {
    override var weight: Int = 1
    override var attributesBitmap: Int = 0

    override fun <T : Any> getDependencySource(clazz: Class<T>): T? =
        if (clazz.isInstance(dependencySource)) clazz.cast(dependencySource) else null
}
```

### 3.3 CoreGraphImpl

```kotlin
class CoreGraphImpl : CoreGraph {
    private val nodeMap: MutableMap<Any, CoreNode> = mutableMapOf()
    private val extensionRegistry: MutableMap<String, Any> = mutableMapOf()

    override val nodes: Collection<CoreNode> get() = nodeMap.values

    override fun lookupNode(identifier: Any): CoreNode? = nodeMap[identifier]

    // ---- construction (internal) ----

    internal fun registerNode(node: CoreNode) {
        nodeMap[node.identifier] = node
    }

    // ---- extensions ----

    override fun <T : Any> registerExtension(clazz: Class<T>, extension: T) {
        extensionRegistry[clazz.name] = extension
    }

    override fun registerExtension(key: String, extension: Any) {
        extensionRegistry[key] = extension
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getExtension(clazz: Class<T>): T? =
        extensionRegistry[clazz.name] as? T

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getExtension(key: String, clazz: Class<T>): T? {
        val value = extensionRegistry[key] ?: return null
        check(clazz.isAssignableFrom(value.javaClass))
        return value as T
    }

    override fun <T : Any> hasExtension(clazz: Class<T>): Boolean =
        extensionRegistry.containsKey(clazz.name)
}
```

### 3.4 HierarchyImpl

```kotlin
class HierarchyImpl(
    override val coreGraph: CoreGraph,
    override val rootNode: CoreNode,
    private val parentMap: MutableMap<Any, Any>,           // childId -> parentId
    private val childrenMap: MutableMap<Any, MutableList<Any>>,  // parentId -> [childIds]
) : Hierarchy {

    override var name: String? = null

    // ---- local nodes (scenario-only, not in shared CoreGraph) ----

    private val localNodeMap: MutableMap<Any, CoreNode> = mutableMapOf()

    override val localNodes: Collection<CoreNode> get() = localNodeMap.values

    override fun createLocalNode(kind: Any?, nodeSourceSupplier: () -> INodeSource): CoreNode {
        val source = nodeSourceSupplier()
        val node = CoreNodeImpl(nodeSource = source)
        node.kind = kind
        source.node = node
        localNodeMap[node.identifier] = node
        return node
    }

    override fun lookupNode(identifier: Any): CoreNode? =
        localNodeMap[identifier] ?: coreGraph.lookupNode(identifier)

    // ---- caches (cleared on structural mutation) ----

    private var predecessorCache: MutableMap<Any, List<CoreNode>>? = null
    private var accOutCache: MutableMap<Any, List<CoreDependency>>? = null
    private var accInCache: MutableMap<Any, List<CoreDependency>>? = null
    private var aggDepCache: MutableMap<Pair<Any, Any>, AggregatedDependency?>? = null

    // ---- structure ----

    override fun parentOf(node: CoreNode): CoreNode? {
        val parentId = parentMap[node.identifier] ?: return null
        return coreGraph.lookupNode(parentId)
    }

    override fun childrenOf(node: CoreNode): List<CoreNode> {
        val childIds = childrenMap[node.identifier] ?: return emptyList()
        return childIds.mapNotNull { coreGraph.lookupNode(it) }
    }

    override fun predecessorsOf(node: CoreNode): List<CoreNode> {
        val cache = predecessorCache ?: mutableMapOf<Any, List<CoreNode>>().also { predecessorCache = it }
        return cache.getOrPut(node.identifier) {
            val parent = parentOf(node) ?: return@getOrPut emptyList()
            buildList {
                add(parent)
                addAll(predecessorsOf(parent))
            }
        }
    }

    override fun isPredecessorOf(ancestor: CoreNode, descendant: CoreNode): Boolean =
        predecessorsOf(descendant).contains(ancestor)

    override fun isSuccessorOf(descendant: CoreNode, ancestor: CoreNode): Boolean =
        isPredecessorOf(ancestor, descendant)

    // ---- accumulated dependencies ----

    override fun accumulatedOutgoing(node: CoreNode): List<CoreDependency> {
        val cache = accOutCache ?: mutableMapOf<Any, List<CoreDependency>>().also { accOutCache = it }
        return cache.getOrPut(node.identifier) {
            buildList {
                addAll(node.outgoingCoreDependencies)
                for (child in childrenOf(node)) {
                    addAll(accumulatedOutgoing(child))
                }
            }
        }
    }

    override fun accumulatedIncoming(node: CoreNode): List<CoreDependency> {
        val cache = accInCache ?: mutableMapOf<Any, List<CoreDependency>>().also { accInCache = it }
        return cache.getOrPut(node.identifier) {
            buildList {
                addAll(node.incomingCoreDependencies)
                for (child in childrenOf(node)) {
                    addAll(accumulatedIncoming(child))
                }
            }
        }
    }

    // ---- aggregated dependencies ----

    override fun getAggregatedDependency(from: CoreNode, to: CoreNode): AggregatedDependency? {
        val cache = aggDepCache ?: mutableMapOf<Pair<Any,Any>, AggregatedDependency?>().also { aggDepCache = it }
        val key = from.identifier to to.identifier
        return cache.getOrPut(key) {
            val coreDeps = accumulatedIncoming(to).filter { dep ->
                dep.from === from || isPredecessorOf(from, dep.from)
            }
            if (coreDeps.isEmpty()) null
            else AggregatedDependencyImpl(from, to, coreDeps)
        }
    }

    override fun getAggregatedDependencies(
        from: CoreNode,
        targets: List<CoreNode>,
    ): List<AggregatedDependency> = targets.mapNotNull { getAggregatedDependency(from, it) }

    override fun getAggregatedDependenciesFrom(
        to: CoreNode,
        sources: List<CoreNode>,
    ): List<AggregatedDependency> = sources.mapNotNull { getAggregatedDependency(it, to) }

    // ---- traversal ----

    override fun traverse(node: CoreNode, action: (CoreNode) -> Unit) {
        for (child in childrenOf(node)) {
            action(child)
            traverse(child, action)
        }
    }

    override fun traverse(node: CoreNode, action: (CoreNode) -> Unit, filter: (CoreNode) -> Boolean) {
        for (child in childrenOf(node)) {
            if (filter(child)) {
                action(child)
                traverse(child, action, filter)
            }
        }
    }

    // ---- mutation ----

    override fun addChild(parent: CoreNode, child: CoreNode) {
        parentMap[child.identifier] = parent.identifier
        childrenMap.getOrPut(parent.identifier) { mutableListOf() }.add(child.identifier)
        clearCaches()
    }

    override fun move(node: CoreNode, newParent: CoreNode) {
        val id = node.identifier
        val oldParentId = parentMap[id]
        if (oldParentId != null) {
            childrenMap[oldParentId]?.remove(id)
        }
        parentMap[id] = newParent.identifier
        childrenMap.getOrPut(newParent.identifier) { mutableListOf() }.add(id)
        clearCaches()
    }

    override fun fork(): Hierarchy {
        val forked = HierarchyImpl(
            coreGraph = coreGraph,
            rootNode = rootNode,
            parentMap = HashMap(parentMap),
            childrenMap = HashMap(childrenMap.mapValues { ArrayList(it.value) }),
        )
        forked.name = name
        // Local nodes are shared by reference — they're immutable identity objects.
        // A forked hierarchy sees the same local nodes as its parent.
        // New local nodes created after fork are only visible in the hierarchy that created them.
        forked.localNodeMap.putAll(localNodeMap)
        return forked
    }

    private fun clearCaches() {
        predecessorCache = null
        accOutCache = null
        accInCache = null
        aggDepCache = null
    }
}
```

### 3.5 AggregatedDependencyImpl

Simple data holder. No lazy computation needed — the Hierarchy computes and caches.

```kotlin
class AggregatedDependencyImpl(
    override val from: CoreNode,
    override val to: CoreNode,
    override val coreDependencies: List<CoreDependency>,
) : AggregatedDependency {
    override val aggregatedWeight: Int = coreDependencies.sumOf { it.weight }
}
```

---

## Part 4: Factory

Replaces `HierarchicalGraphFactory`. Separates core node creation from hierarchy placement.

```kotlin
object CoreGraphFactory {

    fun createCoreGraph(): CoreGraphImpl = CoreGraphImpl()

    fun createNode(
        graph: CoreGraphImpl,
        nodeSourceSupplier: () -> INodeSource,
    ): CoreNode {
        val source = nodeSourceSupplier()
        val node = CoreNodeImpl(nodeSource = source)
        source.node = node
        graph.registerNode(node)
        return node
    }

    fun createCoreDependency(
        source: CoreNode,
        target: CoreNode,
        type: String,
        depSourceSupplier: () -> IDependencySource,
    ): CoreDependency {
        val depSource = depSourceSupplier()
        val dep = CoreDependencyImpl(from = source, to = target, type = type, dependencySource = depSource)
        depSource.dependency = dep
        (source as CoreNodeImpl)._outgoing.add(dep)
        (target as CoreNodeImpl)._incoming.add(dep)
        return dep
    }
}

object HierarchyFactory {

    fun createHierarchy(coreGraph: CoreGraph, rootNode: CoreNode): HierarchyImpl {
        return HierarchyImpl(
            coreGraph = coreGraph,
            rootNode = rootNode,
            parentMap = mutableMapOf(),
            childrenMap = mutableMapOf(),
        )
    }

    fun addChild(hierarchy: Hierarchy, parent: CoreNode, child: CoreNode) {
        val impl = hierarchy as HierarchyImpl
        impl.parentMap[child.identifier] = parent.identifier
        impl.childrenMap
            .getOrPut(parent.identifier) { mutableListOf() }
            .add(child.identifier)
    }
}
```

---

## Part 5: DefaultMappingService changes

```kotlin
class DefaultMappingService : IMappingService {

    // Return type changes from HGRootNode to HGModel
    fun convert(mappingProvider: MappingProvider, boltClient: IBoltClient): HGModel {

        val hierarchyProvider = mappingProvider.hierarchyDefinitionProvider
        val dependencyProvider = mappingProvider.dependencyDefinitionProvider

        // 1. Create core graph
        val coreGraph = CoreGraphFactory.createCoreGraph()
        coreGraph.registerExtension(IBoltClient::class.java, boltClient)

        // 2. Create root node
        val rootNodeSource = GraphDbRootNodeSource(identifier = -1L)
        rootNodeSource.boltClient = boltClient
        val rootNode = CoreGraphFactory.createNode(coreGraph) { rootNodeSource }

        // 3. Create hierarchy
        val hierarchy = HierarchyFactory.createHierarchy(coreGraph, rootNode)

        // 4. Node lookup map (local, for construction only)
        val idToNodeMap = mutableMapOf<Long, CoreNode>()

        // 5. Initialize + build hierarchy from provider
        if (hierarchyProvider is IBoltClientAware) {
            hierarchyProvider.boltClient = boltClient
        }
        hierarchyProvider.initialize()

        // 5a. Top-level nodes
        for (rn in hierarchyProvider.toplevelNodeIds) {
            val node = getOrCreateNode(rn.id, coreGraph, idToNodeMap, hierarchyProvider)
            HierarchyFactory.addChild(hierarchy, rootNode, node)
            if (node.kind == null) node.kind = rn.kind
        }

        // 5b. Parent-child relationships
        for (pcn in hierarchyProvider.parentChildNodeIds) {
            val parent = getOrCreateNode(pcn.parentId, coreGraph, idToNodeMap, hierarchyProvider)
            val child = getOrCreateNode(pcn.childId, coreGraph, idToNodeMap, hierarchyProvider)
            HierarchyFactory.addChild(hierarchy, parent, child)
            if (child.kind == null) child.kind = pcn.childKind
        }

        // 5c. Remove dangling nodes (nodes without a parent in the hierarchy)
        //     These are in coreGraph but not placed in any hierarchy — leave them in
        //     coreGraph (they're valid nodes) but they won't appear in traversals.

        // 6. Build dependencies
        if (dependencyProvider is IBoltClientAware) {
            dependencyProvider.boltClient = boltClient
        }
        dependencyProvider.initialize()

        for (depDef in dependencyProvider.dependencies) {
            val from = idToNodeMap[depDef.idStart] ?: continue
            val to = idToNodeMap[depDef.idTarget] ?: continue
            val dep = CoreGraphFactory.createCoreDependency(from, to, depDef.type) {
                dependencyProvider.createDependencySource(depDef)
            }
            dep.weight = depDef.weight
            dep.attributesBitmap = depDef.attributesBitmap
        }

        // 7. Register mapping provider as extension
        coreGraph.registerExtension(MappingProvider::class.java, mappingProvider)

        // 8. Cleanup
        hierarchyProvider.dispose()
        dependencyProvider.dispose()

        return HGModel(coreGraph, hierarchy)
    }

    private fun getOrCreateNode(
        id: Long,
        coreGraph: CoreGraphImpl,
        idToNodeMap: MutableMap<Long, CoreNode>,
        hierarchyProvider: IHierarchyDefinitionProvider,
    ): CoreNode {
        return idToNodeMap.getOrPut(id) {
            val source = hierarchyProvider.createNodeSource(id)
            CoreGraphFactory.createNode(coreGraph) { source }
        }
    }
}
```

---

## Part 6: GraphDbNodeSource changes

Currently `GraphDbNodeSource.getBoltClient()` navigates `node.rootNode.nodeSource` to find
the bolt client. With the split, `CoreNode` has no `rootNode`. Instead, store the bolt client
reference directly.

```kotlin
open class GraphDbNodeSource(
    override val identifier: Any,
) : INodeSource {

    override var node: CoreNode? = null    // changed from HGNode to CoreNode

    // NEW: direct reference, set during mapping
    var boltClient: IBoltClient? = null

    private var _properties: Map<String, String>? = null
    private var _labels: List<String>? = null

    val properties: Map<String, String>
        get() {
            if (_properties == null) loadNodeData()
            return _properties!!
        }

    val labels: List<String>
        get() {
            if (_labels == null) loadNodeData()
            return _labels!!
        }

    private fun loadNodeData() {
        // BEFORE: val boltClient = node!!.rootNode.nodeSource...boltClient
        // AFTER:  direct reference
        val client = checkNotNull(boltClient) { "No bolt client set." }
        val neo4jNode = client.getNode(identifier as Long)
        _labels = neo4jNode.labels().toList()
        _properties = neo4jNode.asMap().entries.associate { (k, v) -> k to v.toString() }
    }
}
```

The mapping service sets `boltClient` on each `GraphDbNodeSource` during `createNodeSource()`,
or alternatively the hierarchy provider sets it in bulk after initialization.

---

## Part 7: Algorithms changes

Algorithms need a `Hierarchy` parameter since aggregation is hierarchy-dependent.

### 7.1 GraphUtils

```kotlin
object GraphUtils {

    fun detectStronglyConnectedComponents(
        nodes: Collection<CoreNode>,
        hierarchy: Hierarchy,
    ): List<List<CoreNode>> = Tarjan().detectStronglyConnectedComponents(nodes, hierarchy)

    fun createDependencyStructureMatrix(
        nodes: Collection<CoreNode>,
        hierarchy: Hierarchy,
    ): IDependencyStructureMatrix = DependencyStructureMatrixImpl(nodes, hierarchy)

    fun computeAdjacencyMatrix(
        nodes: List<CoreNode>,
        hierarchy: Hierarchy,
    ): Array<IntArray> {
        val n = nodes.size
        return Array(n) { i ->
            IntArray(n) { j ->
                hierarchy.getAggregatedDependency(nodes[i], nodes[j])?.aggregatedWeight ?: 0
            }
        }
    }

    fun computeAdjacencyList(
        nodes: Collection<CoreNode>,
        hierarchy: Hierarchy,
    ): Array<IntArray> {
        val nodeList = nodes.toList()
        val indexMap = nodeList.withIndex().associate { (i, node) -> node to i }
        return Array(nodeList.size) { i ->
            val deps = hierarchy.getAggregatedDependencies(nodeList[i], nodeList)
            IntArray(deps.size) { j -> indexMap[deps[j].to]!! }
        }
    }

    fun createFasNodeSorter(): INodeSorter = FastFasSorter()
}
```

### 7.2 IDependencyStructureMatrix

```kotlin
interface IDependencyStructureMatrix {
    val orderedNodes: List<CoreNode>       // was List<HGNode>
    val upwardDependencies: List<AggregatedDependency>
    val cycles: List<List<CoreNode>>

    fun isCellInCycle(i: Int, j: Int): Boolean
    fun isRowInCycle(i: Int): Boolean
    fun getWeight(i: Int, j: Int): Int
    fun getMatrix(): Array<IntArray>
}
```

### 7.3 INodeSorter

```kotlin
interface INodeSorter {
    fun sort(nodes: List<CoreNode>, hierarchy: Hierarchy): SortResult
}

interface SortResult {
    val orderedNodes: List<CoreNode>
    val upwardDependencies: List<AggregatedDependency>
}
```

---

## Part 8: INodeSource changes

`INodeSource.node` changes from `HGNode?` to `CoreNode?`:

```kotlin
interface INodeSource {
    val identifier: Any
    var node: CoreNode?     // was HGNode?
}
```

`IDependencySource.dependency` changes from `HGCoreDependency?` to `CoreDependency?`:

```kotlin
interface IDependencySource {
    val identifier: Any
    var dependency: CoreDependency?     // was HGCoreDependency?
}
```

---

## Part 9: What gets deleted

| Old type | Replacement |
|---|---|
| `HGNode` | `CoreNode` |
| `HGRootNode` | `CoreGraph` (lookup, extensions) + `Hierarchy` (root, structure) |
| `HGCoreDependency` | `CoreDependency` |
| `HGAggregatedDependency` | `AggregatedDependency` |
| `HGNodeImpl` | `CoreNodeImpl` |
| `HGRootNodeImpl` | `CoreGraphImpl` + `HierarchyImpl` |
| `HGCoreDependencyImpl` | `CoreDependencyImpl` |
| `HGAggregatedDependencyImpl` | `AggregatedDependencyImpl` |
| `HierarchicalGraphFactory` | `CoreGraphFactory` + `HierarchyFactory` |
| `HGNodeTraverser` | `Hierarchy.traverse()` |
| `HGCacheInvalidator` | `HierarchyImpl.clearCaches()` (internal) |

---

## Part 10: Consumer migration pattern

Every consumer follows the same pattern:

### 10.1 Tool injection

```kotlin
// BEFORE
class SomeTool(private val graphService: HierarchicalGraphService)

// AFTER
class SomeTool(private val model: HGModel)
```

### 10.2 Method body

```kotlin
// BEFORE
fun execute(nodeId: Long) {
    val node = graphService.rootNode.lookupNode(nodeId) ?: return
    val children = node.children
    val parent = node.parent
    val deps = node.getOutgoingDependenciesTo(targets)
    // ...
}

// AFTER
fun execute(nodeId: Long) = model.withScope {
    val node = model.lookupNode(nodeId) ?: return@withScope
    val children = node.children                    // extension property
    val parent = node.parent                        // extension property
    val deps = node.outgoingTo(targets)             // extension function (renamed)
    // ...
}
```

### 10.3 Core dependency access (no scope needed)

Accessing identity fields on dependency endpoints does NOT require a scope, because
`CoreNode` has `identifier`, `kind`, `nodeSource` directly:

```kotlin
for (dep in node.outgoingCoreDependencies) {    // direct on CoreNode, no scope needed
    val targetId = dep.to.identifier            // direct on CoreNode
    val targetKind = dep.to.kind                // direct on CoreNode
}
```

Only hierarchy navigation (parent, children, predecessors, aggregation) requires the scope.

### 10.4 Algorithm calls

```kotlin
// BEFORE
val dsm = GraphUtils.createDependencyStructureMatrix(nodes)

// AFTER
val dsm = GraphUtils.createDependencyStructureMatrix(nodes, model.hierarchy)
```

---

## Part 11: Virtual refactoring scenarios

With the architecture in place, virtual refactoring is straightforward.

### Moving types into an existing package

```kotlin
val scenario = model.fork()

scenario.hierarchy.move(typeA, existingPackage)
scenario.hierarchy.move(typeB, existingPackage)

scenario.withScope {
    val deps = existingPackage.outgoingTo(otherPackage)
    println("Weight: ${deps?.aggregatedWeight}")
}
```

### Extracting types into a new module/package

The new module and package don't exist in the scanned codebase — they're local to the
scenario. `Hierarchy.createLocalNode()` creates them:

```kotlin
val scenario = model.fork()

// Create nodes that only exist in this scenario
val newModule = scenario.hierarchy.createLocalNode(
    kind = "java.module",
    nodeSourceSupplier = { SyntheticNodeSource(identifier = "new-module", name = "new-module") }
)
val newPackage = scenario.hierarchy.createLocalNode(
    kind = "java.package",
    nodeSourceSupplier = { SyntheticNodeSource(identifier = "new-pkg", name = "com.example.extracted") }
)

// Place them in the hierarchy
scenario.hierarchy.addChild(scenario.hierarchy.rootNode, newModule)
scenario.hierarchy.addChild(newModule, newPackage)

// Move types into the new package
scenario.hierarchy.move(typeA, newPackage)
scenario.hierarchy.move(typeB, newPackage)
scenario.hierarchy.move(typeC, newPackage)

// Query — aggregations are computed from the moved types' existing core deps
scenario.withScope {
    // The new package's aggregated deps are derived from typeA/B/C's core deps
    val outgoing = newPackage.outgoingTo(oldPackage)
    println("New package -> old package: ${outgoing?.aggregatedWeight}")

    // DSM including the new module
    val allModules = scenario.hierarchy.childrenOf(scenario.hierarchy.rootNode)
    val dsm = GraphUtils.createDependencyStructureMatrix(allModules, scenario.hierarchy)
}

// Compare base vs scenario
val baseDsm = GraphUtils.createDependencyStructureMatrix(modules, model.hierarchy)
val scenarioDsm = GraphUtils.createDependencyStructureMatrix(
    scenario.hierarchy.childrenOf(scenario.hierarchy.rootNode),
    scenario.hierarchy,
)
```

### How local nodes work

Local nodes are `CoreNode` instances stored on the `Hierarchy`, not on the shared `CoreGraph`.

- **Visibility:** A local node is only visible in the hierarchy that created it (and its forks).
  Other hierarchies sharing the same `CoreGraph` don't see it.
- **Lookup:** `Hierarchy.lookupNode()` checks local nodes first, then falls back to `CoreGraph`.
  `HGModel.lookupNode()` delegates to this.
- **Identity:** Local nodes use synthetic identifiers (e.g., strings like `"new-module"`) that
  don't collide with Neo4j Long IDs from the shared graph.
- **Dependencies:** Local nodes have no core dependencies of their own — they're pure
  containers. Their aggregated dependencies are computed entirely from their children's core
  deps via the hierarchy. This means **no new core deps need to be created** for extraction
  scenarios.
- **Forking:** When a hierarchy is forked, its local nodes are shared by reference with the
  fork (they're immutable identity objects). Local nodes created after the fork are only visible
  in the hierarchy that created them.
- **No INodeSource from Neo4j:** Local nodes use a `SyntheticNodeSource` that provides a name
  and identifier but has no Neo4j backing and no lazy-loaded properties.

```kotlin
class SyntheticNodeSource(
    override val identifier: Any,
    val name: String,
    val qualifiedName: String = name,
) : INodeSource {
    override var node: CoreNode? = null
}
```

---

## Part 12: Memory and performance

### Fork cost (110K node graph)

| Operation | Cost |
|---|---|
| Copy `parentMap` (110K Long->Long entries) | ~7 MB, < 5 ms |
| Copy `childrenMap` (20K entries, deep copy lists) | ~3 MB, < 5 ms |
| Clear caches (set to null) | O(1) |
| **Total fork** | **~10 MB, < 10 ms** |

### Move cost

| Operation | Cost |
|---|---|
| Update `parentMap` (1 entry) | O(1) |
| Update `childrenMap` (remove from old, add to new) | O(k) where k = children of old parent |
| Clear all caches | O(1) |
| **Total per move** | **O(k), < 1 ms** |

### Cache rebuild after move (lazy, on first query)

| Cache | Rebuild cost | When triggered |
|---|---|---|
| `predecessorsOf(node)` | O(depth) per node, on demand | First access after move |
| `accumulatedOutgoing(node)` | O(subtree size), recursive | First aggregation query |
| `getAggregatedDependency(a, b)` | O(accumulated incoming of b) | First aggregation query |

All caches are rebuilt lazily. Querying a single module's dependencies after moving 20 types
touches only the relevant subtrees, not the entire graph.

### Steady-state memory (no scenarios)

Identical to today. `CoreNodeImpl` has the same fields as the identity portion of `HGNodeImpl`.
`HierarchyImpl` maps replace the `_parent`/`_children` pointers. Net difference: maps have
slight overhead vs direct pointers, but the hierarchy caches are the same. Estimate: **< 5%
memory difference** vs current model.

---

## Part 13: Module structure

All new types go in the existing `io.hierograph.hierarchicalgraph.core.model` module. No new
Maven modules needed.

```
io.hierograph.hierarchicalgraph.core.model/
  src/main/kotlin/io/hierograph/hierarchicalgraph/core/model/
    CoreNode.kt                   (interface)
    CoreDependency.kt             (interface)
    CoreGraph.kt                  (interface)
    AggregatedDependency.kt       (interface)
    Hierarchy.kt                  (interface)
    HierarchyScope.kt             (scoped extensions)
    HGModel.kt                    (consumer entry point)
    INodeSource.kt                (updated: node -> CoreNode)
    IDependencySource.kt          (updated: dependency -> CoreDependency)
    internal/
      CoreNodeImpl.kt
      CoreDependencyImpl.kt
      CoreGraphImpl.kt
      HierarchyImpl.kt
      AggregatedDependencyImpl.kt
    CoreGraphFactory.kt
    HierarchyFactory.kt

  DELETED:
    HGNode.kt
    HGRootNode.kt
    HGCoreDependency.kt
    HGAggregatedDependency.kt
    HGCacheInvalidator.kt
    HGNodeTraverser.kt
    HierarchicalGraphFactory.kt
    internal/HGNodeImpl.kt
    internal/HGRootNodeImpl.kt
    internal/HGCoreDependencyImpl.kt
    internal/HGAggregatedDependencyImpl.kt
```

---

## Part 14: Migration file count

| Area | Files changed | Nature of change |
|---|---|---|
| core.model (interfaces + impls) | ~15 | Delete old, create new |
| core.algorithms | ~6 | Add `Hierarchy` parameter |
| graphdb.model | ~3 | `INodeSource.node` type change, bolt client ref |
| graphdb.mapping.spi | ~2 | Return type adjustments |
| graphdb.mapping.service | ~1 | `DefaultMappingService` rewrite |
| serialization | ~3 | `HGNode` -> `CoreNode` + hierarchy param |
| mcp.jqassistant | ~3 | `HGNode` -> `CoreNode` in metadata provider |
| mcp.javaspec | ~2 | Type references |
| mcp.server (tools) | ~15 | Inject `HGModel`, wrap in `withScope` |
| mcp.server (core) | ~3 | `HierarchicalGraphService` returns `HGModel` |
| graphql controllers | ~8 | `HGNode` -> `CoreNode`, inject `HGModel` |
| Tests | ~6 | Adapt to new types |
| **Total** | **~67** | |
