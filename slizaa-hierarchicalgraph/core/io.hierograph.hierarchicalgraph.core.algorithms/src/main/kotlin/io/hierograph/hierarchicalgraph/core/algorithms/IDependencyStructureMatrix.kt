package io.hierograph.hierarchicalgraph.core.algorithms

import io.hierograph.hierarchicalgraph.core.model.HGAggregatedDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode

interface IDependencyStructureMatrix {
    val orderedNodes: List<HGNode>
    val upwardDependencies: List<HGAggregatedDependency>
    val cycles: List<List<HGNode>>

    fun isCellInCycle(i: Int, j: Int): Boolean
    fun isRowInCycle(i: Int): Boolean
    fun getWeight(i: Int, j: Int): Int
    fun getMatrix(): Array<IntArray>
}
