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
package io.hierograph.mcp.server.tools.navigation

import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.mcp.server.tools.KindFilter
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.server.graphservice.pagination.DataHashProvider
import io.hierograph.mcp.server.pagination.PageResult
import io.hierograph.mcp.server.pagination.PaginationSpec
import io.hierograph.mcp.server.pagination.Paginator
import io.hierograph.mcp.server.pagination.QueryHash
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `list_descendants`
 *
 * Returns all descendants of a node matching the specified filters, across the entire
 * subtree in a single call. Each descendant is an enriched NodeRef with kind-appropriate
 * metadata. Results are paginated when large.
 */
@Component
class ListDescendantsTool(
    private val graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory,
    private val dataHashProvider: DataHashProvider
) {

    @Tool(
        name = "list_descendants",
        description = "[Discovery and navigation] " +
                "Returns descendants matching specified filters across the entire subtree in a single call. " +
                "This is the right tool for any 'show me all X in subtree Y' question. " +
                "Supports filtering by kind, name pattern, and modifiers. " +
                "The summary fields (by_kind, by_parent) often answer the question without " +
                "needing to enumerate individual results. " +
                "Results are paginated when large — use next_cursor to retrieve more, " +
                "or narrow the query with tighter filters. " +
                "If you find yourself calling list_children more than once or twice to walk a tree, " +
                "use this tool instead."
    )
    fun listDescendants(
        @ToolParam(description = "Root node ID — descendants of this node will be searched. The root itself is not included in results.")
        nodeId: Long,
        @ToolParam(
            description = "Restricts results to specific kinds. Accepts specific kind values " +
                    "(e.g. 'java.class', 'java.method') and group aliases ('types', 'members', 'packages'). " +
                    "Mixing is allowed. The filter applies to results, not traversal — the full subtree " +
                    "is always traversed. Omit to return all descendants.",
            required = false
        )
        kindFilter: List<String>?,
        @ToolParam(
            description = "Case-insensitive substring match against descendant names.",
            required = false
        )
        namePattern: String?,
        @ToolParam(
            description = "Restricts to methods and fields whose modifiers include ALL listed values (AND logic); " +
                    "other descendants are excluded while the filter is set. Values: public, protected, private, " +
                    "package-private, static, final, abstract, synchronized, native, default, transient, volatile, " +
                    "bridge, synthetic.",
            required = false
        )
        modifierFilter: List<String>?,
        @ToolParam(
            description = "Maximum items per page (default 150, max 200; larger values are clamped).",
            required = false
        )
        limit: Int?,
        @ToolParam(
            description = "Opaque pagination cursor from a previous response's next_cursor. " +
                    "Pass it to retrieve the next page; omit to start from the first page. " +
                    "When continuing, keep the other parameters identical to the original call. " +
                    "If the result set is larger than you need, prefer narrowing the query " +
                    "(tighter kind/name filters or a smaller subtree) over paginating through all of it.",
            required = false
        )
        cursor: String?
    ): Map<String, Any?> {

        // ── resolve the node ──────────────────────────────────────────
        val rootNode = graphService.model.lookupNode(nodeId)
            ?: return errorResponse(
                "NODE_NOT_FOUND",
                "No node with id $nodeId exists in the graph.",
                "Use find_node to look up the correct node ID."
            )

        // ── resolve kind filter (expand aliases) ──────────────────────
        val expandedKinds = when (val parsed = KindFilter.parse(kindFilter)) {
            null -> null
            is KindFilter.Valid -> parsed.kinds
            is KindFilter.Invalid -> return KindFilter.invalidResponse(parsed)
        }

        // ── resolve modifier filter ───────────────────────────────────
        val modifiers = when (val parsed = ModifierFilter.parse(modifierFilter)) {
            null -> null
            is ModifierFilter.Valid -> parsed.filter
            is ModifierFilter.Invalid -> return ModifierFilter.invalidResponse(parsed.values)
        }

        val nameLower = namePattern?.lowercase()

        // Query hash covers the parameters that define the result set — not limit or cursor.
        // Set-like filters are sorted so a mere reordering is not seen as a different query.
        val queryHash = QueryHash.of(
            mapOf(
                "nodeId" to nodeId,
                "kindFilter" to expandedKinds?.map { it.toString() }?.sorted(),  // aliases expanded
                "namePattern" to nameLower,                                       // matching ignores case
                "modifierFilter" to ModifierFilter.canonical(modifierFilter)
            )
        )

        // ── depth-first pre-order traversal ───────────────────────────
        // Full traversal with filtering on results, not on traversal.
        // Children at each level are visited in a stable, deterministic order — by qualified name,
        // with the node identifier as a tiebreaker — so the result sequence is reproducible and
        // pagination cursors remain valid across calls.
        val hierarchy = graphService.model.hierarchy
        val allFiltered = mutableListOf<HGNode>()
        val byKind = linkedMapOf<String, Int>()
        val parentCounts = linkedMapOf<Any, Int>()

        fun traverse(node: HGNode) {
            val orderedChildren = hierarchy.childrenOf(node).sortedWith(
                CHILD_ORDER
            )
            for (child in orderedChildren) {
                // apply filters
                var matches = true

                // 1. kind filter
                if (expandedKinds != null && child.kind !in expandedKinds) {
                    matches = false
                }

                // 2. name pattern
                if (matches && nameLower != null) {
                    val name = NodeMetadata.getName(child) ?: ""
                    if (!name.lowercase().contains(nameLower)) matches = false
                }

                // 3. modifier filter (methods and fields only)
                if (matches && modifiers != null && !modifiers.matches(child)) matches = false

                if (matches) {
                    val kindStr = child.kind?.toString() ?: "unknown"
                    byKind.merge(kindStr, 1) { a, b -> a + b }
                    val parentId = hierarchy.parentOf(child)?.identifier
                    if (parentId != null) {
                        parentCounts.merge(parentId, 1) { a, b -> a + b }
                    }
                    allFiltered.add(child)
                }

                // always continue traversal into children
                traverse(child)
            }
        }
        traverse(rootNode)

        // ── build by_parent summary (top 10) ──────────────────────────
        val byParent = parentCounts.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (parentId, count) ->
                val parentNode = graphService.model.lookupNode(parentId)
                linkedMapOf<String, Any?>(
                    "parent" to if (parentNode != null) nodeRefFactory.minimalNodeRef(parentNode) else mapOf("id" to parentId),
                    "match_count" to count
                )
            }

        // ── paginate ──────────────────────────────────────────────────
        val pageResult = Paginator.paginate(
            allItems = allFiltered,
            spec = PAGINATION,
            queryHash = queryHash,
            dataHash = dataHashProvider.dataHash,
            cursor = cursor,
            limit = limit
        )
        val page = when (pageResult) {
            is PageResult.Failed -> return pageResult.error.toResponse()
            is PageResult.Page -> pageResult
        }

        val results = page.items.map { nodeRefFactory.enrichedNodeRef(it) }

        // ── assemble response ─────────────────────────────────────────
        val response = linkedMapOf<String, Any?>(
            "root" to nodeRefFactory.enrichedNodeRef(rootNode),
            "results" to results,
            "summary" to linkedMapOf<String, Any?>(
                "total" to page.total,
                "returned" to page.returned,
                "truncated" to page.truncated,
                "by_kind" to byKind,
                "by_parent" to byParent
            )
        )

        // next_cursor is present iff more results follow this page (omitted, not null, on the last page).
        page.nextCursor?.let { response["next_cursor"] = it }

        return response
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun errorResponse(code: String, message: String, recovery: String): Map<String, Any?> =
        mapOf("error" to linkedMapOf<String, Any?>("code" to code, "message" to message, "recovery" to recovery))

    companion object {
        /** Pagination policy for list_descendants (~250 bytes/item): default 150, cap 200 (the budget's fill). */
        private val PAGINATION = PaginationSpec(tool = "list_descendants", defaultLimit = 150, maxLimit = 200, bytesPerItem = 250)

        private fun <K, V> linkedMapOf(vararg pairs: Pair<K, V>): LinkedHashMap<K, V> {
            val map = LinkedHashMap<K, V>()
            for ((k, v) in pairs) map[k] = v
            return map
        }
    }
}
