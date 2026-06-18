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

import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.mcp.server.core.HierarchicalGraphService
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.javaspec.JavaNodeKind
import io.hierograph.mcp.server.core.INodeRefFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `find_node`
 *
 * Resolves a name into node IDs by searching the in-memory hierarchical model
 * with case-insensitive substring matching on name and fully-qualified name.
 * Each match is returned as an enriched NodeRef.
 */
@Component
class FindNodeTool(
    private val graphService: HierarchicalGraphService,
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
            val validKindValues = JavaKinds.ALL_KINDS.map { it.value }.toSet()
            val invalid = kindFilter.filter { it !in validKindValues && it !in JavaKinds.ALL_ALIASES }
            if (invalid.isNotEmpty()) {
                return mapOf(
                    "error" to mapOf(
                        "code" to "INVALID_KIND",
                        "message" to "Unknown kind${if (invalid.size > 1) "s" else ""}: ${invalid.joinToString(", ") { "'$it'" }}. " +
                                "Valid kinds: ${validKindValues.joinToString(", ")}. " +
                                "Group aliases: ${JavaKinds.ALL_ALIASES.joinToString(", ")}.",
                        "invalid_values" to invalid,
                        "valid_kinds" to validKindValues.toList(),
                        "valid_aliases" to JavaKinds.ALL_ALIASES.toList()
                    )
                )
            }
        }

        // ── scan the precomputed search index ──────────────────────────
        // Match against name and fqn (case-insensitive substring), then rank by match quality:
        // exact name (0), exact fqn (1), name prefix (2), substring elsewhere (3); ties broken by
        // shorter fqn. With no kind filter, restrict to structural nodes (modules, packages, types) —
        // methods and fields are only searched when explicitly requested via kind_filter.
        val expandedKinds = expandKindFilter(kindFilter)
        val kindMatches: (HGNode) -> Boolean = when (expandedKinds) {
            null -> { node -> node.kind != JavaNodeKind.METHOD && node.kind != JavaNodeKind.FIELD }
            else -> { node -> node.kind in expandedKinds }
        }
        val query = name.lowercase()

        val matches = ArrayList<RankedNode>()
        for (entry in graphService.searchIndex.entries) {
            if (!kindMatches(entry.node)) continue
            if (!entry.nameLower.contains(query) && !entry.fqnLower.contains(query)) continue
            val rank = when {
                entry.nameLower == query -> 0
                entry.fqnLower == query -> 1
                entry.nameLower.startsWith(query) -> 2
                else -> 3
            }
            matches.add(RankedNode(entry.node, rank, entry.fqnLower.length))
        }

        val ranked = matches
            .sortedWith(compareBy({ it.rank }, { it.fqnLength }))
            .take(SERVER_SIDE_CAP)

        // ── assemble response ──────────────────────────────────────────
        val results = ranked.map { nodeRefFactory.enrichedNodeRef(it.node) }
        return mapOf(
            "results" to results,
            "summary" to mapOf(
                "total" to results.size,
                "returned" to results.size
            )
        )
    }

    /**
     * Expands [kindFilter] into the set of concrete kinds to match. Returns `null` when no filter is
     * supplied (the caller then applies the structural-node default). Assumes entries are already
     * validated, so [JavaNodeKind.fromValue] never legitimately fails here.
     */
    private fun expandKindFilter(kindFilter: List<String>?): Set<JavaNodeKind>? {
        if (kindFilter.isNullOrEmpty()) return null
        val result = mutableSetOf<JavaNodeKind>()
        for (entry in kindFilter) {
            val expanded = JavaKinds.expandAlias(entry)
            if (expanded != null) result.addAll(expanded) else JavaNodeKind.fromValue(entry)?.let { result.add(it) }
        }
        return result
    }

    private data class RankedNode(val node: HGNode, val rank: Int, val fqnLength: Int)

    companion object {
        private const val SERVER_SIDE_CAP = 50
    }
}
