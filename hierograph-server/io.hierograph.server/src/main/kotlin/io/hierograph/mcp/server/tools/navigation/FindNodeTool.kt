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
                "Results include kind-appropriate metadata (member counts, modifiers, flags) " +
                "so you can inspect matches before proceeding. " +
                "Use kindFilter to narrow results when names are ambiguous across node types. " +
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
        val invalid = nodeSearch.invalidKinds(kindFilter)
        if (invalid.isNotEmpty()) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_KIND",
                    "message" to "Unknown kind${if (invalid.size > 1) "s" else ""}: ${invalid.joinToString(", ") { "'$it'" }}. " +
                            "Valid kinds: ${nodeSearch.validKindValues.joinToString(", ")}. " +
                            "Group aliases: ${nodeSearch.validAliases.joinToString(", ")}.",
                    "invalid_values" to invalid,
                    "valid_kinds" to nodeSearch.validKindValues,
                    "valid_aliases" to nodeSearch.validAliases
                )
            )
        }

        // ── search (shared with the GraphQL findNodes field) ───────────
        val results = nodeSearch.search(name, kindFilter, SERVER_SIDE_CAP).map { nodeRefFactory.enrichedNodeRef(it) }
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
    }
}
