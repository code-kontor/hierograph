package io.hierograph.mcp.server.tools.dependencyanalysis

import org.slizaa.hierarchicalgraph.core.model.HGNode
import io.hierograph.mcp.server.HierarchicalGraphService
import io.hierograph.mcp.javaspec.JavaEdgeAttributes
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.server.tools.INodeRefFactory
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
                "Direction is implicit: put the depender in source_ids, the depended-on in target_ids. " +
                "Each edge carries weight, type_pair_count, and structured attributes (is_extends, " +
                "is_implements, is_annotated_by, is_depends_on_other). " +
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

        // ── validate cross-product cap ─────────────────────────────────
        val crossProduct = sourceIds.size.toLong() * targetIds.size.toLong()
        if (crossProduct > MAX_CROSS_PRODUCT) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INPUT_TOO_LARGE",
                    "message" to "The cross product of source_ids (${sourceIds.size}) x " +
                            "target_ids (${targetIds.size}) = $crossProduct exceeds the maximum of $MAX_CROSS_PRODUCT.",
                    "source_count" to sourceIds.size,
                    "target_count" to targetIds.size,
                    "cross_product" to crossProduct,
                    "max_cross_product" to MAX_CROSS_PRODUCT,
                    "recovery" to "Narrow either source_ids or target_ids. " +
                            "For all-pairs analysis within a node set, use pairwise_dependencies instead."
                )
            )
        }

        // ── resolve and validate all nodes ─────────────────────────────
        val sourceNodes = mutableListOf<HGNode>()
        for (id in sourceIds) {
            val node = graphService.rootNode.lookupNode(id) ?: return nodeNotFound(id)
            validateNodeKind(node)?.let { return it }
            sourceNodes.add(node)
        }

        val targetNodes = mutableListOf<HGNode>()
        for (id in targetIds) {
            val node = graphService.rootNode.lookupNode(id) ?: return nodeNotFound(id)
            validateNodeKind(node)?.let { return it }
            targetNodes.add(node)
        }

        // ── compute aggregated edges ───────────────────────────────────
        val nodes = linkedMapOf<String, Any>()
        val edges = mutableListOf<Map<String, Any?>>()
        var pairsWithDep = 0

        for (source in sourceNodes) {
            for (target in targetNodes) {
                val aggDep = source.getOutgoingDependenciesTo(target)
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
                        "attributes" to JavaEdgeAttributes.toMap(unionBitmap)
                    )
                )
            }
        }

        // ── summary ────────────────────────────────────────────────────
        val totalPairs = sourceIds.size * targetIds.size

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
        if (kind == JavaKinds.METHOD || kind == JavaKinds.FIELD) {
            val declaringType = node.parent
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_NODE_KIND",
                    "message" to "This tool operates on type-level dependencies. " +
                            "The node is a $kind, not a type.",
                    "actual_kind" to kind.toString(),
                    "declaring_type" to if (declaringType != null)
                        nodeRefFactory.minimalNodeRef(declaringType) else null,
                    "recovery" to "To query dependencies involving this ${kind}'s declaring type, " +
                            "pass id=${declaringType?.identifier}. To query method-level evidence directly, " +
                            "use outgoing_dependencies or incoming_dependencies with detail_level='detail'."
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
