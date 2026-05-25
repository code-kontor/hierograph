package io.hierograph.hierarchicalgraph.core.model.internal

import io.hierograph.hierarchicalgraph.core.model.HGAggregatedDependency
import io.hierograph.hierarchicalgraph.core.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode

class HGAggregatedDependencyImpl(
    override val from: HGNode,
    override val to: HGNode
) : HGAggregatedDependency {

    private var initialized = false
    private var _coreDependencies: List<HGCoreDependency> = emptyList()
    private var _aggregatedWeight: Int = 0

    override val coreDependencies: List<HGCoreDependency>
        get() {
            initialize()
            return _coreDependencies
        }

    override val aggregatedWeight: Int
        get() {
            initialize()
            return _aggregatedWeight
        }

    fun initialize() {
        if (initialized) return

        val toNode = to as HGNodeImpl
        val deps = toNode.accumulatedIncomingCoreDependencies.filter { dep ->
            dep.from === from || from.isPredecessorOf(dep.from)
        }

        _coreDependencies = deps
        _aggregatedWeight = deps.sumOf { it.weight }
        initialized = true
    }

    fun invalidate() {
        if (!initialized) return
        initialized = false
        _coreDependencies = emptyList()
        _aggregatedWeight = 0
    }
}
