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
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

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
                "Each child carries kind-appropriate metadata (counts and modifiers). " +
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
            description = "Restricts to methods and fields whose modifiers include ALL listed values (AND logic); " +
                    "other children are excluded while the filter is set. Values: public, protected, private, " +
                    "package-private, static, final, abstract, synchronized, native, default, transient, volatile. " +
                    "Examples: ['public'], ['static', 'final'], ['private'].",
            required = false
        )
        modifierFilter: List<String>?,
        @ToolParam(
            description = "Maximum number of children to return (1-200, default 200; larger values are clamped).",
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

        // 200 children of ~250 bytes fill the 50,000-byte response budget the paginated tools observe.
        val effectiveLimit = (limit ?: 200).coerceIn(1, 200)
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
                val name = NodeMetadata.getName(child) ?: ""
                if (!name.lowercase().contains(nameLower)) continue
            }

            // 3. modifier filter (methods and fields only)
            if (modifiers != null && !modifiers.matches(child)) continue

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
