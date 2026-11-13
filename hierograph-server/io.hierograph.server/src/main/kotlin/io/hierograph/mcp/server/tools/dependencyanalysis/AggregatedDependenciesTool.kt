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
package io.hierograph.mcp.server.tools.dependencyanalysis

import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.graphstore.jvm.model.JavaEdgeAttributes
import io.hierograph.server.mapping.GraphStoreDependencySource
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.mcp.server.core.INodeRefFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `aggregated_dependencies`
 *
 * Returns aggregated pairwise dependency edges from source subtrees to target subtrees.
 * Uses slim payload encoding. Operates entirely on the in-memory model.
 */
@Component
class AggregatedDependenciesTool(
    private val graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory
) {

    @Tool(
        name = "aggregated_dependencies",
        description = "[Dependency analysis] " +
                "Return aggregated pairwise dependencies from source subtrees to target subtrees. " +
                "Direction is implicit: put the depender in sourceIds, the depended-on in targetIds. " +
                "Each edge carries weight, type_pair_count, structured attributes (is_extends, " +
                "is_implements, is_annotated_by, is_depends_on_other), and kinds: how many facts of each kind " +
                "(calls, extends, casts, …) make up the weight. " +
                "Pairs with no dependency are omitted; check the summary for honest accounting. " +
                "No limit needed — result size is controlled by input size (cross-product cap: 2500). " +
                "For matrix-style all-pairs analysis, use pairwise_dependencies instead. " +
                "For type-level or detail-level evidence between a specific pair, use " +
                "outgoing_dependencies or incoming_dependencies."
    )
    fun aggregatedDependencies(
        @ToolParam(description = "One or more source subtree IDs (modules, packages, or types).")
        sourceIds: List<Long>,
        @ToolParam(description = "One or more target subtree IDs (modules, packages, or types).")
        targetIds: List<Long>
    ): Map<String, Any?> {

        // ── validate list sizes: each side needs an id; a repeated id adds nothing ──
        val sources = sourceIds.distinct()
        val targets = targetIds.distinct()
        if (sources.isEmpty() || targets.isEmpty()) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INPUT_TOO_SMALL",
                    "message" to "aggregated_dependencies needs at least one id in sourceIds and in targetIds, " +
                            "got ${sources.size} and ${targets.size}.",
                    "recovery" to "Pass at least one source and one target id."
                )
            )
        }

        // ── validate cross-product cap ─────────────────────────────────
        val crossProduct = sources.size.toLong() * targets.size.toLong()
        if (crossProduct > MAX_CROSS_PRODUCT) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INPUT_TOO_LARGE",
                    "message" to "The cross product of sourceIds (${sources.size}) x " +
                            "targetIds (${targets.size}) = $crossProduct exceeds the maximum of $MAX_CROSS_PRODUCT.",
                    "source_count" to sources.size,
                    "target_count" to targets.size,
                    "cross_product" to crossProduct,
                    "max_cross_product" to MAX_CROSS_PRODUCT,
                    "recovery" to "Narrow either sourceIds or targetIds. " +
                            "For all-pairs analysis within a node set, use pairwise_dependencies instead."
                )
            )
        }

        // ── resolve and validate all nodes ─────────────────────────────
        val hierarchy = graphService.model.hierarchy
        val sourceNodes = mutableListOf<HGNode>()
        for (id in sources) {
            val node = graphService.model.lookupNode(id) ?: return nodeNotFound(id)
            validateNodeKind(node)?.let { return it }
            sourceNodes.add(node)
        }

        val targetNodes = mutableListOf<HGNode>()
        for (id in targets) {
            val node = graphService.model.lookupNode(id) ?: return nodeNotFound(id)
            validateNodeKind(node)?.let { return it }
            targetNodes.add(node)
        }

        // ── compute aggregated edges ───────────────────────────────────
        val nodes = linkedMapOf<String, Any>()
        val edges = mutableListOf<Map<String, Any?>>()
        var pairsWithDep = 0

        for (source in sourceNodes) {
            for (target in targetNodes) {
                val aggDep = hierarchy.getAggregatedDependency(source, target)
                if (aggDep == null || aggDep.aggregatedWeight <= 0) continue

                pairsWithDep++

                // Register nodes
                nodeRefFactory.putSlimNode(nodes, source)
                nodeRefFactory.putSlimNode(nodes, target)

                // Count distinct type pairs and union the attributes bitmap
                val coreDeps = aggDep.coreDependencies
                val typePairs = mutableSetOf<Pair<Any, Any>>()
                var unionBitmap = 0
                for (dep in coreDeps) {
                    typePairs.add(dep.from.identifier to dep.to.identifier)
                    unionBitmap = unionBitmap or dep.attributesBitmap
                }

                edges.add(
                    linkedMapOf<String, Any?>(
                        "from" to source.identifier,
                        "to" to target.identifier,
                        "weight" to aggDep.aggregatedWeight,
                        "type_pair_count" to typePairs.size,
                        "attributes" to JavaEdgeAttributes.toMap(unionBitmap),
                        "kinds" to DependencyKind.toMap(GraphStoreDependencySource.kindsOf(coreDeps)),
                    )
                )
            }
        }

        // ── summary ────────────────────────────────────────────────────
        val totalPairs = sources.size * targets.size

        return linkedMapOf<String, Any?>(
            "nodes" to nodes,
            "edges" to edges,
            "summary" to linkedMapOf<String, Any?>(
                "total_pairs_requested" to totalPairs,
                "pairs_with_dependency" to pairsWithDep,
                "pairs_without_dependency" to (totalPairs - pairsWithDep)
            )
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun validateNodeKind(node: HGNode): Map<String, Any?>? {
        val kind = node.kind
        if (kind == JavaNodeKind.METHOD || kind == JavaNodeKind.FIELD) {
            val declaringType = graphService.model.hierarchy.parentOf(node)
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_NODE_KIND",
                    "message" to "This tool operates on type-level dependencies. " +
                            "The node is a $kind, not a type.",
                    "actual_kind" to kind.toString(),
                    "declaring_type" to if (declaringType != null)
                        nodeRefFactory.minimalNodeRef(declaringType) else null,
                    "recovery" to "To query dependencies involving this ${kind}'s declaring type, " +
                            "pass its id (${declaringType?.identifier}). For the member itself, call " +
                            "outgoing_dependencies or incoming_dependencies with detailLevel='detail', which accept member ids."
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

    companion object {
        private const val MAX_CROSS_PRODUCT = 2500
    }
}
