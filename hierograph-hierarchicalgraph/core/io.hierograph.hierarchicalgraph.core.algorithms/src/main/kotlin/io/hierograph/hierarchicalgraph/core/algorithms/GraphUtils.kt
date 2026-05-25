package io.hierograph.hierarchicalgraph.core.algorithms

import io.hierograph.hierarchicalgraph.core.algorithms.impl.DependencyStructureMatrixImpl
import io.hierograph.hierarchicalgraph.core.algorithms.impl.FastFasSorter
import io.hierograph.hierarchicalgraph.core.algorithms.impl.Tarjan
import io.hierograph.hierarchicalgraph.core.model.HGNode

object GraphUtils {

    fun detectStronglyConnectedComponents(nodes: Collection<HGNode>): List<List<HGNode>> {
        return Tarjan().detectStronglyConnectedComponents(nodes)
    }

    fun detectCycles(nodes: Collection<HGNode>): List<List<HGNode>> {
        return Tarjan().detectStronglyConnectedComponents(nodes).filter { it.size > 1 }
    }

    fun createDependencyStructureMatrix(nodes: Collection<HGNode>): IDependencyStructureMatrix {
        return DependencyStructureMatrixImpl(nodes)
    }

    fun computeAdjacencyMatrix(nodes: List<HGNode>): Array<IntArray> {
        val n = nodes.size
        return Array(n) { i ->
            IntArray(n) { j ->
                val dep = nodes[i].getOutgoingDependenciesTo(nodes[j])
                dep?.aggregatedWeight ?: 0
            }
        }
    }

    fun computeAdjacencyList(nodes: Collection<HGNode>): Array<IntArray> {
        val nodeList = nodes.toList()
        val indexMap = nodeList.withIndex().associate { (i, node) -> node to i }

        return Array(nodeList.size) { i ->
            val deps = nodeList[i].getOutgoingDependenciesTo(nodeList)
            IntArray(deps.size) { j -> indexMap[deps[j].to]!! }
        }
    }

    fun createFasNodeSorter(): INodeSorter {
        return FastFasSorter()
    }
}
