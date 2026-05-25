package io.hierograph.hierarchicalgraph.core.algorithms

import io.hierograph.hierarchicalgraph.core.model.HGAggregatedDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode

interface INodeSorter {
    fun sort(nodes: List<HGNode>): SortResult
}

interface SortResult {
    val orderedNodes: List<HGNode>
    val upwardDependencies: List<HGAggregatedDependency>
}
