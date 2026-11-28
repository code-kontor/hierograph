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
package io.hierograph.hierarchicalgraph.algorithms.internal

import io.hierograph.hierarchicalgraph.algorithms.INodeSorter
import io.hierograph.hierarchicalgraph.algorithms.SortResult
import io.hierograph.hierarchicalgraph.model.AggregatedDependency
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy

class FastFasSorter : INodeSorter {

    override fun sort(nodes: List<HGNode>, hierarchy: Hierarchy): SortResult {
        val adjacencyMatrix = DependencyAggregator.computeAdjacencyMatrix(nodes, hierarchy)

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

        // A small component gets the exact order: the one with the least upward weight. It replaces the heuristic
        // order only when it is strictly better, so an order that is already optimal stays as it is.
        val isExact = ordered.size <= EXACT_LIMIT
        if (isExact && ordered.size > 2) {
            val exact = exactOrder(adjacencyMatrix)
            if (upwardWeight(exact, adjacencyMatrix) < upwardWeight(ordered, adjacencyMatrix)) ordered = exact
        }

        // Upward dependencies are derived from the refined order, not from FastFAS's skipped edges: the
        // bubble pass above can swap two vertices and so reverse the direction of the edges between them.
        // In this (dependents-first) order, an edge from a later vertex to an earlier one points upward.
        val upwardDeps = mutableListOf<AggregatedDependency>()
        for (later in ordered.indices) {
            for (earlier in 0 until later) {
                val from = ordered[later]
                val to = ordered[earlier]
                if (adjacencyMatrix[from][to] > 0) {
                    hierarchy.getAggregatedDependency(nodes[from], nodes[to])?.let { upwardDeps.add(it) }
                }
            }
        }

        ordered = FastFAS.reverse(ordered)

        val resultNodes = ordered.map { nodes[it] }

        return SortResult(orderedNodes = resultNodes, upwardDependencies = upwardDeps, isExact = isExact)
    }

    companion object {
        /** The largest component that gets the exact order; its cost grows with `2^k · k²`. */
        const val EXACT_LIMIT = 12

        /** The weight of the edges that point upward in [order] (dependents first): from a vertex to an earlier one. */
        internal fun upwardWeight(order: IntArray, adjacencyMatrix: Array<IntArray>): Long {
            var weight = 0L
            for (later in order.indices) for (earlier in 0 until later) weight += adjacencyMatrix[order[later]][order[earlier]]
            return weight
        }

        /**
         * The order of all vertices, dependents first, with the least upward weight: a dynamic program over the subsets
         * of vertices placed first. Placing `v` after the set `S` costs the weight of `v`'s edges into `S`. Ties go to
         * the lowest vertex, so the result is deterministic.
         */
        internal fun exactOrder(adjacencyMatrix: Array<IntArray>): IntArray {
            val n = adjacencyMatrix.size
            val full = (1 shl n) - 1
            val cost = LongArray(1 shl n) { Long.MAX_VALUE }
            val last = IntArray(1 shl n) { -1 }
            cost[0] = 0
            for (set in 1..full) {
                for (v in 0 until n) {
                    if (set and (1 shl v) == 0) continue
                    val before = set and (1 shl v).inv()
                    if (cost[before] == Long.MAX_VALUE) continue
                    var c = cost[before]
                    for (u in 0 until n) if (before and (1 shl u) != 0) c += adjacencyMatrix[v][u]
                    if (c < cost[set]) {
                        cost[set] = c
                        last[set] = v
                    }
                }
            }
            val order = IntArray(n)
            var set = full
            for (position in n - 1 downTo 0) {
                val v = last[set]
                order[position] = v
                set = set and (1 shl v).inv()
            }
            return order
        }
    }
}
