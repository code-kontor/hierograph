package io.hierograph.mcp.server.tools.dependencyanalysis

import org.slizaa.hierarchicalgraph.core.model.HGNode
import org.slizaa.hierarchicalgraph.core.model.HGNodeTraverser
import io.hierograph.mcp.server.HierarchicalGraphService
import io.hierograph.mcp.javaspec.JavaEdgeAttributes
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.server.tools.INodeRefFactory
import io.hierograph.mcp.server.tools.detail.DetailDependenciesComponent
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `outgoing_dependencies`
 *
 * Returns edges from a source subtree to a target subtree at the requested zoom level.
 * - `detail_level: "type"` (default): type-to-type edges, in-memory, fast
 * - `detail_level: "detail"`: method/field-level edges with source locations, via Neo4j
 */
@Component
class OutgoingDependenciesTool(
    private val graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory,
    private val detailDependenciesTool: DetailDependenciesComponent
) {

    @Tool(
        name = "outgoing_dependencies",
        description = "[Dependency analysis] " +
                "Return the edges from a source subtree to a target subtree. This is the evidence " +
                "tool — use after an aggregated query reveals a dependency of interest. " +
                "detail_level='type' (default) returns type-to-type edges from the in-memory model (fast). " +
                "detail_level='detail' returns method/field-level edges with source locations (slower). " +
                "The 'relationship' filter is only valid at detail level. " +
                "Summaries (by_attribute/by_relationship, by_source_type) give the shape without " +
                "needing to process all edges. " +
                "Direction: this tool shows what the source uses of the target. " +
                "For the reverse, use incoming_dependencies."
    )
    fun outgoingDependencies(
        @ToolParam(description = "Source subtree root ID.")
        fromId: Long,
        @ToolParam(description = "Target subtree root ID.")
        toId: Long,
        @ToolParam(
            description = "Zoom level: 'type' (default, in-memory) or 'detail' (method/field-level, Neo4j).",
            required = false
        )
        detailLevel: String?,
        @ToolParam(
            description = "Filter to a specific detail-level relationship kind. Only valid when detail_level='detail'.",
            required = false
        )
        relationship: String?,
        @ToolParam(
            description = "Maximum edges per page. Default: 100 (type) / 80 (detail). Caps: 400 / 250.",
            required = false
        )
        limit: Int?
    ): Map<String, Any?> {

        val level = detailLevel ?: "type"

        // ── validate detail_level ──────────────────────────────────────
        if (level !in listOf("type", "detail")) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_PARAMETER",
                    "message" to "Invalid detail_level: '$level'. Must be 'type' or 'detail'.",
                    "recovery" to "Use 'type' for type-to-type edges or 'detail' for method/field-level edges."
                )
            )
        }

        // ── validate relationship at type level ────────────────────────
        if (level == "type" && !relationship.isNullOrBlank()) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_PARAMETER",
                    "message" to "The 'relationship' parameter is only valid at detail_level='detail'. " +
                            "At type level, edges carry attribute flags but cannot be filtered by detail-level relationship.",
                    "recovery" to "Either remove the 'relationship' parameter, or set detail_level='detail' to filter by relationship kind."
                )
            )
        }

        return if (level == "type") {
            typeLevelDependencies(fromId, toId, limit, outgoing = true)
        } else {
            // Delegate to existing detail_dependencies tool
            val effectiveLimit = (limit ?: 80).coerceIn(1, 250)
            val effectiveRel = if (relationship.isNullOrBlank()) null else relationship
            detailDependenciesTool.detailDependencies(fromId, toId, effectiveRel, effectiveLimit)
        }
    }

    // ── type-level implementation (shared with IncomingDependenciesTool) ─

    internal fun typeLevelDependencies(
        fromId: Long,
        toId: Long,
        limit: Int?,
        outgoing: Boolean
    ): Map<String, Any?> {

        // ── resolve nodes ──────────────────────────────────────────────
        val fromNode = graphService.rootNode.lookupNode(fromId)
            ?: return nodeNotFound(fromId)
        val toNode = graphService.rootNode.lookupNode(toId)
            ?: return nodeNotFound(toId)

        // ── validate node kinds ────────────────────────────────────────
        validateNodeKind(fromNode)?.let { return it }
        validateNodeKind(toNode)?.let { return it }

        val effectiveLimit = (limit ?: 100).coerceIn(1, 400)

        // ── determine source and target based on direction ─────────────
        // outgoing: edges from fromNode to toNode
        // incoming: edges from toNode to fromNode (what toNode uses of fromNode)
        val sourceNode = if (outgoing) fromNode else toNode
        val targetNode = if (outgoing) toNode else fromNode

        // ── collect type-level edges ───────────────────────────────────
        // Expand both subtrees to types, then find core deps between them
        val sourceTypes = collectTypes(sourceNode)
        val targetTypeIds = collectTypeIds(targetNode)

        data class TypeEdge(
            val from: HGNode,
            val to: HGNode,
            val weight: Int,
            val bitmap: Int
        )

        val allEdges = mutableListOf<TypeEdge>()
        val byAttribute = linkedMapOf<String, Int>()
        for ((_, name) in JavaEdgeAttributes.ALL) {
            byAttribute[name] = 0
        }
        val sourceTypeCounts = linkedMapOf<Any, Int>()

        for (srcType in sourceTypes) {
            for (dep in srcType.outgoingCoreDependencies) {
                if (dep.to.identifier in targetTypeIds) {
                    allEdges.add(TypeEdge(srcType, dep.to, dep.weight, dep.attributesBitmap))

                    for ((pos, name) in JavaEdgeAttributes.ALL) {
                        if (JavaEdgeAttributes.isSet(dep.attributesBitmap, pos)) {
                            byAttribute.merge(name, 1) { a, b -> a + b }
                        }
                    }
                    sourceTypeCounts.merge(srcType.identifier, 1) { a, b -> a + b }
                }
            }
        }

        // ── sort: source qualified name, then target qualified name ────
        allEdges.sortWith(compareBy(
            { it.from.kind?.toString() ?: "" },
            { it.to.kind?.toString() ?: "" }
        ))

        // ── paginate ───────────────────────────────────────────────────
        val total = allEdges.size
        val truncated = total > effectiveLimit
        val page = allEdges.take(effectiveLimit)

        // ── build slim nodes map ───────────────────────────────────────
        val nodes = linkedMapOf<String, Any>()
        nodeRefFactory.putSlimNode(nodes, fromNode)
        nodeRefFactory.putSlimNode(nodes, toNode)
        for (edge in page) {
            nodeRefFactory.putSlimNode(nodes, edge.from)
            nodeRefFactory.putSlimNode(nodes, edge.to)
        }

        // ── build edge entries ─────────────────────────────────────────
        val edges = page.map { edge ->
            linkedMapOf<String, Any?>(
                "from" to edge.from.identifier,
                "to" to edge.to.identifier,
                "weight" to edge.weight,
                "type_pair_count" to 1,
                "attributes" to JavaEdgeAttributes.toMap(edge.bitmap)
            )
        }

        // ── by_source_type (top 10) ────────────────────────────────────
        val bySourceType = sourceTypeCounts.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (id, count) ->
                linkedMapOf<String, Any?>("id" to id, "count" to count)
            }

        return linkedMapOf<String, Any?>(
            "nodes" to nodes,
            "edges" to edges,
            "summary" to linkedMapOf<String, Any?>(
                "total" to total,
                "returned" to page.size,
                "truncated" to truncated,
                "by_attribute" to byAttribute,
                "by_source_type" to bySourceType
            )
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun collectTypes(node: HGNode): List<HGNode> {
        val types = mutableListOf<HGNode>()
        HGNodeTraverser.traverse(node) { n ->
            if (n.kind in JavaKinds.TYPE_KINDS) types.add(n)
        }
        return types
    }

    private fun collectTypeIds(node: HGNode): Set<Any> {
        val ids = mutableSetOf<Any>()
        HGNodeTraverser.traverse(node) { n ->
            if (n.kind in JavaKinds.TYPE_KINDS) ids.add(n.identifier)
        }
        return ids
    }

    internal fun validateNodeKind(node: HGNode): Map<String, Any?>? {
        val kind = node.kind
        if (kind == JavaKinds.METHOD || kind == JavaKinds.FIELD) {
            val declaringType = node.parent
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_NODE_KIND",
                    "message" to "This tool operates on type-level dependencies at detail_level='type'. " +
                            "The node is a $kind, not a type.",
                    "actual_kind" to kind.toString(),
                    "declaring_type" to if (declaringType != null)
                        nodeRefFactory.minimalNodeRef(declaringType) else null,
                    "recovery" to "Pass the declaring type's id (${declaringType?.identifier}), " +
                            "or use detail_level='detail' to query method/field-level evidence directly."
                )
            )
        }
        return null
    }

    internal fun nodeNotFound(id: Long) = mapOf<String, Any?>(
        "error" to mapOf(
            "code" to "NODE_NOT_FOUND",
            "message" to "No node with id $id exists in the graph.",
            "recovery" to "Use find_node to look up the correct node ID."
        )
    )
}
