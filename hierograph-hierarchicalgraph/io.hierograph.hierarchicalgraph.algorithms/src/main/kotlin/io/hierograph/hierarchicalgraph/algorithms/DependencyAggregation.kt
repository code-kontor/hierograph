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
package io.hierograph.hierarchicalgraph.algorithms

import io.hierograph.hierarchicalgraph.algorithms.internal.DependencyAggregator
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy

/**
 * The dependencies among a node set, aggregated per pair of selected subtrees (specification, §4): as a weight
 * matrix, an adjacency list, or a list of aggregated edges. Each is computed by one linear bucketing pass.
 */
object DependencyAggregation {

    /**
     * The `n x n` weight matrix among [nodes], where `matrix[i][j]` is the summed weight of all
     * dependencies from anything in subtree `i` to anything in subtree `j`. The diagonal carries the
     * subtree-internal weight.
     *
     * Computed by a single linear bucketing pass — one walk of each subtree's accumulated outgoing
     * edges, each charged to the cell of the selected node that contains its target. This is defined for
     * disjoint-subtree inputs; with nested inputs (a selected node inside another), a dependency is
     * charged to the row of every selected node whose subtree contains its source, and its target is
     * attributed to the later (higher-index) selected node that contains it.
     */
    fun computeAdjacencyMatrix(nodes: List<HGNode>, hierarchy: Hierarchy): Array<IntArray> =
        DependencyAggregator.computeAdjacencyMatrix(nodes, hierarchy)

    /**
     * The adjacency list among [nodes]: `result[i]` is the ascending, de-duplicated list of indices `j`
     * such that subtree `i` depends on subtree `j`. Same linear bucketing as [computeAdjacencyMatrix];
     * a self-edge `i` appears only when subtree `i` has an internal dependency.
     */
    fun computeAdjacencyList(nodes: Collection<HGNode>, hierarchy: Hierarchy): Array<IntArray> =
        DependencyAggregator.computeAdjacencyList(nodes, hierarchy)

    /**
     * Computes every non-empty off-diagonal aggregated edge among [nodes] in a single linear pass: it
     * walks each subtree's accumulated outgoing edges once, bucketing each edge into the `(i, j)` cell of
     * the selected nodes that contain its endpoints, and accumulating weight, the set of contributing
     * type pairs, and the union of attribute bitmaps. Self-loops (`i == j`) and zero-weight cells are
     * omitted. Indices are positions in [nodes], so callers that pass an already ordered node list
     * (e.g. a DSM's `orderedNodes`) get edges indexed in that order.
     */
    fun computePairwiseAggregation(nodes: List<HGNode>, hierarchy: Hierarchy): List<AggregatedEdge> =
        DependencyAggregator.computePairwiseAggregation(nodes, hierarchy)
}
