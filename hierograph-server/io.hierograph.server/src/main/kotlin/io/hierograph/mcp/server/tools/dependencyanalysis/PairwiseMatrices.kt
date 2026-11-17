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
import io.hierograph.hierarchicalgraph.algorithms.GraphUtils
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
)

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
    /** The nodes in matrix order: the topological order when the matrix is acyclic. */
    val orderedNodes: List<HGNode> get() = dsm.orderedNodes

    /** The analysis over the whole matrix: counts, density, strongly connected components, topological order. */
    fun analysis(): LinkedHashMap<String, Any?> {
        val size = orderedNodes.size
        val possibleEdges = size * (size - 1)
        val density = if (possibleEdges > 0) Math.round(edges.size * 100.0 / possibleEdges) / 100.0 else 0.0
        val hasCycles = dsm.cycles.isNotEmpty()
        return linkedMapOf<String, Any?>(
            "node_count" to size,
            "edge_count" to edges.size, // unfiltered count, drives density
            "possible_edges" to possibleEdges,
            "density" to density,
            "has_cycles" to hasCycles,
            "strongly_connected_components" to dsm.cycles.filter { it.size >= 2 }.map { cycle -> cycle.map { it.identifier } },
        ).apply { if (!hasCycles) put("topological_order", orderedNodes.map { it.identifier }) }
    }

    /** The fact counts per kind of the type-level dependencies under [edge]. */
    fun kindsOf(edge: MatrixEdge): Map<DependencyKind, Int> {
        val coreDeps = hierarchy.getAggregatedDependency(orderedNodes[edge.fromIdx], orderedNodes[edge.toIdx])
            ?.coreDependencies.orEmpty()
        return GraphStoreDependencySource.kindsOf(coreDeps)
    }

    companion object {
        /** Matrix reading order: by row, then by column. */
        val DSM_ORDER: Comparator<MatrixEdge> = compareBy<MatrixEdge> { it.fromIdx }.thenBy { it.toIdx }

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
        val dsm = GraphUtils.createDependencyStructureMatrix(resolvedNodes, hierarchy)
        val orderedNodes = dsm.orderedNodes
        // computePairwiseAggregation yields i→j (i depends on j): every edge reads depender → depended-upon.
        val edges = GraphUtils.computePairwiseAggregation(orderedNodes, hierarchy).map { e ->
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
