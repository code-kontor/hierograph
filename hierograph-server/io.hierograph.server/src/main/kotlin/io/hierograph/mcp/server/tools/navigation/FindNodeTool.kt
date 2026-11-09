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

import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.server.graphservice.NodeSearch
import io.hierograph.mcp.server.tools.KindFilter
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `find_node`
 *
 * Resolves a name into node IDs by searching the in-memory hierarchical model
 * with case-insensitive substring matching on name and fully-qualified name (see [NodeSearch],
 * shared with the GraphQL `findNodes` field). Each match is returned as an enriched NodeRef.
 */
@Component
class FindNodeTool(
    private val nodeSearch: NodeSearch,
    private val nodeRefFactory: INodeRefFactory
) {

    @Tool(
        name = "find_node",
        description = "[Discovery and navigation] " +
                "Look up nodes by name. This is the primary way to obtain node IDs " +
                "and should be the first tool called when the user mentions a specific " +
                "class, package, or module by name. " +
                "Searches by name or fully qualified name using case-insensitive substring matching. " +
                "Results include kind-appropriate metadata (counts and modifiers) " +
                "so you can inspect matches before proceeding. " +
                "Use kindFilter to narrow results when names are ambiguous across node types. " +
                "Accepts specific kinds (java.class, java.interface, java.enum, java.record, " +
                "java.annotation, java.method, java.field, java.package, java.module, external.type) " +
                "and group aliases (types, members, packages). " +
                "Returns at most the 50 best matches; summary.total counts all matches and " +
                "summary.truncated is true when more exist, in which case use a longer name or a kindFilter."
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

        // ── validate kindFilter ────────────────────────────────────────
        (KindFilter.parse(kindFilter) as? KindFilter.Invalid)?.let { return KindFilter.invalidResponse(it) }

        // ── search (shared with the GraphQL findNodes field) ───────────
        val found = nodeSearch.find(name, kindFilter, SERVER_SIDE_CAP)
        val results = found.nodes.map { nodeRefFactory.enrichedNodeRef(it) }
        return mapOf(
            "results" to results,
            "summary" to mapOf(
                "total" to found.total,
                "returned" to results.size,
                "truncated" to (found.total > results.size)
            )
        )
    }

    companion object {
        private const val SERVER_SIDE_CAP = 50
    }
}
