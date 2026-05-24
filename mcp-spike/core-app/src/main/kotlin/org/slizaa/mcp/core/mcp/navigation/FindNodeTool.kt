package org.slizaa.mcp.core.mcp.navigation

import org.slizaa.mcp.core.HierarchicalGraphService
import org.slizaa.mcp.core.mcp.INodeRefFactory
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ISearchProvider
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `find_node`
 *
 * Resolves a name into node IDs by searching the graph via Neo4j.
 * Only nodes present in the in-memory hierarchical model are returned,
 * each as an enriched NodeRef.
 */
@Component
class FindNodeTool(
    private val graphService: HierarchicalGraphService,
    private val searchProvider: ISearchProvider,
    private val nodeRefFactory: INodeRefFactory
) {

    @Tool(
        name = "find_node",
        description = "[Discovery and navigation] " +
                "Look up nodes by name. This is the primary way to obtain node IDs " +
                "and should be the first tool called when the user mentions a specific " +
                "class, package, or module by name. " +
                "Searches by name or fully qualified name using case-insensitive substring matching. " +
                "Results include kind-appropriate metadata (member counts, modifiers, flags) " +
                "so you can inspect matches before proceeding. " +
                "Use kind_filter to narrow results when names are ambiguous across node types. " +
                "Accepts specific kinds (java.class, java.interface, java.enum, java.record, " +
                "java.annotation, java.method, java.field, java.package, java.module) " +
                "and group aliases (types, members, packages)."
    )
    fun findNode(
        @ToolParam(description = "Name or fragment to search for, e.g. 'ClusterService', 'payment.api'")
        name: String,
        @ToolParam(
            description = "Optional list of kind filters to restrict results. " +
                    "Accepts specific kinds (e.g. 'java.class') and group aliases ('types', 'members', 'packages').",
            required = false
        )
        kindFilter: List<String>?
    ): Map<String, Any?> {

        // ── validate kind_filter ───────────────────────────────────────
        if (kindFilter != null) {
            val invalid = kindFilter.filter { it !in VALID_KINDS && it !in GROUP_ALIASES }
            if (invalid.isNotEmpty()) {
                return mapOf(
                    "error" to mapOf(
                        "code" to "INVALID_KIND",
                        "message" to "Unknown kind${if (invalid.size > 1) "s" else ""}: ${invalid.joinToString(", ") { "'$it'" }}. " +
                                "Valid kinds: ${VALID_KINDS.joinToString(", ")}. " +
                                "Group aliases: ${GROUP_ALIASES.joinToString(", ")}.",
                        "invalid_values" to invalid,
                        "valid_kinds" to VALID_KINDS.toList(),
                        "valid_aliases" to GROUP_ALIASES.toList()
                    )
                )
            }
        }

        // ── stage 1: search via provider (Neo4j) ───────────────────────
        val candidates = searchProvider.search(name, kindFilter, SERVER_SIDE_CAP)

        // ── stage 2: filter to mapped nodes and enrich ─────────────────
        val results = candidates.mapNotNull { candidate ->
            val hgNode = graphService.rootNode.lookupNode(candidate.nodeId) ?: return@mapNotNull null
            nodeRefFactory.enrichedNodeRef(hgNode)
        }

        // ── assemble response ──────────────────────────────────────────
        return mapOf(
            "results" to results,
            "summary" to mapOf(
                "total" to results.size,
                "returned" to results.size
            )
        )
    }

    companion object {
        private const val SERVER_SIDE_CAP = 50

        private val VALID_KINDS = setOf(
            "java.module", "java.package",
            "java.class", "java.interface", "java.enum", "java.record", "java.annotation",
            "java.method", "java.field"
        )

        private val GROUP_ALIASES = setOf("types", "members", "packages")
    }
}
