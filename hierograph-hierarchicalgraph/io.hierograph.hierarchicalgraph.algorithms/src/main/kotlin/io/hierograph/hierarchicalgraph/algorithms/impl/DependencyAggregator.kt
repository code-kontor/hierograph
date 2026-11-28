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

import io.hierograph.hierarchicalgraph.algorithms.AggregatedEdge
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy

/**
 * Implements [io.hierograph.hierarchicalgraph.algorithms.DependencyAggregation] by linear bucketing: every node
 * of a selected subtree is mapped to the selected node's index, then each subtree's accumulated outgoing
 * dependencies are walked once and charged to the cell of the selected node containing their target.
 */
internal object DependencyAggregator {

    fun computeAdjacencyMatrix(nodes: List<HGNode>, hierarchy: Hierarchy): Array<IntArray> {
        val n = nodes.size
        val bucket = buildBucketMap(nodes, hierarchy)
        val matrix = Array(n) { IntArray(n) }
        for (i in 0 until n) {
            for (dep in hierarchy.accumulatedOutgoing(nodes[i])) {
                val j = bucket[dep.to.identifier] ?: continue
                matrix[i][j] += dep.weight
            }
        }
        return matrix
    }

    fun computeAdjacencyList(nodes: Collection<HGNode>, hierarchy: Hierarchy): Array<IntArray> {
        val nodeList = nodes.toList()
        val n = nodeList.size
        val bucket = buildBucketMap(nodeList, hierarchy)
        return Array(n) { i ->
            val neighbors = sortedSetOf<Int>()
            for (dep in hierarchy.accumulatedOutgoing(nodeList[i])) {
                val j = bucket[dep.to.identifier] ?: continue
                neighbors.add(j)
            }
            neighbors.toIntArray()
        }
    }

    fun computePairwiseAggregation(nodes: List<HGNode>, hierarchy: Hierarchy): List<AggregatedEdge> {
        val n = nodes.size
        val bucket = buildBucketMap(nodes, hierarchy)
        val weight = HashMap<Long, Int>()
        val bitmap = HashMap<Long, Int>()
        val typePairs = HashMap<Long, MutableSet<Pair<Any, Any>>>()
        for (i in 0 until n) {
            for (dep in hierarchy.accumulatedOutgoing(nodes[i])) {
                val j = bucket[dep.to.identifier] ?: continue
                if (j == i) continue
                val key = i.toLong() * n + j
                weight.merge(key, dep.weight) { a, b -> a + b }
                bitmap.merge(key, dep.attributesBitmap) { a, b -> a or b }
                typePairs.getOrPut(key) { HashSet() }.add(dep.from.identifier to dep.to.identifier)
            }
        }
        return weight.entries.mapNotNull { (key, w) ->
            if (w <= 0) return@mapNotNull null
            AggregatedEdge(
                fromIndex = (key / n).toInt(),
                toIndex = (key % n).toInt(),
                weight = w,
                typePairCount = typePairs.getValue(key).size,
                attributesBitmap = bitmap.getValue(key)
            )
        }
    }

    /**
     * Maps every node contained in each selected subtree (the selected node itself plus all its
     * descendants) to the index of that selected node. For overlapping/nested selections, the later
     * (higher-index) selected node wins.
     */
    private fun buildBucketMap(nodeList: List<HGNode>, hierarchy: Hierarchy): Map<Any, Int> {
        val bucket = HashMap<Any, Int>()
        nodeList.forEachIndexed { i, selected ->
            bucket[selected.identifier] = i
            hierarchy.traverse(selected) { descendant -> bucket[descendant.identifier] = i }
        }
        return bucket
    }
}
