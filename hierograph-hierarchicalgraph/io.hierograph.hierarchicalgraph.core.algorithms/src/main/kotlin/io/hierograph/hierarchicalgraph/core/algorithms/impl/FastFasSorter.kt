/*
 * Copyright 2026 Gerd Wuetherich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hierograph.hierarchicalgraph.core.algorithms.impl

import io.hierograph.hierarchicalgraph.core.algorithms.GraphUtils
import io.hierograph.hierarchicalgraph.core.algorithms.INodeSorter
import io.hierograph.hierarchicalgraph.core.algorithms.SortResult
import io.hierograph.hierarchicalgraph.core.model.AggregatedDependency
import io.hierograph.hierarchicalgraph.core.model.CoreNode
import io.hierograph.hierarchicalgraph.core.model.Hierarchy

class FastFasSorter : INodeSorter {

    override fun sort(nodes: List<CoreNode>, hierarchy: Hierarchy): SortResult {
        val adjacencyMatrix = GraphUtils.computeAdjacencyMatrix(nodes, hierarchy)

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
            hierarchy.getAggregatedDependency(nodes[edge[0]], nodes[edge[1]])
        }

        return object : SortResult {
            override val orderedNodes: List<CoreNode> = resultNodes
            override val upwardDependencies: List<AggregatedDependency> = upwardDeps
        }
    }
}
