package io.hierograph.hierarchicalgraph.core.model.internal

import io.hierograph.hierarchicalgraph.core.model.*

open class HGNodeImpl(
    kind: Any?,
    override val nodeSource: INodeSource
) : HGNode {

    override var kind: Any? = kind

    internal var _parent: HGNode? = null
    internal val _children: MutableList<HGNode> = mutableListOf()

    internal val _outgoingCoreDependencies: MutableList<HGCoreDependency> = mutableListOf()
    internal val _incomingCoreDependencies: MutableList<HGCoreDependency> = mutableListOf()

    private val _cachedPredecessors = InvalidatableLazy { computePredecessors() }
    private val _cachedAccumulatedOutgoing = InvalidatableLazy { computeAccumulatedOutgoing() }
    private val _cachedAccumulatedIncoming = InvalidatableLazy { computeAccumulatedIncoming() }

    private var _cachedAggregatedOutgoing: MutableMap<HGNode, HGAggregatedDependencyImpl>? = null
    private var _cachedAggregatedIncoming: MutableMap<HGNode, HGAggregatedDependencyImpl>? = null

    // -- stored properties --

    override val parent: HGNode? get() = _parent
    override val children: List<HGNode> get() = _children

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

    override val predecessors: List<HGNode> get() = _cachedPredecessors.get()

    override val outgoingCoreDependencies: List<HGCoreDependency> get() = _outgoingCoreDependencies
    override val incomingCoreDependencies: List<HGCoreDependency> get() = _incomingCoreDependencies
    override val accumulatedOutgoingCoreDependencies: List<HGCoreDependency> get() = _cachedAccumulatedOutgoing.get()
    override val accumulatedIncomingCoreDependencies: List<HGCoreDependency> get() = _cachedAccumulatedIncoming.get()

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
        _cachedPredecessors.invalidate()
        _cachedAccumulatedOutgoing.invalidate()
        _cachedAccumulatedIncoming.invalidate()

        _cachedAggregatedOutgoing?.values?.forEach { it.invalidate() }
        _cachedAggregatedIncoming?.values?.forEach { it.invalidate() }
    }

    internal fun initializeLocalCaches() {
        _cachedPredecessors.get()
        _cachedAccumulatedOutgoing.get()
        _cachedAccumulatedIncoming.get()

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
            addAll(_outgoingCoreDependencies)
            for (child in _children) {
                addAll(child.accumulatedOutgoingCoreDependencies)
            }
        }
    }

    private fun computeAccumulatedIncoming(): List<HGCoreDependency> {
        return buildList {
            addAll(_incomingCoreDependencies)
            for (child in _children) {
                addAll(child.accumulatedIncomingCoreDependencies)
            }
        }
    }
}
