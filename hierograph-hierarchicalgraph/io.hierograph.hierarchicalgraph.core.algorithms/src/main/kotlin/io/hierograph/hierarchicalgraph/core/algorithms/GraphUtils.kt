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
package io.hierograph.hierarchicalgraph.core.algorithms

import io.hierograph.hierarchicalgraph.core.algorithms.impl.DependencyStructureMatrixImpl
import io.hierograph.hierarchicalgraph.core.algorithms.impl.FastFasSorter
import io.hierograph.hierarchicalgraph.core.algorithms.impl.Tarjan
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.Hierarchy

object GraphUtils {

    fun detectStronglyConnectedComponents(nodes: Collection<HGNode>, hierarchy: Hierarchy): List<List<HGNode>> {
        return Tarjan().detectStronglyConnectedComponents(nodes, hierarchy)
    }

    fun detectCycles(nodes: Collection<HGNode>, hierarchy: Hierarchy): List<List<HGNode>> {
        return Tarjan().detectStronglyConnectedComponents(nodes, hierarchy).filter { it.size > 1 }
    }

    fun createDependencyStructureMatrix(nodes: Collection<HGNode>, hierarchy: Hierarchy): IDependencyStructureMatrix {
        return DependencyStructureMatrixImpl(nodes, hierarchy)
    }

    /**
     * The `n x n` weight matrix among [nodes], where `matrix[i][j]` is the summed weight of all
     * dependencies from anything in subtree `i` to anything in subtree `j` (the diagonal carries the
     * subtree-internal weight, as before).
     *
     * Computed by a single linear bucketing pass — one walk of each subtree's accumulated outgoing
     * edges, each charged to the cell of the selected node that contains its target — rather than the
     * former `O(n^2)` of one `getAggregatedDependency` call per cell. The result is identical for the
     * disjoint-subtree inputs this is used with; nested inputs (a selected node inside another) attribute
     * each contained node to the innermost-by-input-order selected node.
     */
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

    /**
     * The adjacency list among [nodes]: `result[i]` is the ascending, de-duplicated list of indices `j`
     * such that subtree `i` depends on subtree `j`. Same linear bucketing as [computeAdjacencyMatrix];
     * a self-edge `i` appears only when subtree `i` has an internal dependency (matching the previous
     * `getAggregatedDependency`-based behavior).
     */
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

    /**
     * One aggregated DSM edge among a node set: `from`/`to` are indices into the node list passed to
     * [computePairwiseAggregation].
     *
     * @property weight summed weight of the underlying dependencies.
     * @property typePairCount number of distinct `(source type, target type)` pairs contributing.
     * @property attributesBitmap union of the contributing dependencies' attribute bitmaps.
     */
    data class AggregatedEdge(
        val fromIndex: Int,
        val toIndex: Int,
        val weight: Int,
        val typePairCount: Int,
        val attributesBitmap: Int,
    )

    /**
     * Computes every non-empty off-diagonal aggregated edge among [nodes] in a single linear pass.
     *
     * This is the matrix-shaped counterpart to repeatedly calling `getAggregatedDependency` for each of
     * the `n^2` cells: it walks each subtree's accumulated outgoing edges once, bucketing each edge into
     * the `(i, j)` cell of the selected nodes that contain its endpoints, and accumulating weight, the
     * set of contributing type pairs, and the union of attribute bitmaps. Self-loops (`i == j`) and
     * zero-weight cells are omitted. Indices are positions in [nodes], so callers that pass an already
     * ordered node list (e.g. a DSM's `orderedNodes`) get edges indexed in that order.
     */
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

    fun createFasNodeSorter(): INodeSorter {
        return FastFasSorter()
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
