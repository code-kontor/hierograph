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

import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.mcp.server.tools.detail.NestedTypes
import io.hierograph.graphstore.jvm.model.JavaKinds
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
                "Use maxPaths and maxLength to control result scope. " +
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

        // No types on either side, or source and target are the same single type → no path
        if (fromTypeIds.isEmpty() || toTypeIds.isEmpty() ||
            (fromTypeIds.size == 1 && toTypeIds.size == 1 && fromTypeIds.first() == toTypeIds.first())
        ) {
            return noPathResult(fromId, toId)
        }

        // ── layered search, keeping each node's best partial paths ─────
        // Layer n holds the nodes first reached in n hops, each with its heaviest partial paths. A node
        // is entered only at its shortest distance, source types are never re-entered, and target types
        // end a path rather than extend it. All arrivals at targets in a layer are ranked by weight, so
        // the heaviest paths of each length are returned. A node keeps at most as many partial paths as
        // paths are still wanted: a heavier prefix with the same continuation always ranks first, so no
        // returned path needs more. The work is bounded by edges × maxPaths, not by the number of paths.
        val foundPaths = mutableListOf<List<PathStep>>()
        val reached = HashSet<Any>(fromTypeIds)
        var layer = LinkedHashMap<HGNode, List<Partial>>()
        for (srcId in fromTypeIds) {
            graphService.model.lookupNode(srcId)?.let { layer[it] = listOf(Partial.START) }
        }

        var length = 0
        while (layer.isNotEmpty() && foundPaths.size < effectiveMaxPaths && length < effectiveMaxLength) {
            length++
            val arrivals = mutableListOf<Partial>()
            val next = LinkedHashMap<HGNode, MutableList<Partial>>()
            for ((node, partials) in layer) {
                for (dep in node.outgoingCoreDependencies) {
                    val neighbor = dep.to
                    val neighborId = neighbor.identifier
                    val isTarget = neighborId in toTypeIds
                    if (!isTarget && neighborId in reached) continue
                    val step = PathStep(from = node.identifier, to = neighborId, weight = dep.weight)
                    val extended = partials.map { Partial(step, it, it.weight + dep.weight) }
                    if (isTarget) arrivals += extended else next.getOrPut(neighbor) { mutableListOf() } += extended
                }
            }
            arrivals.sortedWith(HEAVIEST_FIRST).take(effectiveMaxPaths - foundPaths.size)
                .forEach { foundPaths += it.steps() }

            val stillWanted = effectiveMaxPaths - foundPaths.size
            layer = LinkedHashMap()
            for ((node, partials) in next) {
                layer[node] = partials.sortedWith(HEAVIEST_FIRST).take(stillWanted)
                reached += node.identifier
            }
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
                "from" to path.first().from,  // the source type the path starts at
                "to" to path.last().to,       // the target type it ends at
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
                "from_scope" to fromId,
                "to_scope" to toId,
                "path_count" to sortedPaths.size,
                "shortest_length" to if (exists) sortedPaths.first().size else null,
                "exists" to exists
            )
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private data class PathStep(val from: Any, val to: Any, val weight: Int)

    /** A partial path: its last [step], the partial path before it, and its total [weight]. */
    private class Partial(val step: PathStep?, val parent: Partial?, val weight: Int) {
        fun steps(): List<PathStep> = generateSequence(this) { it.parent }.mapNotNull { it.step }.toList().asReversed()

        companion object {
            /** The empty path every search starts from, at a source type. */
            val START = Partial(null, null, 0)
        }
    }

    private fun collectTypeIds(node: HGNode): Set<Any> {
        val hierarchy = graphService.model.hierarchy
        val ids = mutableSetOf<Any>()
        if (JavaKinds.isTypeKind(node.kind)) ids.add(node.identifier)
        hierarchy.traverse(node) { n ->
            if (JavaKinds.isTypeKind(n.kind)) {
                ids.add(n.identifier)
            }
        }
        // A type stands for its source: its nested types sit beside it in the package but belong to it.
        if (node.kind in JavaKinds.TYPE_KINDS) NestedTypes.allNested(hierarchy, node).forEach { ids.add(it.identifier) }
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

    private companion object {
        /** Ranks partial paths by total weight, heaviest first; equal weights keep their discovery order. */
        val HEAVIEST_FIRST = compareByDescending<Partial> { it.weight }
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
            "from_scope" to fromId,
            "to_scope" to toId,
            "path_count" to 0,
            "shortest_length" to null,
            "exists" to false
        )
    )
}
