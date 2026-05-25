package io.hierograph.hierarchicalgraph.core.model.internal

import io.hierograph.hierarchicalgraph.core.model.*

open class HGNodeImpl(
    kind: Any?,
    override val nodeSource: INodeSource
) : HGNode {

    override var kind: Any? = kind

    internal var _parent: HGNode? = null
    internal var _children: MutableList<HGNode>? = null

    internal var _outgoingCoreDependencies: MutableList<HGCoreDependency>? = null
    internal var _incomingCoreDependencies: MutableList<HGCoreDependency>? = null

    private var _predecessorsInitialized = false
    private var _predecessors: List<HGNode>? = null

    private var _accumulatedOutgoingInitialized = false
    private var _accumulatedOutgoing: List<HGCoreDependency>? = null

    private var _accumulatedIncomingInitialized = false
    private var _accumulatedIncoming: List<HGCoreDependency>? = null

    private var _cachedAggregatedOutgoing: MutableMap<HGNode, HGAggregatedDependencyImpl>? = null
    private var _cachedAggregatedIncoming: MutableMap<HGNode, HGAggregatedDependencyImpl>? = null

    // -- stored properties --

    override val parent: HGNode? get() = _parent
    override val children: List<HGNode> get() = _children ?: emptyList()

    // -- derived properties --

    override val identifier: Any get() = nodeSource.identifier

    override val rootNode: HGRootNode
        get() {
            if (_parent == null) {
                if (this is HGRootNode) return this
                throw IllegalStateException("No root set for $identifier")
            }
            return _parent!!.rootNode
        }

    override val predecessors: List<HGNode>
        get() {
            if (!_predecessorsInitialized) {
                _predecessors = computePredecessors()
                _predecessorsInitialized = true
            }
            return _predecessors!!
        }

    override val outgoingCoreDependencies: List<HGCoreDependency>
        get() = _outgoingCoreDependencies ?: emptyList()

    override val incomingCoreDependencies: List<HGCoreDependency>
        get() = _incomingCoreDependencies ?: emptyList()

    override val accumulatedOutgoingCoreDependencies: List<HGCoreDependency>
        get() {
            if (!_accumulatedOutgoingInitialized) {
                _accumulatedOutgoing = computeAccumulatedOutgoing()
                _accumulatedOutgoingInitialized = true
            }
            return _accumulatedOutgoing!!
        }

    override val accumulatedIncomingCoreDependencies: List<HGCoreDependency>
        get() {
            if (!_accumulatedIncomingInitialized) {
                _accumulatedIncoming = computeAccumulatedIncoming()
                _accumulatedIncomingInitialized = true
            }
            return _accumulatedIncoming!!
        }

    // -- operations --

    override fun isPredecessorOf(node: HGNode?): Boolean {
        if (node == null) return false
        return node.predecessors.contains(this)
    }

    override fun isSuccessorOf(node: HGNode?): Boolean {
        if (node == null) return false
        return node.isPredecessorOf(this)
    }

    override fun getOutgoingDependenciesTo(target: HGNode): HGAggregatedDependency? {
        val map = cachedAggregatedOutgoing()
        if (!map.containsKey(target)) {
            val dep = HGAggregatedDependencyImpl(from = this, to = target)
            dep.initialize()
            map[target] = dep
            (target as HGNodeImpl).cachedAggregatedIncoming()[this] = dep
        }
        val dep = map[target]!!
        return if (dep.aggregatedWeight > 0) dep else null
    }

    override fun getOutgoingDependenciesTo(targets: List<HGNode>): List<HGAggregatedDependency> {
        return targets.mapNotNull { getOutgoingDependenciesTo(it) }
    }

    override fun getIncomingDependenciesFrom(source: HGNode): HGAggregatedDependency? {
        val map = cachedAggregatedIncoming()
        if (!map.containsKey(source)) {
            val dep = HGAggregatedDependencyImpl(from = source, to = this)
            dep.initialize()
            map[source] = dep
            (source as HGNodeImpl).cachedAggregatedOutgoing()[this] = dep
        }
        val dep = map[source]!!
        return if (dep.aggregatedWeight > 0) dep else null
    }

    override fun getIncomingDependenciesFrom(sources: List<HGNode>): List<HGAggregatedDependency> {
        return sources.mapNotNull { getIncomingDependenciesFrom(it) }
    }

    override fun <T : Any> getNodeSource(clazz: Class<T>): T? {
        return if (clazz.isInstance(nodeSource)) clazz.cast(nodeSource) else null
    }

    // -- cache management --

    internal fun invalidateLocalCaches() {
        _predecessorsInitialized = false
        _predecessors = null
        _accumulatedOutgoingInitialized = false
        _accumulatedOutgoing = null
        _accumulatedIncomingInitialized = false
        _accumulatedIncoming = null

        _cachedAggregatedOutgoing?.values?.forEach { it.invalidate() }
        _cachedAggregatedIncoming?.values?.forEach { it.invalidate() }
    }

    internal fun initializeLocalCaches() {
        predecessors
        accumulatedOutgoingCoreDependencies
        accumulatedIncomingCoreDependencies

        _cachedAggregatedOutgoing?.values?.forEach { it.initialize() }
        _cachedAggregatedIncoming?.values?.forEach { it.initialize() }
    }

    internal fun cachedAggregatedOutgoing(): MutableMap<HGNode, HGAggregatedDependencyImpl> {
        if (_cachedAggregatedOutgoing == null) {
            _cachedAggregatedOutgoing = mutableMapOf()
        }
        return _cachedAggregatedOutgoing!!
    }

    internal fun cachedAggregatedIncoming(): MutableMap<HGNode, HGAggregatedDependencyImpl> {
        if (_cachedAggregatedIncoming == null) {
            _cachedAggregatedIncoming = mutableMapOf()
        }
        return _cachedAggregatedIncoming!!
    }

    // -- mutable list access (lazy allocation) --

    internal fun childrenMutable(): MutableList<HGNode> {
        if (_children == null) _children = mutableListOf()
        return _children!!
    }

    internal fun outgoingMutable(): MutableList<HGCoreDependency> {
        if (_outgoingCoreDependencies == null) _outgoingCoreDependencies = mutableListOf()
        return _outgoingCoreDependencies!!
    }

    internal fun incomingMutable(): MutableList<HGCoreDependency> {
        if (_incomingCoreDependencies == null) _incomingCoreDependencies = mutableListOf()
        return _incomingCoreDependencies!!
    }

    // -- computation --

    private fun computePredecessors(): List<HGNode> {
        val p = _parent ?: return emptyList()
        return buildList {
            add(p)
            addAll(p.predecessors)
        }
    }

    private fun computeAccumulatedOutgoing(): List<HGCoreDependency> {
        return buildList {
            _outgoingCoreDependencies?.let { addAll(it) }
            _children?.forEach { child ->
                addAll(child.accumulatedOutgoingCoreDependencies)
            }
        }
    }

    private fun computeAccumulatedIncoming(): List<HGCoreDependency> {
        return buildList {
            _incomingCoreDependencies?.let { addAll(it) }
            _children?.forEach { child ->
                addAll(child.accumulatedIncomingCoreDependencies)
            }
        }
    }
}
