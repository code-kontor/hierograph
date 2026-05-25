package io.hierograph.hierarchicalgraph.core.model.internal

import io.hierograph.hierarchicalgraph.core.model.HGAggregatedDependency
import io.hierograph.hierarchicalgraph.core.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode

class HGAggregatedDependencyImpl(
    override val from: HGNode,
    override val to: HGNode
) : HGAggregatedDependency {

    override val coreDependencies: List<HGCoreDependency> by lazy {
        to.accumulatedIncomingCoreDependencies.filter { dep ->
            dep.from === from || from.isPredecessorOf(dep.from)
        }
    }

    override val aggregatedWeight: Int by lazy {
        coreDependencies.sumOf { it.weight }
    }
}
