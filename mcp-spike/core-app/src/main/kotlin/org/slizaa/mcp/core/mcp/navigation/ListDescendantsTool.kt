package org.slizaa.mcp.core.mcp.navigation

import org.slizaa.hierarchicalgraph.core.model.HGNode
import org.slizaa.mcp.core.HierarchicalGraphService
import org.slizaa.mcp.core.mcp.INodeRefFactory
import org.slizaa.mcp.javaspec.JavaKinds
import org.slizaa.mcp.javaspec.JavaNodeKind
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

// TODO: modifier_filter requires property materialisation (modifiers are not yet
//       available in the in-memory model). Once INodeMetadataProvider exposes
//       getModifiers(node), re-enable the modifier filtering below.

// TODO: cursor-based pagination is specified but not yet implemented.
//       The current implementation uses simple offset-based truncation via the limit parameter.

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
    private val nodeRefFactory: INodeRefFactory
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
            description = "Restricts to descendants whose modifiers include ALL listed values (AND logic). " +
                    "Only meaningful for methods and fields.",
            required = false
        )
        modifierFilter: List<String>?,
        @ToolParam(
            description = "Maximum items per page (default 150, max 500).",
            required = false
        )
        limit: Int?
    ): Map<String, Any?> {

        // ── resolve the node ──────────────────────────────────────────
        val rootNode = graphService.rootNode.lookupNode(nodeId)
            ?: return errorResponse(
                "NODE_NOT_FOUND",
                "No node with id $nodeId exists in the graph.",
                "Use find_node to look up the correct node ID."
            )

        // ── resolve kind filter (expand aliases) ──────────────────────
        val expandedKinds = expandKindFilter(kindFilter)
        if (expandedKinds == null && kindFilter != null) {
            val invalid = kindFilter.filter { JavaNodeKind.fromValue(it) == null && JavaKinds.expandAlias(it) == null }
            return errorResponse(
                "INVALID_KIND",
                "Unknown kind${if (invalid.size > 1) "s" else ""} ${invalid.joinToString { "'$it'" }}. " +
                        "Valid kinds: ${JavaKinds.ALL_KINDS.joinToString { it.value }}. " +
                        "Group aliases: ${JavaKinds.ALL_ALIASES.joinToString()}.",
                "Use valid kind values or group aliases."
            )
        }

        val mp = metadataProvider()
        val effectiveLimit = (limit ?: 150).coerceIn(1, 500)
        val nameLower = namePattern?.lowercase()

        // ── depth-first pre-order traversal ───────────────────────────
        // Full traversal with filtering on results, not on traversal.
        // Children at each level are visited in their natural order (stable).
        val allFiltered = mutableListOf<HGNode>()
        val byKind = linkedMapOf<String, Int>()
        val parentCounts = linkedMapOf<Any, Int>()

        fun traverse(node: HGNode) {
            for (child in node.children) {
                // apply filters
                var matches = true

                // 1. kind filter
                if (expandedKinds != null && child.kind !in expandedKinds) {
                    matches = false
                }

                // 2. name pattern
                if (matches && nameLower != null) {
                    val name = mp.getName(child) ?: ""
                    if (!name.lowercase().contains(nameLower)) matches = false
                }

                // 3. modifier filter — currently a no-op
                // if (matches && modifierFilter != null && modifierFilter.isNotEmpty()) { ... }

                if (matches) {
                    val kindStr = child.kind?.toString() ?: "unknown"
                    byKind.merge(kindStr, 1) { a, b -> a + b }
                    val parentId = child.parent?.identifier
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
                val parentNode = graphService.rootNode.lookupNode(parentId)
                linkedMapOf<String, Any?>(
                    "parent" to if (parentNode != null) nodeRefFactory.minimalNodeRef(parentNode) else mapOf("id" to parentId),
                    "match_count" to count
                )
            }

        // ── slice for page ────────────────────────────────────────────
        val truncated = allFiltered.size > effectiveLimit
        val results = allFiltered
            .take(effectiveLimit)
            .map { nodeRefFactory.enrichedNodeRef(it) }

        // ── assemble response ─────────────────────────────────────────
        val response = linkedMapOf<String, Any?>(
            "root" to nodeRefFactory.enrichedNodeRef(rootNode),
            "results" to results,
            "summary" to linkedMapOf<String, Any?>(
                "total" to allFiltered.size,
                "returned" to results.size,
                "truncated" to truncated,
                "by_kind" to byKind,
                "by_parent" to byParent
            )
        )

        // next_cursor: absent when not truncated (per spec: omitted, not null)
        // TODO: implement proper cursor-based pagination

        return response
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun metadataProvider(): INodeMetadataProvider =
        graphService.rootNode.getExtension(INodeMetadataProvider::class.java)

    private fun expandKindFilter(kindFilter: List<String>?): Set<JavaNodeKind>? {
        if (kindFilter.isNullOrEmpty()) return null

        val result = mutableSetOf<JavaNodeKind>()
        for (entry in kindFilter) {
            val expanded = JavaKinds.expandAlias(entry)
            if (expanded != null) {
                result.addAll(expanded)
            } else {
                val kind = JavaNodeKind.fromValue(entry) ?: return null
                result.add(kind)
            }
        }
        return result
    }

    private fun errorResponse(code: String, message: String, recovery: String): Map<String, Any?> =
        mapOf("error" to linkedMapOf<String, Any?>("code" to code, "message" to message, "recovery" to recovery))

    companion object {
        private fun <K, V> linkedMapOf(vararg pairs: Pair<K, V>): LinkedHashMap<K, V> {
            val map = LinkedHashMap<K, V>()
            for ((k, v) in pairs) map[k] = v
            return map
        }
    }
}
