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
package io.hierograph.mcp.server.tools.dependencyanalysis

import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.algorithms.DependencyAggregation
import io.hierograph.hierarchicalgraph.algorithms.GraphAlgorithms
import io.hierograph.hierarchicalgraph.algorithms.IDependencyStructureMatrix
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.mapping.GraphStoreDependencySource
import org.springframework.stereotype.Component

/**
 * One aggregated edge of a [PairwiseMatrix]: everything one listed subtree uses of another. [fromIdx] and [toIdx]
 * are positions in the matrix's node order (the topological order when it is acyclic), so "dsm" ordering is a
 * plain index sort.
 */
data class MatrixEdge(
    val fromId: Any,
    val toId: Any,
    val fromIdx: Int,
    val toIdx: Int,
    val weight: Int,
    val typePairCount: Int,
    val attributesBitmap: Int,
) {
    /** Whether the edge points back up the matrix order: from a node to one placed before it, so it closes a cycle. */
    val isBack: Boolean get() = fromIdx > toIdx
}

/**
 * The dependency matrix among a set of disjoint subtrees: its node order, every aggregated edge between them, and
 * the analysis over the whole matrix (density, cycles, topological order). What `pairwise_dependencies` pages
 * through.
 */
class PairwiseMatrix internal constructor(
    private val dsm: IDependencyStructureMatrix,
    private val hierarchy: Hierarchy,
    /** Every edge, unfiltered, depender → depended-upon, in no particular order. */
    val edges: List<MatrixEdge>,
) {
    /** The nodes in matrix order, dependers first: the topological order when the matrix is acyclic. */
    val orderedNodes: List<HGNode> get() = dsm.orderedNodes

    /**
     * The analysis over the whole matrix: counts, density, the node order and the strongly connected components;
     * for a cyclic matrix also the back edges, the mutual dependencies and how good the order is.
     */
    fun analysis(): LinkedHashMap<String, Any?> {
        val size = orderedNodes.size
        val possibleEdges = size * (size - 1)
        val density = if (possibleEdges > 0) Math.round(edges.size * 100.0 / possibleEdges) / 100.0 else 0.0
        val hasCycles = dsm.cycles.isNotEmpty()
        val nodeOrder = orderedNodes.map { it.identifier }
        val analysis = linkedMapOf<String, Any?>(
            "node_count" to size,
            "edge_count" to edges.size, // unfiltered count, drives density
            "possible_edges" to possibleEdges,
            "density" to density,
            "has_cycles" to hasCycles,
            "node_order" to nodeOrder,
            "strongly_connected_components" to dsm.cycles.map { cycle -> cycle.map { it.identifier } },
        )
        if (!hasCycles) {
            analysis["topological_order"] = nodeOrder
            return analysis
        }

        val weightOf = HashMap<Long, Int>(edges.size * 2).apply { for (e in edges) put(pairKey(e.fromIdx, e.toIdx), e.weight) }
        fun reverseWeight(e: MatrixEdge) = weightOf[pairKey(e.toIdx, e.fromIdx)] ?: 0

        val backEdges = edges.filter { it.isBack }.sortedWith(PairwiseMatrix.WEIGHT_ORDER)
        val backEdgeWeight = backEdges.sumOf { it.weight.toLong() }
        // Each 2-cycle forces one of its directions above the diagonal: the sum of the lighter directions is a lower
        // bound for every order. In each pair, `from` is the node earlier in the order, so its reverse is the back one.
        val mutual = edges.filter { !it.isBack && reverseWeight(it) > 0 }.sortedWith(PairwiseMatrix.DSM_ORDER)
        val lowerBound = mutual.sumOf { minOf(it.weight, reverseWeight(it)).toLong() }

        analysis["back_edge_count"] = backEdges.size
        analysis["back_edge_weight"] = backEdgeWeight
        analysis["back_edges"] = backEdges.take(LISTED).map { it.withReverse(reverseWeight(it)) }
        analysis["back_edges_truncated"] = backEdges.size > LISTED
        analysis["mutual_dependency_count"] = mutual.size
        analysis["mutual_dependencies"] = mutual.take(LISTED).map { it.withReverse(reverseWeight(it)) }
        analysis["mutual_dependencies_truncated"] = mutual.size > LISTED
        analysis["all_cycles_pairwise"] = acyclicWithoutLighterDirections(mutual, ::reverseWeight)
        analysis["order_quality"] = if (dsm.isOrderExact || backEdgeWeight == lowerBound) "exact" else "heuristic"
        return analysis
    }

    /**
     * Whether cutting the lighter direction of every 2-cycle (the back one on a tie) leaves the matrix acyclic: then
     * every cycle runs through 2-cycles alone, and those cuts untangle the set.
     */
    private fun acyclicWithoutLighterDirections(mutual: List<MatrixEdge>, reverseWeight: (MatrixEdge) -> Int): Boolean {
        val cut = HashSet<Long>(mutual.size * 2)
        for (e in mutual) {
            if (e.weight < reverseWeight(e)) cut += pairKey(e.fromIdx, e.toIdx) else cut += pairKey(e.toIdx, e.fromIdx)
        }
        val n = orderedNodes.size
        val successors = Array(n) { mutableListOf<Int>() }
        val inDegree = IntArray(n)
        for (e in edges) {
            if (pairKey(e.fromIdx, e.toIdx) in cut) continue
            successors[e.fromIdx] += e.toIdx
            inDegree[e.toIdx]++
        }
        val ready = ArrayDeque((0 until n).filter { inDegree[it] == 0 })
        var visited = 0
        while (ready.isNotEmpty()) {
            val v = ready.removeFirst()
            visited++
            for (w in successors[v]) if (--inDegree[w] == 0) ready.addLast(w)
        }
        return visited == n
    }

    private fun MatrixEdge.withReverse(reverse: Int) =
        linkedMapOf<String, Any?>("from" to fromId, "to" to toId, "weight" to weight, "reverse_weight" to reverse)

    private fun pairKey(from: Int, to: Int): Long = (from.toLong() shl 32) or to.toLong()

    /** The fact counts per kind of the type-level dependencies under [edge]. */
    fun kindsOf(edge: MatrixEdge): Map<DependencyKind, Int> {
        val coreDeps = hierarchy.getAggregatedDependency(orderedNodes[edge.fromIdx], orderedNodes[edge.toIdx])
            ?.coreDependencies.orEmpty()
        return GraphStoreDependencySource.kindsOf(coreDeps)
    }

    companion object {
        /** Matrix reading order: by row, then by column. */
        val DSM_ORDER: Comparator<MatrixEdge> = compareBy<MatrixEdge> { it.fromIdx }.thenBy { it.toIdx }

        /** The most back edges and mutual dependencies the analysis lists; the counts and weights cover all. */
        const val LISTED = 50

        /** Heaviest first; ties in matrix reading order. */
        val WEIGHT_ORDER: Comparator<MatrixEdge> = compareByDescending<MatrixEdge> { it.weight }.then(DSM_ORDER)
    }
}

/**
 * Validates a node set and computes its [PairwiseMatrix], apart from how `pairwise_dependencies` pages and renders it.
 */
@Component
class PairwiseMatrices(
    private val graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory,
) {

    /** The outcome of [compute]: the matrix, or the error response that refuses the node set. */
    sealed interface Result {
        class Computed(val matrix: PairwiseMatrix) : Result
        class Rejected(val response: Map<String, Any?>) : Result
    }

    /**
     * The matrix among [nodeIds], or why it cannot be computed: fewer than 2 or more than [MAX_NODES] ids, an id
     * that names no node or a member, or ids that are not disjoint subtrees. [tool] names the calling tool in the
     * messages.
     */
    fun compute(tool: String, nodeIds: List<Long>): Result {
        if (nodeIds.size < 2) {
            return Result.Rejected(
                error(
                    "INPUT_TOO_SMALL", "$tool requires at least 2 node IDs, got ${nodeIds.size}.",
                    "For single-pair queries, use aggregated_dependencies instead.",
                ),
            )
        }
        if (nodeIds.size > MAX_NODES) {
            return Result.Rejected(
                mapOf(
                    "error" to linkedMapOf(
                        "code" to "INPUT_TOO_LARGE",
                        "message" to "$tool accepts at most $MAX_NODES node IDs " +
                            "(the summary — SCCs and topological order — is O(node_count)), got ${nodeIds.size}.",
                        "node_count" to nodeIds.size,
                        "max_nodes" to MAX_NODES,
                        "recovery" to "Narrow the node set, or use aggregated_dependencies for an asymmetric " +
                            "slice. The number of edges is not limited, so node count is the only thing to reduce.",
                    ),
                ),
            )
        }

        val hierarchy = graphService.model.hierarchy
        val resolvedNodes = ArrayList<HGNode>(nodeIds.size)
        for (id in nodeIds) {
            val node = graphService.model.lookupNode(id) ?: return Result.Rejected(nodeNotFound(id))
            memberRejected(node)?.let { return Result.Rejected(it) }
            resolvedNodes.add(node)
        }
        overlapping(resolvedNodes)?.let { return Result.Rejected(it) }

        // Analytics are computed over the FULL node set.
        val dsm = GraphAlgorithms.createDependencyStructureMatrix(resolvedNodes, hierarchy)
        val orderedNodes = dsm.orderedNodes
        // computePairwiseAggregation yields i→j (i depends on j): every edge reads depender → depended-upon.
        val edges = DependencyAggregation.computePairwiseAggregation(orderedNodes, hierarchy).map { e ->
            MatrixEdge(
                fromId = orderedNodes[e.fromIndex].identifier,
                toId = orderedNodes[e.toIndex].identifier,
                fromIdx = e.fromIndex,
                toIdx = e.toIndex,
                weight = e.weight,
                typePairCount = e.typePairCount,
                attributesBitmap = e.attributesBitmap,
            )
        }
        return Result.Computed(PairwiseMatrix(dsm, hierarchy, edges))
    }

    /**
     * The `INVALID_PARAMETER` error for a node set that is not disjoint, or `null` when it is. A matrix
     * row stands for a whole subtree, so an id listed twice, or a node inside another listed node, would
     * attribute the same dependencies to two rows; which row wins would depend on the input order, and a
     * containing node can even look cyclic with the node inside it.
     */
    private fun overlapping(nodes: List<HGNode>): Map<String, Any?>? {
        val hierarchy = graphService.model.hierarchy
        val listed = nodes.map { it.identifier }.toSet()
        val duplicates = nodes.groupingBy { it.identifier }.eachCount().filterValues { it > 1 }.keys.toList()
        val nested = nodes.distinctBy { it.identifier }.mapNotNull { node ->
            generateSequence(hierarchy.parentOf(node)) { hierarchy.parentOf(it) }
                .firstOrNull { it.identifier in listed }
                ?.let { ancestor -> linkedMapOf("node" to node.identifier, "inside" to ancestor.identifier) }
        }
        if (duplicates.isEmpty() && nested.isEmpty()) return null
        val problems = buildList {
            if (duplicates.isNotEmpty()) add("listed more than once: ${duplicates.joinToString()}")
            if (nested.isNotEmpty()) add("inside another listed node: ${nested.joinToString { "${it["node"]} in ${it["inside"]}" }}")
        }
        return mapOf(
            "error" to linkedMapOf(
                "code" to "INVALID_PARAMETER",
                "message" to "nodeIds must name disjoint subtrees; ${problems.joinToString("; ")}.",
                "duplicates" to duplicates,
                "nested" to nested,
                "recovery" to "List each id once, and keep either a containing node or the nodes inside it, not both."
            )
        )
    }

    /** `INVALID_NODE_KIND` for a method or field — the matrix is type-level — or `null` for any other node. */
    private fun memberRejected(node: HGNode): Map<String, Any?>? {
        val kind = node.kind
        if (kind != JavaNodeKind.METHOD && kind != JavaNodeKind.FIELD) return null
        val declaringType = graphService.model.hierarchy.parentOf(node)
        return mapOf(
            "error" to mapOf(
                "code" to "INVALID_NODE_KIND",
                "message" to "This tool operates on type-level dependencies. The node is a $kind, not a type.",
                "actual_kind" to kind.toString(),
                "declaring_type" to declaringType?.let { nodeRefFactory.minimalNodeRef(it) },
                "recovery" to "Pass the declaring type's id (${declaringType?.identifier}) instead."
            )
        )
    }

    private fun nodeNotFound(id: Long) = error(
        "NODE_NOT_FOUND", "No node with id $id exists in the graph.", "Use find_node to look up the correct node ID.",
    )

    private fun error(code: String, message: String, recovery: String): Map<String, Any?> =
        mapOf("error" to linkedMapOf("code" to code, "message" to message, "recovery" to recovery))

    companion object {
        /** The most node ids a matrix takes: its analysis is O(node_count). */
        const val MAX_NODES = 1000
    }
}
