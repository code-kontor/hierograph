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
import io.hierograph.hierarchicalgraph.core.algorithms.IDependencyStructureMatrix
import io.hierograph.hierarchicalgraph.core.model.HGAggregatedDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode

class DependencyStructureMatrixImpl(nodes: Collection<HGNode>) : IDependencyStructureMatrix {

    override val orderedNodes: List<HGNode>
    override val upwardDependencies: List<HGAggregatedDependency>
    override val cycles: List<List<HGNode>>

    init {
        val allUpward = mutableListOf<HGAggregatedDependency>()

        // 1. Detect all SCCs
        val sccs = GraphUtils.detectStronglyConnectedComponents(nodes)

        // 2. Sort each SCC using FastFAS
        val sorter = FastFasSorter()
        val sortedSccs = sccs.map { scc ->
            val sortResult = sorter.sort(scc)
            allUpward.addAll(sortResult.upwardDependencies)
            sortResult.orderedNodes.toMutableList()
        }

        // 3. Build ordered node list
        val ordered = mutableListOf<HGNode>()

        // First: single-node SCCs with no outgoing core dependencies
        for (scc in sortedSccs) {
            if (scc.size == 1 && scc[0].outgoingCoreDependencies.isEmpty()) {
                ordered.add(scc[0])
            }
        }

        // Then: all remaining nodes
        for (scc in sortedSccs) {
            for (node in scc) {
                if (node !in ordered) {
                    ordered.add(node)
                }
            }
        }

        // Reverse
        ordered.reverse()

        orderedNodes = ordered
        upwardDependencies = allUpward
        cycles = sortedSccs.filter { it.size > 1 }
    }

    override fun isCellInCycle(i: Int, j: Int): Boolean {
        if (i < 0 || i >= orderedNodes.size || j < 0 || j >= orderedNodes.size) return false
        val nodeI = orderedNodes[i]
        val nodeJ = orderedNodes[j]
        return cycles.any { cycle -> cycle.size > 1 && nodeI in cycle && nodeJ in cycle }
    }

    override fun isRowInCycle(i: Int): Boolean = isCellInCycle(i, i)

    override fun getWeight(i: Int, j: Int): Int {
        if (i < 0 || i >= orderedNodes.size || j < 0 || j >= orderedNodes.size) return -1
        val dep = orderedNodes[i].getOutgoingDependenciesTo(orderedNodes[j])
        return dep?.aggregatedWeight ?: 0
    }

    override fun getMatrix(): Array<IntArray> {
        val n = orderedNodes.size
        return Array(n) { i ->
            IntArray(n) { j ->
                val dep = orderedNodes[i].getOutgoingDependenciesTo(orderedNodes[j])
                dep?.aggregatedWeight ?: 0
            }
        }
    }
}
