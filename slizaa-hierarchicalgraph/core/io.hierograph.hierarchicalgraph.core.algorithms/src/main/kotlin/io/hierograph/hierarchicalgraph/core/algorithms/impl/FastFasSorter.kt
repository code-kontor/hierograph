package io.hierograph.hierarchicalgraph.core.algorithms.impl

import io.hierograph.hierarchicalgraph.core.algorithms.GraphUtils
import io.hierograph.hierarchicalgraph.core.algorithms.INodeSorter
import io.hierograph.hierarchicalgraph.core.algorithms.SortResult
import io.hierograph.hierarchicalgraph.core.model.HGAggregatedDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode

class FastFasSorter : INodeSorter {

    override fun sort(nodes: List<HGNode>): SortResult {
        val adjacencyMatrix = GraphUtils.computeAdjacencyMatrix(nodes)

        val fastFAS = FastFAS(adjacencyMatrix)
        var ordered = fastFAS.getOrderedSequence()

        // Bubble sort refinement
        for (outerIndex in 1 until ordered.size) {
            var index = outerIndex
            while (index >= 1) {
                if (adjacencyMatrix[ordered[index]][ordered[index - 1]] >
                    adjacencyMatrix[ordered[index - 1]][ordered[index]]
                ) {
                    val temp = ordered[index]
                    ordered[index] = ordered[index - 1]
                    ordered[index - 1] = temp
                    index--
                } else {
                    break
                }
            }
        }

        ordered = FastFAS.reverse(ordered)

        val resultNodes = ordered.map { nodes[it] }

        val upwardDeps = fastFAS.getSkippedEdges().mapNotNull { edge ->
            nodes[edge[0]].getOutgoingDependenciesTo(nodes[edge[1]])
        }

        return object : SortResult {
            override val orderedNodes: List<HGNode> = resultNodes
            override val upwardDependencies: List<HGAggregatedDependency> = upwardDeps
        }
    }
}
