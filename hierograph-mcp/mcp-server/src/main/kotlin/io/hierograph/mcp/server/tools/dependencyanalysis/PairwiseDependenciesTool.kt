package io.hierograph.mcp.server.tools.dependencyanalysis

import io.hierograph.hierarchicalgraph.core.algorithms.GraphUtils
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.mcp.server.HierarchicalGraphService
import io.hierograph.mcp.javaspec.JavaEdgeAttributes
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.server.tools.INodeRefFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `pairwise_dependencies`
 *
 * Returns the dependency matrix among a set of subtrees — all pairwise aggregated
 * dependencies plus server-computed structural insights (density, cycles, SCCs,
 * topological order). Uses slim payload encoding. Entirely in-memory.
 */
@Component
class PairwiseDependenciesTool(
    private val graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory
) {

    @Tool(
        name = "pairwise_dependencies",
        description = "[Dependency analysis] " +
                "Return the dependency matrix among a set of subtrees — the DSM / coupling-matrix tool. " +
                "Returns all pairwise aggregated dependencies plus server-computed structural insights: " +
                "density, cycle detection, strongly connected components, and topological order. " +
                "The summary often answers the architectural question directly — check it before " +
                "processing individual edges. Input is 2-50 nodes. " +
                "For larger or asymmetric queries, use aggregated_dependencies. " +
                "For evidence of a specific dependency pair, use outgoing_dependencies or incoming_dependencies."
    )
    fun pairwiseDependencies(
        @ToolParam(description = "List of subtree IDs to analyze pairwise (2-50; typically modules or packages).")
        nodeIds: List<Long>,
        @ToolParam(
            description = "Which edges to include: 'both' (default, standard DSM), " +
                    "'outgoing' (row depends on column), 'incoming' (column depends on row).",
            required = false
        )
        direction: String?
    ): Map<String, Any?> {

        // ── validate input size ────────────────────────────────────────
        if (nodeIds.size < 2) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INPUT_TOO_SMALL",
                    "message" to "pairwise_dependencies requires at least 2 node IDs, got ${nodeIds.size}.",
                    "recovery" to "For single-pair queries, use aggregated_dependencies instead."
                )
            )
        }
        if (nodeIds.size > MAX_NODES) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INPUT_TOO_LARGE",
                    "message" to "pairwise_dependencies accepts at most $MAX_NODES node IDs for matrix usability, got ${nodeIds.size}.",
                    "node_count" to nodeIds.size,
                    "max_nodes" to MAX_NODES,
                    "recovery" to "Reduce the node set, or use aggregated_dependencies with explicit source_ids and target_ids for larger asymmetric queries."
                )
            )
        }

        // ── validate direction ─────────────────────────────────────────
        val effectiveDirection = direction ?: "both"
        if (effectiveDirection !in listOf("both", "outgoing", "incoming")) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_PARAMETER",
                    "message" to "Invalid direction: '$effectiveDirection'. Must be 'both', 'outgoing', or 'incoming'.",
                    "recovery" to "Use 'both' for standard DSM, 'outgoing' for row→column, 'incoming' for column→row."
                )
            )
        }

        // ── resolve and validate nodes ─────────────────────────────────
        val resolvedNodes = mutableListOf<HGNode>()
        for (id in nodeIds) {
            val node = graphService.rootNode.lookupNode(id)
                ?: return nodeNotFound(id)
            validateNodeKind(node)?.let { return it }
            resolvedNodes.add(node)
        }

        // ── build DSM using the algorithms library ─────────────────────
        val dsm = GraphUtils.createDependencyStructureMatrix(resolvedNodes)
        val orderedNodes = dsm.orderedNodes
        val size = orderedNodes.size

        // ── slim nodes map (DSM order) ─────────────────────────────────
        val nodes = linkedMapOf<String, Any>()
        for (node in orderedNodes) {
            nodeRefFactory.putSlimNode(nodes, node)
        }

        // ── compute edges ──────────────────────────────────────────────
        val edges = mutableListOf<Map<String, Any?>>()
        var edgeCount = 0

        for (i in 0 until size) {
            for (j in 0 until size) {
                if (i == j) continue // no self-loops

                val source = orderedNodes[i]
                val target = orderedNodes[j]

                // Direction filter
                val include = when (effectiveDirection) {
                    "outgoing" -> true   // source→target = row depends on column
                    "incoming" -> false  // we'll handle below
                    else -> true         // "both"
                }

                // For "incoming", swap: we want edges where target depends on source
                val (from, to) = if (effectiveDirection == "incoming") target to source else source to target

                if (!include && effectiveDirection != "incoming") continue

                val aggDep = from.getOutgoingDependenciesTo(to)
                if (aggDep == null || aggDep.aggregatedWeight <= 0) continue

                val coreDeps = aggDep.coreDependencies
                val typePairs = mutableSetOf<Pair<Any, Any>>()
                var unionBitmap = 0
                for (dep in coreDeps) {
                    typePairs.add(dep.from.identifier to dep.to.identifier)
                    unionBitmap = unionBitmap or dep.attributesBitmap
                }

                edges.add(
                    linkedMapOf<String, Any?>(
                        "from" to from.identifier,
                        "to" to to.identifier,
                        "weight" to aggDep.aggregatedWeight,
                        "type_pair_count" to typePairs.size,
                        "attributes" to JavaEdgeAttributes.toMap(unionBitmap)
                    )
                )
                edgeCount++
            }
        }

        // ── structural analytics ───────────────────────────────────────
        val possibleEdges = size * (size - 1)
        val density = if (possibleEdges > 0)
            Math.round(edgeCount * 100.0 / possibleEdges) / 100.0
        else 0.0

        val cycles = dsm.cycles
        val hasCycles = cycles.isNotEmpty()

        val sccs = cycles
            .filter { it.size >= 2 }
            .map { cycle -> cycle.map { it.identifier } }

        // ── summary ────────────────────────────────────────────────────
        val summary = linkedMapOf<String, Any?>(
            "node_count" to size,
            "edge_count" to edgeCount,
            "possible_edges" to possibleEdges,
            "density" to density,
            "has_cycles" to hasCycles,
            "strongly_connected_components" to sccs
        )
        if (!hasCycles) {
            summary["topological_order"] = orderedNodes.map { it.identifier }
        }

        return linkedMapOf<String, Any?>(
            "nodes" to nodes,
            "edges" to edges,
            "summary" to summary
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

    companion object {
        private const val MAX_NODES = 50
    }
}
