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

import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.mapping.JavaEdgeAttributes
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.server.graphservice.pagination.DataHashProvider
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
    graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory,
    private val dataHashProvider: DataHashProvider
) {

    /** Validates the node set and computes the matrix. */
    private val matrices = PairwiseMatrices(graphService, nodeRefFactory)

    @Tool(
        name = "pairwise_dependencies",
        description = "[Dependency analysis] " +
                "Dependency matrix (DSM / coupling matrix) among a set of subtrees. Returns two things: " +
                "(1) a small global summary — density, cycle detection, strongly connected components, " +
                "node_order (dependers first, ALWAYS present: render the matrix in this order, never in one of " +
                "your own) and, for a cyclic set, the back edges that close the cycles (back_edges, heaviest first, " +
                "with back_edge_weight), the 2-cycles (mutual_dependencies) and order_quality — computed over the " +
                "entire node set, and (2) a paginated edge list, where each back edge carries back=true. " +
                "READ THE SUMMARY FIRST: it answers most architectural questions (is it acyclic? what are " +
                "the layers? where are the cycles?) in a few hundred bytes, with no need to fetch edges at " +
                "all. Only page through the edge list when you need specific pairwise weights. " +
                "WARNING: the edge list can be large — a full all-modules query is often hundreds of edges " +
                "(~300 bytes each), and every page is kept within the ~50 KB response budget, so a large list " +
                "takes several pages. Do NOT fetch it all at once. Instead: keep limit small (default 25, stay " +
                "<= 50) and walk next_cursor only as far as needed; raise minWeight to drop noise (a threshold like 100+ usually " +
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
            description = "Max edges per page (1-500, default 25). A page never exceeds the ~50 KB response " +
                    "budget: when the edges would not fit, the page holds fewer and next_cursor continues after them.",
            required = false
        )
        limit: Int?,
        @ToolParam(
            description = "Opaque pagination cursor from a previous response's next_cursor. Omit for " +
                    "the first page; the matrix analysis in the summary and the nodes map are returned on the first " +
                    "page only, the page counts (summary.total, returned, truncated) on every page. " +
                    "To continue, passing the cursor alone is enough: the server remembers the other arguments. " +
                    "If you pass them, they must be identical to the first call's.",
            required = false
        )
        cursor: String?,
        @ToolParam(
            description = "Whether the first page carries the nodes map (name and kind of every id). Default true. " +
                    "Pass false when you already know the nodes — it is the largest part of the first page " +
                    "for a large node set.",
            required = false
        )
        includeNodes: Boolean? = null,
    ): Map<String, Any?> {

        // ── validate edgeSort ─────────────────────────────────────────
        val effectiveSort = edgeSort ?: "dsm"
        if (effectiveSort !in EDGE_SORTS) {
            return invalidParam(
                "Invalid edgeSort: '$effectiveSort'. Must be 'dsm' or 'weight_desc'.",
                "Use 'dsm' for matrix reading order or 'weight_desc' for heaviest-first."
            )
        }
        val effectiveMinWeight = (minWeight ?: 1).coerceAtLeast(1)

        // ── validate the node set and build the matrix; analytics cover the FULL node set ──
        val matrix = when (val result = matrices.compute("pairwise_dependencies", nodeIds)) {
            is PairwiseMatrices.Result.Rejected -> return result.response
            is PairwiseMatrices.Result.Computed -> result.matrix
        }
        val orderedNodes = matrix.orderedNodes

        // ── order (stable, total), then apply minWeight ───────────────
        val orderedFiltered = matrix.edges
            .filter { it.weight >= effectiveMinWeight }
            .sortedWith(if (effectiveSort == "weight_desc") PairwiseMatrix.WEIGHT_ORDER else PairwiseMatrix.DSM_ORDER)

        // ── the first page's nodes map and matrix analysis, computed once (the page is rendered several times) ──
        val nodes = if (cursor == null && includeNodes != false) {
            linkedMapOf<String, Any>().also { map -> for (node in orderedNodes) nodeRefFactory.putSlimNode(map, node) }
        } else {
            null
        }
        val analysis = if (cursor == null) matrix.analysis() else null

        // ── paginate the edge list, within the response budget ─────────
        val queryHash = QueryHash.of(
            mapOf(
                "nodeIds" to nodeIds, // verbatim: order can influence tie-broken edge order
                "edgeSort" to effectiveSort,
                "minWeight" to effectiveMinWeight
            )
        )
        return Paginator.respond(
            allItems = orderedFiltered,
            spec = PAGINATION,
            queryHash = queryHash,
            dataHash = dataHashProvider.dataHash,
            cursor = cursor,
            limit = limit,
            tooLarge = "Pass includeNodes=false (the nodes map names every id you passed), or pass fewer nodeIds.",
        ) { page ->
            val edges = page.items.map { e ->
                linkedMapOf<String, Any?>(
                    "from" to e.fromId,
                    "to" to e.toId,
                    "weight" to e.weight,
                    "type_pair_count" to e.typePairCount,
                    "attributes" to JavaEdgeAttributes.toMap(e.attributesBitmap),
                    // The kinds of the type-level dependencies under this pair, for the returned edges only.
                    "kinds" to DependencyKind.toMap(matrix.kindsOf(e)),
                ).apply { if (e.isBack) put("back", true) }
            }
            // ── assemble: page counts on every page; nodes + matrix analysis on the FIRST page only ──
            val response = linkedMapOf<String, Any?>()
            nodes?.let { response["nodes"] = it }
            response["summary"] = linkedMapOf<String, Any?>(
                "total" to page.total,          // post-minWeight edges, across all pages
                "returned" to page.returned,
                "truncated" to page.truncated
            ).apply { analysis?.let { putAll(it) } }
            response["edges"] = edges
            // next_cursor is present iff more edges follow this page (omitted, not null, on the last page).
            page.nextCursor?.let { response["next_cursor"] = it }
            response
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun invalidParam(message: String, recovery: String): Map<String, Any?> = mapOf(
        "error" to mapOf(
            "code" to "INVALID_PARAMETER",
            "message" to message,
            "recovery" to recovery
        )
    )

    companion object {
        private val EDGE_SORTS = setOf("dsm", "weight_desc")

        /** Pagination policy for pairwise_dependencies: default 25, server cap 500; the response budget trims a page further. */
        private val PAGINATION = PaginationSpec(tool = "pairwise_dependencies", defaultLimit = 25, maxLimit = 500)
    }
}
