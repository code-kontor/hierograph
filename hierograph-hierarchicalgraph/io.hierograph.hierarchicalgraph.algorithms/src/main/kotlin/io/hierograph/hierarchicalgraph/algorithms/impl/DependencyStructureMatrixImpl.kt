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
package io.hierograph.hierarchicalgraph.algorithms.impl

import io.hierograph.hierarchicalgraph.algorithms.GraphUtils
import io.hierograph.hierarchicalgraph.algorithms.IDependencyStructureMatrix
import io.hierograph.hierarchicalgraph.model.AggregatedDependency
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy

class DependencyStructureMatrixImpl(nodes: Collection<HGNode>, private val hierarchy: Hierarchy) : IDependencyStructureMatrix {

    override val orderedNodes: List<HGNode>
    override val upwardDependencies: List<AggregatedDependency>
    override val cycles: List<List<HGNode>>

    init {
        val allUpward = mutableListOf<AggregatedDependency>()

        // 1. Detect all SCCs
        val sccs = GraphUtils.detectStronglyConnectedComponents(nodes, hierarchy)

        // 2. Sort each SCC using FastFAS
        val sorter = FastFasSorter()
        val sortedSccs = sccs.map { scc ->
            val sortResult = sorter.sort(scc, hierarchy)
            allUpward.addAll(sortResult.upwardDependencies)
            sortResult.orderedNodes.toMutableList()
        }

        // 3. Build ordered node list. Membership is tracked in a HashSet of identifiers so the
        //    "already added?" check is O(1).
        val ordered = mutableListOf<HGNode>()
        val seen = HashSet<Any>()

        // First: sinks — single-node SCCs with no dependency on any *other* node of the set. Decided from
        //    the node set's adjacency (which aggregates each node's subtree), not from the node's own core
        //    dependencies: a container node (module, package) usually has no edges of its own. Placing
        //    sinks first sends them to the bottom after the reversal, which keeps the order topological.
        val nodeList = nodes.toList()
        val adjacency = GraphUtils.computeAdjacencyList(nodeList, hierarchy)
        val indexOf = HashMap<Any, Int>(nodeList.size * 2).apply {
            nodeList.forEachIndexed { i, node -> put(node.identifier, i) }
        }
        fun isSink(node: HGNode): Boolean {
            val i = indexOf.getValue(node.identifier)
            return adjacency[i].all { it == i }
        }
        for (scc in sortedSccs) {
            if (scc.size == 1 && isSink(scc[0])) {
                if (seen.add(scc[0].identifier)) ordered.add(scc[0])
            }
        }

        // Then: all remaining nodes. Tarjan emits SCCs in reverse topological order (dependencies
        //    before their dependents), so after the reversal every dependent precedes its dependencies.
        for (scc in sortedSccs) {
            for (node in scc) {
                if (seen.add(node.identifier)) {
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

    /**
     * The `n x n` weight matrix (including the subtree-internal diagonal), built once by a single linear
     * pass and reused. Backs both [getWeight] and [getMatrix].
     */
    private val weightMatrix: Array<IntArray> by lazy {
        GraphUtils.computeAdjacencyMatrix(orderedNodes, hierarchy)
    }

    override fun getWeight(i: Int, j: Int): Int {
        if (i < 0 || i >= orderedNodes.size || j < 0 || j >= orderedNodes.size) return -1
        return weightMatrix[i][j]
    }

    override fun getMatrix(): Array<IntArray> = weightMatrix
}
