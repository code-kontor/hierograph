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

import io.hierograph.hierarchicalgraph.algorithms.GraphUtils
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.mapping.JavaEdgeAttributes
import io.hierograph.server.mapping.GraphStoreDependencySource
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.server.graphservice.pagination.DataHashProvider
import io.hierograph.mcp.server.pagination.PageResult
import io.hierograph.mcp.server.pagination.PaginationSpec
import io.hierograph.mcp.server.pagination.Paginator
import io.hierograph.mcp.server.pagination.QueryHash
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `pairwise_dependencies`
 *
 * Returns the dependency matrix among a set of subtrees — a global structural summary (density, cycles,
 * SCCs, topological order) computed over the whole node set, plus a paginated edge list. Every page's
 * summary carries the page counts (total, returned, truncated); the matrix analysis is `O(node_count)`
 * and is returned on the first page only; the edge list is `O(edges)` and is paginated
 * (and can be thinned with `minWeight`). Entirely in-memory.
 */
@Component
class PairwiseDependenciesTool(
    private val graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory,
    private val dataHashProvider: DataHashProvider
) {

    /**
     * An aggregated DSM edge held in typed form so it can be ordered, `minWeight`-filtered, and paged
     * before being mapped to the wire shape. `fromIdx`/`toIdx` are positions in the matrix's node order
     * (equal to the topological order when acyclic), which makes "dsm" ordering a plain index sort.
     */
    private data class DsmEdge(
        val fromId: Any,
        val toId: Any,
        val fromIdx: Int,
        val toIdx: Int,
        val weight: Int,
        val typePairCount: Int,
        val attributesBitmap: Int
    )

    @Tool(
        name = "pairwise_dependencies",
        description = "[Dependency analysis] " +
                "Dependency matrix (DSM / coupling matrix) among a set of subtrees. Returns two things: " +
                "(1) a small global summary — density, cycle detection, strongly connected components, " +
                "topological order — computed over the entire node set, and (2) a paginated edge list. " +
                "READ THE SUMMARY FIRST: it answers most architectural questions (is it acyclic? what are " +
                "the layers? where are the cycles?) in a few hundred bytes, with no need to fetch edges at " +
                "all. Only page through the edge list when you need specific pairwise weights. " +
                "WARNING: the edge list can be large — a full all-modules query is often hundreds of edges " +
                "(~300 bytes each), and a full page of 166 fills the ~50 KB response budget. Do NOT fetch it " +
                "all at once. Instead: keep limit small (default 25, stay <= 50) and walk next_cursor " +
                "only as far as needed; raise minWeight to drop noise (a threshold like 100+ usually " +
                "leaves a handful of edges); and use edgeSort='weight_desc' so the first small page " +
                "already holds the strongest couplings. The node set you pass is bounded only by a " +
                "generous soft cap, so passing many modules is fine — it is the edge OUTPUT, not the " +
                "input, that you must bound. " +
                "For one-directional or asymmetric queries use aggregated_dependencies; for type/method-" +
                "level evidence behind a single pair use outgoing_dependencies or incoming_dependencies."
    )
    fun pairwiseDependencies(
        @ToolParam(description = "List of subtree IDs to analyze pairwise (2+; typically modules or packages).")
        nodeIds: List<Long>,
        @ToolParam(
            description = "Edge ordering for the paginated list: 'dsm' (default, matrix reading order) " +
                    "or 'weight_desc' (heaviest coupling first). Does not affect the summary.",
            required = false
        )
        edgeSort: String?,
        @ToolParam(
            description = "Drop edges with weight below this from the edge list (server-side). " +
                    "Default 1 = no filtering (returns everything — large). Raise it (e.g. 50-100) " +
                    "to keep responses small and surface only meaningful coupling. " +
                    "Does not affect the summary analytics.",
            required = false
        )
        minWeight: Int?,
        @ToolParam(
            description = "Max edges per page (1-166, default 25). Keep this small — a full page fills the " +
                    "~50 KB response budget. Increase only when you know the filtered edge count is low.",
            required = false
        )
        limit: Int?,
        @ToolParam(
            description = "Opaque pagination cursor from a previous response's next_cursor. Omit for " +
                    "the first page; the matrix analysis in the summary and the nodes map are returned on the first " +
                    "page only, the page counts (summary.total, returned, truncated) on every page. " +
                    "Keep the other parameters identical when continuing.",
            required = false
        )
        cursor: String?
    ): Map<String, Any?> {

        // ── validate node-set size (soft cap bounds the O(N) summary + analytics build) ──
        if (nodeIds.size < 2) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INPUT_TOO_SMALL",
                    "message" to "pairwise_dependencies requires at least 2 node IDs, got ${nodeIds.size}.",
                    "recovery" to "For single-pair queries, use aggregated_dependencies instead."
                )
            )
        }
        if (nodeIds.size > MAX_NODES) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INPUT_TOO_LARGE",
                    "message" to "pairwise_dependencies accepts at most $MAX_NODES node IDs " +
                            "(the summary — SCCs and topological order — is O(node_count)), got ${nodeIds.size}.",
                    "node_count" to nodeIds.size,
                    "max_nodes" to MAX_NODES,
                    "recovery" to "Narrow the node set, or use aggregated_dependencies for an asymmetric " +
                            "slice. The edge list itself is paginated, so node count is the only thing to reduce."
                )
            )
        }

        // ── validate edgeSort ─────────────────────────────────────────
        val effectiveSort = edgeSort ?: "dsm"
        if (effectiveSort !in EDGE_SORTS) {
            return invalidParam(
                "Invalid edgeSort: '$effectiveSort'. Must be 'dsm' or 'weight_desc'.",
                "Use 'dsm' for matrix reading order or 'weight_desc' for heaviest-first."
            )
        }
        val effectiveMinWeight = (minWeight ?: 1).coerceAtLeast(1)

        // ── resolve and validate nodes ─────────────────────────────────
        val hierarchy = graphService.model.hierarchy
        val resolvedNodes = ArrayList<HGNode>(nodeIds.size)
        for (id in nodeIds) {
            val node = graphService.model.lookupNode(id) ?: return nodeNotFound(id)
            validateNodeKind(node)?.let { return it }
            resolvedNodes.add(node)
        }
        overlapping(resolvedNodes)?.let { return it }

        // ── build DSM; analytics are computed over the FULL node set ────
        val dsm = GraphUtils.createDependencyStructureMatrix(resolvedNodes, hierarchy)
        val orderedNodes = dsm.orderedNodes
        val size = orderedNodes.size

        // ── build the complete (unfiltered) edge list — one linear pass ─
        val aggregated = GraphUtils.computePairwiseAggregation(orderedNodes, hierarchy)
        // computePairwiseAggregation yields i→j (i depends on j): every edge reads depender → depended-upon.
        val allEdges = aggregated.map { e ->
            DsmEdge(
                fromId = orderedNodes[e.fromIndex].identifier,
                toId = orderedNodes[e.toIndex].identifier,
                fromIdx = e.fromIndex,
                toIdx = e.toIndex,
                weight = e.weight,
                typePairCount = e.typePairCount,
                attributesBitmap = e.attributesBitmap
            )
        }
        val edgeCount = allEdges.size // unfiltered — drives density

        // ── order (stable, total), then apply minWeight ───────────────
        val comparator = when (effectiveSort) {
            "weight_desc" -> compareByDescending<DsmEdge> { it.weight }.thenBy { it.fromIdx }.thenBy { it.toIdx }
            else -> compareBy<DsmEdge> { it.fromIdx }.thenBy { it.toIdx } // "dsm"
        }
        val orderedFiltered = allEdges
            .filter { it.weight >= effectiveMinWeight }
            .sortedWith(comparator)

        // ── paginate the edge list ─────────────────────────────────────
        val queryHash = QueryHash.of(
            mapOf(
                "nodeIds" to nodeIds, // verbatim: order can influence tie-broken edge order
                "edgeSort" to effectiveSort,
                "minWeight" to effectiveMinWeight
            )
        )
        val page = when (val result = Paginator.paginate(
            allItems = orderedFiltered,
            spec = PAGINATION,
            queryHash = queryHash,
            dataHash = dataHashProvider.dataHash,
            cursor = cursor,
            limit = limit
        )) {
            is PageResult.Failed -> return result.error.toResponse()
            is PageResult.Page -> result
        }

        val edges = page.items.map { e ->
            // The kinds of the type-level dependencies under this pair, for the returned edges only.
            val coreDeps = hierarchy.getAggregatedDependency(orderedNodes[e.fromIdx], orderedNodes[e.toIdx])?.coreDependencies.orEmpty()
            linkedMapOf<String, Any?>(
                "from" to e.fromId,
                "to" to e.toId,
                "weight" to e.weight,
                "type_pair_count" to e.typePairCount,
                "attributes" to JavaEdgeAttributes.toMap(e.attributesBitmap),
                "kinds" to DependencyKind.toMap(GraphStoreDependencySource.kindsOf(coreDeps)),
            )
        }

        // ── assemble: page counts on every page; nodes + matrix analysis on the FIRST page only ──
        val response = linkedMapOf<String, Any?>()
        val summary = linkedMapOf<String, Any?>(
            "total" to page.total,          // post-minWeight edges, across all pages
            "returned" to page.returned,
            "truncated" to page.truncated
        )
        if (cursor == null) {
            val possibleEdges = size * (size - 1)
            val density = if (possibleEdges > 0)
                Math.round(edgeCount * 100.0 / possibleEdges) / 100.0
            else 0.0
            val hasCycles = dsm.cycles.isNotEmpty()
            val sccs = dsm.cycles.filter { it.size >= 2 }.map { cycle -> cycle.map { it.identifier } }

            val nodes = linkedMapOf<String, Any>()
            for (node in orderedNodes) {
                nodeRefFactory.putSlimNode(nodes, node)
            }

            summary["node_count"] = size
            summary["edge_count"] = edgeCount  // unfiltered count, drives density
            summary["possible_edges"] = possibleEdges
            summary["density"] = density
            summary["has_cycles"] = hasCycles
            summary["strongly_connected_components"] = sccs
            if (!hasCycles) {
                summary["topological_order"] = orderedNodes.map { it.identifier }
            }

            response["nodes"] = nodes
        }
        response["summary"] = summary
        response["edges"] = edges
        // next_cursor is present iff more edges follow this page (omitted, not null, on the last page).
        page.nextCursor?.let { response["next_cursor"] = it }
        return response
    }

    // ── helpers ─────────────────────────────────────────────────────────

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

    private fun invalidParam(message: String, recovery: String): Map<String, Any?> = mapOf(
        "error" to mapOf(
            "code" to "INVALID_PARAMETER",
            "message" to message,
            "recovery" to recovery
        )
    )

    private fun validateNodeKind(node: HGNode): Map<String, Any?>? {
        val kind = node.kind
        if (kind == JavaNodeKind.METHOD || kind == JavaNodeKind.FIELD) {
            val declaringType = graphService.model.hierarchy.parentOf(node)
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_NODE_KIND",
                    "message" to "This tool operates on type-level dependencies. " +
                            "The node is a $kind, not a type.",
                    "actual_kind" to kind.toString(),
                    "declaring_type" to if (declaringType != null)
                        nodeRefFactory.minimalNodeRef(declaringType) else null,
                    "recovery" to "Pass the declaring type's id (${declaringType?.identifier}) instead."
                )
            )
        }
        return null
    }

    private fun nodeNotFound(id: Long) = mapOf<String, Any?>(
        "error" to mapOf(
            "code" to "NODE_NOT_FOUND",
            "message" to "No node with id $id exists in the graph.",
            "recovery" to "Use find_node to look up the correct node ID."
        )
    )

    companion object {
        private const val MAX_NODES = 1000
        private val EDGE_SORTS = setOf("dsm", "weight_desc")

        /** Pagination policy for pairwise_dependencies (~300 bytes/edge with its kinds): default 25, server cap 166. */
        private val PAGINATION = PaginationSpec(tool = "pairwise_dependencies", defaultLimit = 25, maxLimit = 166, bytesPerItem = 300)
    }
}
