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
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.javaspec.JavaNodeKind
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantNodeMetadataProvider
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

// TODO: modifier_filter requires property materialisation (modifiers are not yet
//       available in the in-memory model). Once INodeMetadataProvider exposes
//       getModifiers(node), re-enable the modifier filtering below.

/**
 * MCP tool: `list_children`
 *
 * Returns the immediate direct children of a node — one level deep only.
 * Each child is an enriched NodeRef with kind-appropriate metadata.
 * Supports filtering by kind, name pattern, and modifiers.
 */
@Component
class ListChildrenTool(
    private val graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory
) {

    @Tool(
        name = "list_children",
        description = "[Discovery and navigation] " +
                "Returns the immediate direct children of a node — one level deep only. " +
                "Each child carries kind-appropriate metadata (counts, modifiers, flags). " +
                "On a module: returns packages. On a package: returns sub-packages and types. " +
                "On a type: returns methods and fields with metadata. " +
                "Supports filtering by kind, name pattern, and modifiers. " +
                "Do NOT use recursively to walk the tree; use list_descendants for multi-level traversal. " +
                "For full detail on a specific method or field, use method_details or field_details."
    )
    fun listChildren(
        @ToolParam(description = "The node ID whose direct children to return.")
        nodeId: Long,
        @ToolParam(
            description = "Restricts results to specific kinds. Accepts specific kind values " +
                    "(e.g. 'java.class', 'java.method') and group aliases ('types', 'members', 'packages'). " +
                    "Mixing is allowed. Omit to return all children.",
            required = false
        )
        kindFilter: List<String>?,
        @ToolParam(
            description = "Case-insensitive substring match against child names.",
            required = false
        )
        namePattern: String?,
        @ToolParam(
            description = "Restricts to children whose modifiers include ALL listed values (AND logic). " +
                    "Only meaningful for methods and fields; silently ignored for other input kinds. " +
                    "Examples: ['public'], ['static', 'final'], ['private'].",
            required = false
        )
        modifierFilter: List<String>?,
        @ToolParam(
            description = "Maximum number of children to return (default 200).",
            required = false
        )
        limit: Int?
    ): Map<String, Any?> {

        // ── resolve the node ──────────────────────────────────────────
        val node = graphService.model.lookupNode(nodeId)
            ?: return errorResponse(
                "NODE_NOT_FOUND",
                "No node with id $nodeId exists in the graph.",
                "Use find_node to look up the correct node ID."
            )

        // ── resolve kind filter (expand aliases) ──────────────────────
        val expandedKinds = expandKindFilter(kindFilter)
        if (expandedKinds == null && kindFilter != null) {
            // expandKindFilter returns null on invalid input
            val invalid = kindFilter.filter { JavaNodeKind.fromValue(it) == null && JavaKinds.expandAlias(it) == null }
            return errorResponse(
                "INVALID_KIND",
                "Unknown kind${if (invalid.size > 1) "s" else ""} ${invalid.joinToString { "'$it'" }}. " +
                        "Valid kinds: ${JavaKinds.ALL_KINDS.joinToString { it.value }}. " +
                        "Group aliases: ${JavaKinds.ALL_ALIASES.joinToString()}.",
                "Use valid kind values or group aliases."
            )
        }

        val effectiveLimit = (limit ?: 200).coerceIn(1, 1000)
        val nameLower = namePattern?.lowercase()

        // ── filter children ───────────────────────────────────────────
        val hierarchy = graphService.model.hierarchy
        val allFiltered = mutableListOf<HGNode>()
        val byKind = linkedMapOf<String, Int>()

        for (child in hierarchy.childrenOf(node)) {
            // 1. kind filter
            if (expandedKinds != null && child.kind !in expandedKinds) continue

            // 2. name pattern
            if (nameLower != null) {
                val name = JQAssistantNodeMetadataProvider.getName(child) ?: ""
                if (!name.lowercase().contains(nameLower)) continue
            }

            // 3. modifier filter — currently a no-op; modifiers are not yet in the in-memory model
            // if (modifierFilter != null && modifierFilter.isNotEmpty()) {
            //     val modifiers = mp.getModifiers(child)
            //     if (modifiers == null || !modifiers.containsAll(modifierFilter)) continue
            // }

            // passed all filters
            val kindStr = child.kind?.toString() ?: "unknown"
            byKind.merge(kindStr, 1) { a, b -> a + b }
            allFiltered.add(child)
        }

        // ── build results (truncated to limit) ────────────────────────
        val truncated = allFiltered.size > effectiveLimit
        val results = allFiltered
            .take(effectiveLimit)
            .map { nodeRefFactory.enrichedNodeRef(it) }

        // ── assemble response ─────────────────────────────────────────
        return linkedMapOf(
            "parent" to nodeRefFactory.enrichedNodeRef(node),
            "results" to results,
            "summary" to linkedMapOf(
                "total" to allFiltered.size,
                "returned" to results.size,
                "truncated" to truncated,
                "by_kind" to byKind
            )
        )
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Expands a kind filter list (which may contain group aliases) into a set of
     * [JavaNodeKind] values. Returns `null` if the input contains invalid values.
     * Returns `null` (meaning "no filter") if [kindFilter] is null or empty.
     */
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
