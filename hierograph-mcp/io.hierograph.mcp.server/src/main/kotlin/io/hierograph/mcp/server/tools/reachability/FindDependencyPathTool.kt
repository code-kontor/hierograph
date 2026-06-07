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

import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.mcp.server.core.HierarchicalGraphService
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.mcp.javaspec.JavaKinds
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `find_dependency_path`
 *
 * Returns paths in the type-level dependency graph from a source to a target.
 * Uses slim payload encoding — types appear as path steps across multiple paths.
 * Operates entirely on the in-memory model.
 */
@Component
class FindDependencyPathTool(
    private val graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory
) {

    @Tool(
        name = "find_dependency_path",
        description = "[Reachability and impact] " +
                "Find transitive dependency paths between two subtrees in the type-level graph. " +
                "Returns the shortest paths with concrete type-level steps and weights. " +
                "An empty result (exists=false) is definitive — no dependency chain exists. " +
                "Accepts modules, packages, or types; expands internally. " +
                "Use max_paths and max_length to control result scope. " +
                "For blast-radius analysis (all things affected), use affected_by instead."
    )
    fun findDependencyPath(
        @ToolParam(description = "Source subtree root — module, package, or type ID.")
        fromId: Long,
        @ToolParam(description = "Target subtree root — module, package, or type ID.")
        toId: Long,
        @ToolParam(
            description = "Maximum number of distinct paths to return (1-20, default 5).",
            required = false
        )
        maxPaths: Int?,
        @ToolParam(
            description = "Maximum path length in hops. Omit for unbounded.",
            required = false
        )
        maxLength: Int?
    ): Map<String, Any?> {

        // ── resolve nodes ──────────────────────────────────────────────
        val fromNode = graphService.model.lookupNode(fromId)
            ?: return nodeNotFound(fromId)
        val toNode = graphService.model.lookupNode(toId)
            ?: return nodeNotFound(toId)

        // ── validate node kinds ────────────────────────────────────────
        validateNodeKind(fromNode)?.let { return it }
        validateNodeKind(toNode)?.let { return it }

        val effectiveMaxPaths = (maxPaths ?: 5).coerceIn(1, 20)
        val effectiveMaxLength = maxLength ?: Int.MAX_VALUE

        // ── expand subtrees to type IDs ────────────────────────────────
        val fromTypeIds = collectTypeIds(fromNode)
        val toTypeIds = collectTypeIds(toNode)

        // Same source and target → no path
        if (fromTypeIds.isEmpty() || toTypeIds.isEmpty() ||
            (fromTypeIds.size == 1 && toTypeIds.size == 1 && fromTypeIds.first() == toTypeIds.first())
        ) {
            return noPathResult(fromId, toId)
        }

        // ── BFS to find shortest paths ─────────────────────────────────
        // We use BFS layer by layer to find all shortest paths first,
        // then progressively longer paths up to max_paths.
        data class BfsState(
            val node: HGNode,
            val path: List<PathStep>  // steps taken to reach this node
        )

        val foundPaths = mutableListOf<List<PathStep>>()
        val visited = mutableSetOf<Any>() // node identifiers already fully expanded
        var queue: MutableList<BfsState> = mutableListOf()

        // Seed with all source types
        for (srcId in fromTypeIds) {
            val srcNode = graphService.model.lookupNode(srcId) ?: continue
            queue.add(BfsState(srcNode, emptyList()))
            visited.add(srcId)
        }

        var currentDepth = 0

        while (queue.isNotEmpty() && foundPaths.size < effectiveMaxPaths && currentDepth < effectiveMaxLength) {
            val nextQueue = mutableListOf<BfsState>()
            val visitedThisLevel = mutableSetOf<Any>()

            for (state in queue) {
                for (dep in state.node.outgoingCoreDependencies) {
                    val neighbor = dep.to
                    val neighborId = neighbor.identifier

                    val step = PathStep(
                        from = state.node.identifier,
                        to = neighborId,
                        weight = dep.weight
                    )
                    val newPath = state.path + step

                    if (neighborId in toTypeIds) {
                        // Found a path to a target type
                        foundPaths.add(newPath)
                        if (foundPaths.size >= effectiveMaxPaths) break
                    } else if (neighborId !in visited) {
                        visitedThisLevel.add(neighborId)
                        nextQueue.add(BfsState(neighbor, newPath))
                    }
                }
                if (foundPaths.size >= effectiveMaxPaths) break
            }

            visited.addAll(visitedThisLevel)
            queue = nextQueue
            currentDepth++
        }

        // ── sort paths: by length asc, then total weight desc ──────────
        val sortedPaths = foundPaths.sortedWith(
            compareBy<List<PathStep>> { it.size }
                .thenByDescending { path -> path.sumOf { it.weight } }
        )

        // ── build slim nodes map ───────────────────────────────────────
        val nodes = linkedMapOf<String, Any>()
        // Register from/to scope nodes first
        nodeRefFactory.putSlimNode(nodes, fromNode)
        nodeRefFactory.putSlimNode(nodes, toNode)
        // Register all nodes appearing in paths
        for (path in sortedPaths) {
            for (step in path) {
                val stepFromNode = graphService.model.lookupNode(step.from)
                if (stepFromNode != null) nodeRefFactory.putSlimNode(nodes, stepFromNode)
                val stepToNode = graphService.model.lookupNode(step.to)
                if (stepToNode != null) nodeRefFactory.putSlimNode(nodes, stepToNode)
            }
        }

        // ── build path entries ─────────────────────────────────────────
        val pathEntries = sortedPaths.map { path ->
            linkedMapOf<String, Any?>(
                "length" to path.size,
                "total_weight" to path.sumOf { it.weight },
                "steps" to path.map { step ->
                    linkedMapOf<String, Any?>(
                        "from" to step.from,
                        "to" to step.to,
                        "weight" to step.weight
                    )
                }
            )
        }

        // ── summary ────────────────────────────────────────────────────
        val exists = sortedPaths.isNotEmpty()

        return linkedMapOf<String, Any?>(
            "nodes" to nodes,
            "paths" to pathEntries,
            "summary" to linkedMapOf<String, Any?>(
                "from" to fromId,
                "to" to toId,
                "path_count" to sortedPaths.size,
                "shortest_length" to if (exists) sortedPaths.first().size else null,
                "exists" to exists
            )
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private data class PathStep(val from: Any, val to: Any, val weight: Int)

    private fun collectTypeIds(node: HGNode): Set<Any> {
        val hierarchy = graphService.model.hierarchy
        val ids = mutableSetOf<Any>()
        if (node.kind in JavaKinds.TYPE_KINDS) ids.add(node.identifier)
        hierarchy.traverse(node) { n ->
            if (n.kind in JavaKinds.TYPE_KINDS) {
                ids.add(n.identifier)
            }
        }
        return ids
    }

    private fun validateNodeKind(node: HGNode): Map<String, Any?>? {
        val kind = node.kind
        if (kind == JavaKinds.METHOD || kind == JavaKinds.FIELD) {
            val declaringType = graphService.model.hierarchy.parentOf(node)
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_NODE_KIND",
                    "message" to "This tool operates on type-level dependencies. " +
                            "The node is a $kind, not a type.",
                    "actual_kind" to kind.toString(),
                    "declaring_type" to if (declaringType != null)
                        nodeRefFactory.minimalNodeRef(declaringType) else null,
                    "recovery" to "Pass the declaring type's id (${declaringType?.identifier}) instead."
                )
            )
        }
        return null
    }

    private fun nodeNotFound(id: Long) = mapOf<String, Any?>(
        "error" to mapOf(
            "code" to "NODE_NOT_FOUND",
            "message" to "No node with id $id exists in the graph.",
            "recovery" to "Use find_node to look up the correct node ID."
        )
    )

    private fun noPathResult(fromId: Long, toId: Long) = linkedMapOf<String, Any?>(
        "nodes" to emptyMap<String, Any>(),
        "paths" to emptyList<Any>(),
        "summary" to linkedMapOf<String, Any?>(
            "from" to fromId,
            "to" to toId,
            "path_count" to 0,
            "shortest_length" to null,
            "exists" to false
        )
    )
}
