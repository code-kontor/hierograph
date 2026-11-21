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

import io.hierograph.hierarchicalgraph.algorithms.ILevelization
import io.hierograph.hierarchicalgraph.algorithms.Tangle
import io.hierograph.hierarchicalgraph.model.AggregatedDependency
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy

/**
 * The levelization of a node set, built on its dependency structure matrix: the DSM's cycles are the tangles, in
 * the DSM's order, and a node's level is the longest chain of dependencies beneath it on the graph with every
 * tangle condensed to one node.
 */
class LevelizationImpl(nodes: Collection<HGNode>, hierarchy: Hierarchy) : ILevelization {

    override val levels: List<List<HGNode>>
    override val tangles: List<Tangle>
    override val feedbackDependencies: List<AggregatedDependency>
    private val levelByNode: Map<Any, Int>

    init {
        val dsm = DependencyStructureMatrixImpl(nodes, hierarchy)
        val ordered = dsm.orderedNodes
        val matrix = dsm.getMatrix()
        val n = ordered.size
        val position = HashMap<Any, Int>(n * 2).apply { ordered.forEachIndexed { i, node -> put(node.identifier, i) } }

        // Each node's component: the position of its tangle's first member, or its own.
        val component = IntArray(n) { it }
        val cyclePositions = dsm.cycles.map { cycle -> cycle.map { position.getValue(it.identifier) } }
        for (members in cyclePositions) for (i in members) component[i] = members.first()

        // The condensed graph's successors, and the longest chain beneath each component.
        val successors = HashMap<Int, MutableSet<Int>>()
        for (i in 0 until n) for (j in 0 until n) {
            if (matrix[i][j] > 0 && component[i] != component[j]) {
                successors.getOrPut(component[i]) { HashSet() }.add(component[j])
            }
        }
        val componentLevel = HashMap<Int, Int>()
        fun levelOf(c: Int): Int = componentLevel[c] ?: run {
            val level = successors[c].orEmpty().maxOfOrNull { levelOf(it) + 1 } ?: 0
            componentLevel[c] = level
            level
        }
        val level = IntArray(n) { levelOf(component[it]) }

        val rows = List((level.maxOrNull() ?: -1) + 1) { mutableListOf<HGNode>() }
        for (i in 0 until n) rows[level[i]].add(ordered[i])
        levels = rows
        levelByNode = HashMap<Any, Int>(n * 2).apply { for (i in 0 until n) put(ordered[i].identifier, level[i]) }

        // Inside a tangle, in its order (dependents first): a dependency on a later member points down, one on an
        // earlier member is a feedback dependency. A member's row is the longest chain of downward ones beneath it.
        val feedback = mutableListOf<AggregatedDependency>()
        tangles = cyclePositions.map { members ->
            val inner = IntArray(members.size)
            for (a in members.indices.reversed()) {
                for (b in a + 1 until members.size) {
                    if (matrix[members[a]][members[b]] > 0) inner[a] = maxOf(inner[a], inner[b] + 1)
                }
                for (b in 0 until a) {
                    if (matrix[members[a]][members[b]] > 0) {
                        hierarchy.getAggregatedDependency(ordered[members[a]], ordered[members[b]])?.let { feedback += it }
                    }
                }
            }
            val innerRows = List(inner.max() + 1) { mutableListOf<HGNode>() }
            members.forEachIndexed { k, i -> innerRows[inner[k]].add(ordered[i]) }
            Tangle(level[members.first()], members.map { ordered[it] }, innerRows)
        }
        // Heaviest first; ties in matrix order of their source, then their target.
        feedbackDependencies = feedback.sortedWith(
            compareByDescending<AggregatedDependency> { it.aggregatedWeight }
                .thenBy { position.getValue(it.from.identifier) }
                .thenBy { position.getValue(it.to.identifier) }
        )
    }

    override fun levelOf(node: HGNode): Int = levelByNode[node.identifier] ?: -1
}
