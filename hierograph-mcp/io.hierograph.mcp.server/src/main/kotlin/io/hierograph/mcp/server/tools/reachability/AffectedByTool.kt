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
package io.hierograph.mcp.server.tools.reachability

import io.hierograph.hierarchicalgraph.core.model.CoreNode
import io.hierograph.mcp.server.core.HierarchicalGraphService
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.mcp.server.core.pagination.DataHashProvider
import io.hierograph.mcp.server.core.pagination.PageResult
import io.hierograph.mcp.server.core.pagination.PaginationSpec
import io.hierograph.mcp.server.core.pagination.Paginator
import io.hierograph.mcp.server.core.pagination.QueryHash
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.javaspec.JavaNodeKind
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantNodeMetadataProvider
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.*

/**
 * MCP tool: `affected_by`
 *
 * Returns types transitively connected to the input via type-level dependencies.
 * "What breaks if I change this?" (incoming) or "What does this rely on?" (outgoing).
 * Operates entirely on the in-memory model — no Neo4j queries.
 */
@Component
class AffectedByTool(
    private val graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory,
    private val dataHashProvider: DataHashProvider
) {

    private data class PathStep(val from: Any, val to: Any, val weight: Int)

    private data class AffectedEntry(
        val node: CoreNode,
        val distance: Int,
        var sourceCount: Int,
        val via: List<PathStep>
    )

    @Tool(
        name = "affected_by",
        description = "[Reachability and impact] " +
                "Return the transitive blast radius of a node — all types that depend on it " +
                "(direction='incoming', default) or all types it depends on (direction='outgoing'), " +
                "up to max_depth hops. This is the tool for 'what breaks if I change this?' " +
                "Accepts modules, packages, or types; expands higher-level inputs internally. " +
                "Each result carries distance (hop count) and source_count (how many types in " +
                "the input subtree reach this affected type). The summary (by_distance, " +
                "by_parent_module) often answers the question without processing individual " +
                "results. Use max_depth or kind_filter to narrow large result sets."
    )
    fun affectedBy(
        @ToolParam(description = "The node being analyzed — module, package, or type ID.")
        nodeId: Long,
        @ToolParam(
            description = "Direction of traversal: 'incoming' (default) = what depends on this node; " +
                    "'outgoing' = what this node depends on.",
            required = false
        )
        direction: String?,
        @ToolParam(
            description = "Maximum traversal depth in dependency hops. Omit for unbounded.",
            required = false
        )
        maxDepth: Int?,
        @ToolParam(
            description = "Optional list of kind filters for results. Accepts specific kinds " +
                    "(e.g. 'java.class') and group aliases ('types'). Filters results, not traversal.",
            required = false
        )
        kindFilter: List<String>?,
        @ToolParam(
            description = "Maximum results per page (1-350, default 100).",
            required = false
        )
        limit: Int?,
        @ToolParam(
            description = "Opaque pagination cursor from a previous response's next_cursor. " +
                    "Pass it to retrieve the next page; omit to start from the first page. " +
                    "When continuing, keep the other parameters identical to the original call. " +
                    "If the blast radius is larger than you need, prefer narrowing the query " +
                    "(a smaller max_depth or a kind_filter) over paginating through all of it.",
            required = false
        )
        cursor: String?
    ): Map<String, Any?> {

        // ── validate direction ─────────────────────────────────────────
        val effectiveDirection = direction ?: "incoming"
        if (effectiveDirection !in listOf("incoming", "outgoing")) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_PARAMETER",
                    "message" to "Invalid direction: '$effectiveDirection'. Must be 'incoming' or 'outgoing'.",
                    "recovery" to "Use direction='incoming' for blast radius or direction='outgoing' for transitive dependencies."
                )
            )
        }

        // ── resolve node ───────────────────────────────────────────────
        val node = graphService.model.lookupNode(nodeId)
            ?: return mapOf(
                "error" to mapOf(
                    "code" to "NODE_NOT_FOUND",
                    "message" to "No node with id $nodeId exists in the graph.",
                    "recovery" to "Use find_node to look up the correct node ID."
                )
            )

        // ── validate node kind (reject methods and fields) ─────────────
        val hierarchy = graphService.model.hierarchy
        val nodeKind = node.kind
        if (nodeKind == JavaKinds.METHOD || nodeKind == JavaKinds.FIELD) {
            val declaringType = hierarchy.parentOf(node)
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_NODE_KIND",
                    "message" to "This tool operates on type-level dependencies. " +
                            "The node is a ${nodeKind}, not a type.",
                    "actual_kind" to nodeKind.toString(),
                    "declaring_type" to if (declaringType != null) nodeRefFactory.minimalNodeRef(declaringType) else null,
                    "recovery" to "To query dependencies involving this ${nodeKind}'s declaring type, " +
                            "pass id=${declaringType?.identifier}."
                )
            )
        }

        // ── resolve kind filter ────────────────────────────────────────
        val allowedKinds: Set<JavaNodeKind>? = if (kindFilter != null) {
            val resolved = mutableSetOf<JavaNodeKind>()
            for (k in kindFilter) {
                val expanded = JavaKinds.expandAlias(k)
                if (expanded != null) {
                    resolved.addAll(expanded)
                } else {
                    val nk = JavaNodeKind.fromValue(k)
                    if (nk != null) resolved.add(nk)
                }
            }
            resolved.ifEmpty { null }
        } else null

        val effectiveMaxDepth = maxDepth ?: Int.MAX_VALUE

        // Query hash covers the parameters that define the result set — not limit or cursor.
        // The kind filter is sorted so a mere reordering is not seen as a different query.
        val queryHash = QueryHash.of(
            mapOf(
                "nodeId" to nodeId,
                "direction" to effectiveDirection,
                "maxDepth" to maxDepth,
                "kindFilter" to kindFilter?.sorted()
            )
        )

        // ── collect source subtree type IDs ────────────────────────────
        val sourceTypeIds = mutableSetOf<Any>()
        if (node.kind in JavaKinds.TYPE_KINDS) {
            sourceTypeIds.add(node.identifier)
        }
        hierarchy.traverse(node) { n ->
            if (n.kind in JavaKinds.TYPE_KINDS) {
                sourceTypeIds.add(n.identifier)
            }
        }

        // ── BFS over type-level dependency graph ───────────────────────
        // BFS state
        val visited = mutableMapOf<Any, AffectedEntry>() // node identifier → entry
        val queue: Queue<Pair<CoreNode, List<PathStep>>> = ArrayDeque()

        // Seed: direct neighbors of source types
        for (sourceId in sourceTypeIds) {
            val sourceNode = graphService.model.lookupNode(sourceId) ?: continue
            val deps = if (effectiveDirection == "incoming")
                sourceNode.incomingCoreDependencies
            else
                sourceNode.outgoingCoreDependencies

            for (dep in deps) {
                val neighbor = if (effectiveDirection == "incoming") dep.from else dep.to
                val neighborId = neighbor.identifier
                if (neighborId in sourceTypeIds) continue // skip self-subtree

                val step = PathStep(
                    from = if (effectiveDirection == "incoming") neighborId else sourceId,
                    to = if (effectiveDirection == "incoming") sourceId else neighborId,
                    weight = dep.weight
                )

                val existing = visited[neighborId]
                if (existing == null) {
                    val entry = AffectedEntry(neighbor, 1, 1, listOf(step))
                    visited[neighborId] = entry
                    queue.add(neighbor to entry.via)
                } else {
                    existing.sourceCount++
                }
            }
        }

        // BFS expansion
        while (queue.isNotEmpty()) {
            val (current, currentVia) = queue.poll()
            val currentEntry = visited[current.identifier] ?: continue
            if (currentEntry.distance >= effectiveMaxDepth) continue

            val deps = if (effectiveDirection == "incoming")
                current.incomingCoreDependencies
            else
                current.outgoingCoreDependencies

            for (dep in deps) {
                val neighbor = if (effectiveDirection == "incoming") dep.from else dep.to
                val neighborId = neighbor.identifier
                if (neighborId in sourceTypeIds) continue
                if (neighborId in visited) continue

                val step = PathStep(
                    from = if (effectiveDirection == "incoming") neighborId else current.identifier,
                    to = if (effectiveDirection == "incoming") current.identifier else neighborId,
                    weight = dep.weight
                )

                val entry = AffectedEntry(
                    neighbor,
                    currentEntry.distance + 1,
                    1,
                    currentVia + step
                )
                visited[neighborId] = entry
                queue.add(neighbor to entry.via)
            }
        }

        // ── filter by kind ─────────────────────────────────────────────
        // Sort by (distance ascending, qualified name), with the identifier as a tiebreaker so the
        // order is total — the closest-affected types appear first, and the sequence is reproducible
        // for pagination cursors.
        val allResults = visited.values
            .filter { allowedKinds == null || it.node.kind in allowedKinds }
            .sortedWith(
                compareBy<AffectedEntry> { it.distance }
                    .thenBy { JQAssistantNodeMetadataProvider.getQualifiedName(it.node) }
                    .thenBy { it.node.identifier.toString() }
            )

        // ── compute summary over full result set ───────────────────────
        val byDistance = sortedMapOf<Int, Int>()
        val moduleCountMap = mutableMapOf<Any, Int>()
        for (entry in allResults) {
            byDistance.merge(entry.distance, 1) { a, b -> a + b }
            // Walk up to find the top-level module
            var ancestor: CoreNode = entry.node
            while (hierarchy.parentOf(ancestor) != null && hierarchy.parentOf(ancestor) !== hierarchy.rootNode) {
                ancestor = hierarchy.parentOf(ancestor)!!
            }
            if (hierarchy.parentOf(ancestor) == hierarchy.rootNode) {
                moduleCountMap.merge(ancestor.identifier, 1) { a, b -> a + b }
            }
        }

        val byParentModule = moduleCountMap.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (moduleId, count) ->
                val moduleNode = graphService.model.lookupNode(moduleId)
                linkedMapOf<String, Any?>(
                    "id" to moduleId,
                    "name" to (moduleNode?.let { nodeRefFactory.minimalNodeRef(it)["name"] } ?: "unknown"),
                    "count" to count
                )
            }

        // ── paginate ───────────────────────────────────────────────────
        val pageResult = Paginator.paginate(
            allItems = allResults,
            spec = PAGINATION,
            queryHash = queryHash,
            dataHash = dataHashProvider.dataHash,
            cursor = cursor,
            limit = limit
        )
        val pageData = when (pageResult) {
            is PageResult.Failed -> return pageResult.error.toResponse()
            is PageResult.Page -> pageResult
        }

        // ── build results ──────────────────────────────────────────────
        val results = pageData.items.map { entry ->
            linkedMapOf<String, Any?>(
                "node" to nodeRefFactory.enrichedNodeRef(entry.node),
                "distance" to entry.distance,
                "source_count" to entry.sourceCount,
                "via" to entry.via.map { step ->
                    linkedMapOf<String, Any?>("from" to step.from, "to" to step.to, "weight" to step.weight)
                }
            )
        }

        // ── assemble response ──────────────────────────────────────────
        val response = linkedMapOf<String, Any?>(
            "source" to nodeRefFactory.enrichedNodeRef(node),
            "direction" to effectiveDirection,
            "results" to results,
            "summary" to linkedMapOf<String, Any?>(
                "total" to pageData.total,
                "returned" to pageData.returned,
                "truncated" to pageData.truncated,
                "by_distance" to byDistance,
                "by_parent_module" to byParentModule
            )
        )
        // next_cursor is present iff more results follow this page (omitted, not null, on the last page).
        pageData.nextCursor?.let { response["next_cursor"] = it }
        return response
    }

    companion object {
        /** Pagination policy for affected_by (~450 bytes/item): default 100, server cap 350. */
        private val PAGINATION = PaginationSpec(tool = "affected_by", defaultLimit = 100, maxLimit = 350)
    }
}
