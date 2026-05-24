package org.slizaa.mcp.core.mcp.detail

import org.neo4j.driver.Record
import org.slf4j.LoggerFactory
import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency
import org.slizaa.hierarchicalgraph.core.model.HGNode
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import org.slizaa.mcp.core.HierarchicalGraphService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Comparator
import java.util.TreeMap

/**
 * Implementation of the `detail_dependencies` MCP tool. See the spec at
 * `docs/tool-specifications/detail-level/cartograph-detail-dependencies-spec.md`.
 *
 * Returns method/field-level edges between a source subtree and a target subtree, with
 * optional filtering by Cartograph relationship kind. The Cartograph vocabulary is mapped
 * to jQAssistant edge labels by the [BRANCHES] table -- the single place to look
 * when adding, renaming, or remapping a relationship kind.
 */
@Component
class DetailDependenciesMcpTool(graphService: HierarchicalGraphService) : AbstractDetailMcpTool(graphService) {

    private val log = LoggerFactory.getLogger(DetailDependenciesMcpTool::class.java)

    /**
     * When true, the Cypher statement built for each invocation is logged at INFO together
     * with the effective `relationship` filter and the resolved `fromTypes` /
     * `toTypes` parameters. Set via
     * `slizaa.mcp.tools.detail-dependencies.log-cypher=true` in
     * `application.properties`; defaults to `false`. Each detail tool gets its
     * own flag as the need arises.
     */
    @Value("\${slizaa.mcp.tools.detail-dependencies.log-cypher:false}")
    private var logCypher: Boolean = false

    @Tool(
        name = "detail_dependencies",
        description = "[Detail-level] Return the method-level and field-level dependencies between a source subtree and a target " +
                "subtree. This is the drill-down tool that bridges the hierarchical level and the detail level — " +
                "given an aggregated dependency you've identified (typically via aggregated_outgoing, " +
                "aggregated_incoming, or outgoing_core_dependencies), this returns the underlying concrete " +
                "method/field edges that explain it. " +
                "Returns a top-level 'nodes' map (each referenced node listed once with name, qualified_name, kind) " +
                "plus an 'edges' list whose entries reference nodes by ID. Each edge carries 'from' and 'to' " +
                "(node IDs), 'from_parent' and optionally 'to_parent' (declaring-type IDs for navigation back to " +
                "the hierarchical model), the relationship kind, and the source location (file path and line " +
                "number). The 'summary' block groups edges by relationship kind (by_relationship) and by source " +
                "type (by_source_type), by source child nodes (by_source_nodes), and by target child nodes " +
                "(by_target_nodes) — these are often more useful than enumerating individual edges, because " +
                "they tell you what kind of coupling exists, which types are responsible, and how the coupling " +
                "is distributed across sub-areas of the source and target subtrees. " +
                "Inheritance: the query always traverses EXTENDS/IMPLEMENTS on both sides. An edge whose source " +
                "method/field is declared on an ancestor of a type in the from-subtree is included; same for the " +
                "target subtree when the target is a method or field. 'from_parent' (and 'to_parent') therefore " +
                "report the actual declaring type, which may be an ancestor outside the from-subtree (or " +
                "to-subtree) when the entity is inherited. To restrict the result to physically-declared edges, " +
                "filter 'edges' where 'from_parent' is in the from-subtree (use list_descendants(from_id) to get " +
                "the ID set). " +
                "Common parameter patterns: " +
                "from_id + to_id (no relationship): see the full structural picture of detail-level coupling " +
                "between two subtrees. Returns all relationship kinds; the by_relationship summary tells you the " +
                "distribution. " +
                "from_id + to_id + relationship 'throws': drill into one specific kind of coupling (here, " +
                "exception throws). " +
                "from_id = root_id + to_id = some_annotation_type: global query — find every method anywhere " +
                "with this annotation. " +
                "from_id = root_id + to_id = some_field with relationship 'writes_field': global query — find " +
                "every method that writes this field. " +
                "When to use this vs. neighboring tools: " +
                "For the type-level evidence (which type-pairs are coupled), use outgoing_core_dependencies or " +
                "incoming_core_dependencies. This tool drills one level deeper, into the methods and fields that " +
                "realize those type-level edges. " +
                "For the methods declared on a single type (composition rather than dependency), use list_methods. " +
                "For everything about one specific method or field, use method_details or field_details. " +
                "Relationship kinds available: throws, calls, returns, parameter_type, reads_field, writes_field, " +
                "overrides, annotated_by, parameter_annotated_by, has_type, read_by, written_by."
    )
    fun detailDependencies(
        @ToolParam(
            description = "Source subtree root node ID. All types under this node are included as sources. " +
                    "Pass the root node ID for global queries."
        )
        fromId: Long,
        @ToolParam(description = "Target subtree root node ID. All types under this node are included as targets.")
        toId: Long,
        @ToolParam(
            description = "Optional relationship kind filter. One of: throws, calls, returns, " +
                    "parameter_type, reads_field, writes_field, overrides, annotated_by, parameter_annotated_by, " +
                    "has_type, read_by, written_by.",
            required = false
        )
        relationship: String?,
        @ToolParam(description = "Max edges to return (1-150, default 50).", required = false)
        limit: Int?
    ): Map<String, Any?> {

        // --- Validate relationship --------------------------------------------------------
        if (relationship != null && relationship.isNotBlank() && relationship !in RELATIONSHIP_KINDS) {
            return error(
                "INVALID_RELATIONSHIP",
                "Invalid relationship: '$relationship'. Allowed values: $RELATIONSHIP_KINDS",
                mapOf("invalid_value" to relationship)
            )
        }

        // --- Resolve scope nodes ----------------------------------------------------------
        val fromNode = resolveNodeOrRoot(fromId)
            ?: return error("NODE_NOT_FOUND", "Source node not found: $fromId. Re-resolve via find_node.", emptyMap())
        val toNode = resolveNodeOrRoot(toId)
            ?: return error("NODE_NOT_FOUND", "Target node not found: $toId. Re-resolve via find_node.", emptyMap())

        // --- Parameters & subtree expansion -----------------------------------------------
        val effectiveLimit = if (limit != null) limit.coerceIn(1, 150) else 50
        val effectiveRel = if (relationship != null && relationship.isNotBlank()) relationship else null
        val mp = getMetadataProvider()

        val fromTypeIds = collectSubtreeTypeIds(fromNode, mp)
        val toTypeIds = collectSubtreeTypeIds(toNode, mp)

        // Empty subtree on either side -> no edges, but still return a well-formed response.
        if (fromTypeIds.isEmpty() || toTypeIds.isEmpty()) {
            return emptyResult(fromNode, toNode, effectiveRel)
        }

        // --- Build & (optionally) log the Cypher ------------------------------------------
        val cypher = buildCypher(effectiveRel)
        if (logCypher) {
            log.info(
                "detail_dependencies cypher (relationship={}, fromTypes={}, toTypes={}):\n{}",
                effectiveRel, fromTypeIds, toTypeIds, cypher
            )
        }
        val queryResult = graphService.boltClient.syncExecCypherQuery(
            cypher, mapOf("fromTypes" to fromTypeIds, "toTypes" to toTypeIds)
        )

        // --- Aggregate result rows --------------------------------------------------------
        val allEdges = mutableListOf<SortableEdge>()
        val byRelationship = TreeMap<String, Int>()
        val sourceTypeCounts = linkedMapOf<Long, Int>()
        val nodeDisplay = linkedMapOf<Long, Array<String>>()  // id -> [name, fqn, kind]

        for (record in queryResult.records()) {
            val relName = record.get("relName").asString()

            val srcId = record.get("srcId").asLong()
            val srcTypeId = record.get("srcTypeId").asLong()
            val tgtId = record.get("tgtId").asLong()
            val tgtTypeId = record.get("tgtTypeId").asLong()
            val lineNumber = record.get("lineNumber").asLong(-1)

            // Stash display fields for the nodes map (first-write-wins via putIfAbsent).
            nodeDisplay.putIfAbsent(
                srcId, arrayOf(
                    record.get("srcName").asString(""),
                    record.get("srcFqn").asString(""),
                    deriveDetailKind(record.get("srcLabels").asList(org.neo4j.driver.Value::asString))
                )
            )
            nodeDisplay.putIfAbsent(
                srcTypeId, arrayOf(
                    record.get("srcTypeName").asString(""),
                    record.get("srcTypeFqn").asString(""),
                    mp.getKindFromLabels(record.get("srcTypeLabels").asList(org.neo4j.driver.Value::asString))
                )
            )
            nodeDisplay.putIfAbsent(
                tgtId, arrayOf(
                    record.get("tgtName").asString(""),
                    record.get("tgtFqn").asString(""),
                    deriveDetailKind(record.get("tgtLabels").asList(org.neo4j.driver.Value::asString))
                )
            )
            if (tgtTypeId != tgtId) {
                nodeDisplay.putIfAbsent(
                    tgtTypeId, arrayOf(
                        record.get("tgtTypeName").asString(""),
                        record.get("tgtTypeFqn").asString(""),
                        mp.getKindFromLabels(record.get("tgtTypeLabels").asList(org.neo4j.driver.Value::asString))
                    )
                )
            }

            // Build the edge (slim: IDs only; no embedded NodeRefs).
            val edge = linkedMapOf<String, Any?>(
                "from" to srcId,
                "from_parent" to srcTypeId,
                "to" to tgtId
            )
            if (tgtTypeId != tgtId) {
                edge["to_parent"] = tgtTypeId
            }
            edge["relationship"] = relName
            edge["location"] = if (lineNumber > 0) mapOf("line_number" to lineNumber) else null

            allEdges.add(
                SortableEdge(
                    edge, relName,
                    record.get("srcTypeFqn").asString(""),
                    record.get("srcName").asString(""),
                    if (lineNumber > 0) lineNumber else 0L
                )
            )

            byRelationship.merge(relName, 1, Integer::sum)
            sourceTypeCounts.merge(srcTypeId, 1, Integer::sum)
        }

        // --- Sort, truncate ---------------------------------------------------------------
        allEdges.sortWith(
            Comparator.comparing(SortableEdge::relationship)
                .thenComparing(SortableEdge::srcTypeFqn)
                .thenComparing(SortableEdge::srcName)
                .thenComparingLong(SortableEdge::lineNumber)
        )

        val totalEdges = allEdges.size
        val truncated = totalEdges > effectiveLimit
        val returnedEdges = allEdges.asSequence()
            .take(effectiveLimit)
            .map { it.edge }
            .toList()

        // --- by_source_type (top 10, with others_count when capped) -----------------------
        val totalSourceTypes = sourceTypeCounts.size
        val bySourceType = sourceTypeCounts.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (key, value) ->
                linkedMapOf<String, Any>("type" to key, "edge_count" to value)
            }

        // --- by_source_nodes / by_target_nodes (hierarchical-level context) ----------------
        val bySourceNodes = computeBySourceNodes(fromNode, toNode)
        val byTargetNodes = computeByTargetNodes(fromNode, toNode)

        // --- Assemble slim nodes map: only what the response actually references ---------
        val nodes = buildNodesMap(
            fromNode, toNode, bySourceType,
            bySourceNodes, byTargetNodes, returnedEdges, nodeDisplay
        )

        // --- Summary ----------------------------------------------------------------------
        val summary = linkedMapOf<String, Any?>(
            "total_edges" to totalEdges,
            "returned" to returnedEdges.size,
            "truncated" to truncated
        )
        // When the caller filtered to a relationship that produced no edges, the by_relationship
        // map should still surface that kind (with count 0) so the absence is explicit.
        if (effectiveRel != null && effectiveRel !in byRelationship) {
            byRelationship[effectiveRel] = 0
        }
        summary["by_relationship"] = byRelationship
        summary["by_source_type"] = bySourceType
        if (totalSourceTypes > bySourceType.size) {
            summary["others_count"] = totalSourceTypes - bySourceType.size
        }
        summary["by_source_nodes"] = bySourceNodes
        summary["by_target_nodes"] = byTargetNodes

        // --- Result -----------------------------------------------------------------------
        return linkedMapOf(
            "nodes" to nodes,
            "from_scope" to fromNode.identifier,
            "to_scope" to toNode.identifier,
            "edges" to returnedEdges,
            "summary" to summary
        )
    }

    // =====================================================================================
    // Relationship -> Cypher mapping
    // =====================================================================================

    /**
     * Shape of a relationship branch. Determines how the target side of the MATCH pattern is
     * written and how the target's declaring-type fields are projected.
     */
    private enum class BranchShape {
        /** `(src)<middle>(tgt:Type)` -- target IS a Type; no DECLARES join. */
        TO_TYPE,
        /** `(src)<middle>(tgt:Entity)<-[:DECLARES]-(tt:Type)` -- target is a Method/Field. */
        TO_ENTITY,
        /** `(src)<middle>(tgt:Method)<-[:DECLARES]-(tt:Type)` -- reverse direction (read_by/written_by). */
        REVERSE_FROM_ENTITY
    }

    /**
     * Declarative description of one Cartograph relationship and how it maps to a Cypher
     * branch. This table is the single source of truth for the Cartograph <-> jQAssistant
     * vocabulary mapping -- adding or remapping a relationship kind only requires editing
     * one row here.
     */
    private data class BranchSpec(
        val cartographKind: String,
        val srcLabel: String,
        val shape: BranchShape,
        val tgtLabel: String,
        val middle: String,
        val lineNumberExpr: String
    )

    /**
     * Pairs an edge map with its sort keys so the keys don't leak into the serialized
     * response. Sort key precedence is: relationship, source-type FQN, source name, line.
     */
    private data class SortableEdge(
        val edge: Map<String, Any?>,
        val relationship: String,
        val srcTypeFqn: String,
        val srcName: String,
        val lineNumber: Long
    )

    companion object {
        /**
         * The full Cartograph relationship vocabulary mapped to jQAssistant edge patterns.
         * Order here is irrelevant -- branches are emitted as a Cypher `UNION ALL` and
         * results are sorted in Java afterwards.
         */
        private val BRANCHES = listOf(
            // Method -> Type (direct edge)
            BranchSpec("throws", "Method", BranchShape.TO_TYPE, "Type",
                "-[r:THROWS]->", "src.firstLineNumber"),
            BranchSpec("returns", "Method", BranchShape.TO_TYPE, "Type",
                "-[r:RETURNS]->", "src.firstLineNumber"),
            // Field -> Type
            BranchSpec("has_type", "Field", BranchShape.TO_TYPE, "Type",
                "-[r:OF_TYPE]->", "null"),
            // Method -> Method / Field (with declarer join on target)
            BranchSpec("calls", "Method", BranchShape.TO_ENTITY, "Method",
                "-[r:INVOKES|VIRTUAL_INVOKES]->", "r.lineNumber"),
            BranchSpec("overrides", "Method", BranchShape.TO_ENTITY, "Method",
                "-[r:OVERRIDES]->", "src.firstLineNumber"),
            BranchSpec("reads_field", "Method", BranchShape.TO_ENTITY, "Field",
                "-[r:READS]->", "r.lineNumber"),
            BranchSpec("writes_field", "Method", BranchShape.TO_ENTITY, "Field",
                "-[r:WRITES]->", "r.lineNumber"),
            // Field <- Method (reverse direction)
            BranchSpec("read_by", "Field", BranchShape.REVERSE_FROM_ENTITY, "Method",
                "<-[r:READS]-", "r.lineNumber"),
            BranchSpec("written_by", "Field", BranchShape.REVERSE_FROM_ENTITY, "Method",
                "<-[r:WRITES]-", "r.lineNumber"),
            // Indirect target via intermediate node(s)
            BranchSpec("annotated_by", "Method", BranchShape.TO_TYPE, "Type",
                "-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->", "src.firstLineNumber"),
            BranchSpec("annotated_by", "Field", BranchShape.TO_TYPE, "Type",
                "-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->", "null"),
            BranchSpec("parameter_type", "Method", BranchShape.TO_TYPE, "Type",
                "-[:HAS]->(p:Parameter)-[:OF_TYPE]->", "src.firstLineNumber"),
            BranchSpec("parameter_annotated_by", "Method", BranchShape.TO_TYPE, "Type",
                "-[:HAS]->(p:Parameter)-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->", "src.firstLineNumber")
        )

        /** Distinct Cartograph relationship kinds -- derived from [BRANCHES] for validation. */
        private val RELATIONSHIP_KINDS: Set<String> = BRANCHES.map { it.cartographKind }.toSet()

        /** Schema-matching no-op Cypher -- used only when [BRANCHES] would yield no branch. */
        private const val EMPTY_CYPHER =
            "RETURN null AS srcId, null AS srcName, null AS srcFqn, null AS srcLabels, " +
            "null AS srcTypeId, null AS srcTypeName, null AS srcTypeFqn, null AS srcTypeLabels, " +
            "null AS tgtId, null AS tgtName, null AS tgtFqn, null AS tgtLabels, " +
            "null AS tgtTypeId, null AS tgtTypeName, null AS tgtTypeFqn, null AS tgtTypeLabels, " +
            "null AS relName, null AS lineNumber LIMIT 0"
    }

    /**
     * Assembles the full Cypher statement as a `UNION ALL` of one branch per matching
     * [BranchSpec]. Each branch traverses inheritance on both the source and target
     * declarer joins (zero or more `EXTENDS|IMPLEMENTS` hops); `RETURN DISTINCT`
     * collapses duplicates that arise when the from / to subtrees include both a type and
     * one of its ancestors.
     */
    private fun buildCypher(relationship: String?): String {
        val branches = BRANCHES
            .filter { relationship == null || it.cartographKind == relationship }
            .map { renderBranch(it) }

        if (branches.isEmpty()) {
            return EMPTY_CYPHER
        }
        return branches.joinToString(" UNION ALL ")
    }

    /** Renders one branch from a [BranchSpec]. */
    private fun renderBranch(b: BranchSpec): String {
        val srcDeclarer = "(st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:${b.srcLabel})"
        val srcProj = "id(so) AS srcTypeId, so.name AS srcTypeName, so.fqn AS srcTypeFqn, labels(so) AS srcTypeLabels"

        val tgtMatch: String
        val tgtProj: String
        val whereTgt: String
        if (b.shape == BranchShape.TO_TYPE) {
            tgtMatch = "(tgt:${b.tgtLabel})"
            tgtProj = "id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels"
            whereTgt = "id(tgt) IN \$toTypes"
        } else {
            tgtMatch = "(tgt:${b.tgtLabel})<-[:DECLARES]-(to:Type)<-[:EXTENDS|IMPLEMENTS*0..]-(tt:Type)"
            tgtProj = "id(to) AS tgtTypeId, to.name AS tgtTypeName, to.fqn AS tgtTypeFqn, labels(to) AS tgtTypeLabels"
            whereTgt = "id(tt) IN \$toTypes"
        }

        return "MATCH $srcDeclarer${b.middle}$tgtMatch " +
                "WHERE id(st) IN \$fromTypes AND $whereTgt " +
                "RETURN DISTINCT " +
                "id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels, " +
                "$srcProj, " +
                "id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels, " +
                "$tgtProj, " +
                "'${b.cartographKind}' AS relName, ${b.lineNumberExpr} AS lineNumber"
    }

    // =====================================================================================
    // Subtree resolution
    // =====================================================================================

    private fun resolveNodeOrRoot(nodeId: Long): HGNode? {
        val node = graphService.rootNode.lookupNode(nodeId)
        if (node != null) return node
        val rootId = graphService.rootNode.identifier
        if (rootId is Long && rootId == nodeId) {
            return graphService.rootNode
        }
        return null
    }

    private fun collectSubtreeTypeIds(node: HGNode, mp: INodeMetadataProvider): List<Long> {
        val typeKinds = setOf("Class", "Interface", "Enum", "Annotation", "Record")
        val result = mutableListOf<Long>()
        collectSubtreeTypeIdsRecursive(node, typeKinds, mp, result)
        return result
    }

    private fun collectSubtreeTypeIdsRecursive(
        node: HGNode, typeKinds: Set<String>,
        mp: INodeMetadataProvider, result: MutableList<Long>
    ) {
        if (mp.getKind(node) in typeKinds) {
            result.add(node.identifier as Long)
        }
        for (child in node.children) {
            collectSubtreeTypeIdsRecursive(child, typeKinds, mp, result)
        }
    }

    // =====================================================================================
    // Response assembly helpers
    // =====================================================================================

    /**
     * Builds the slim `nodes` map containing exactly the nodes referenced by the
     * truncated edge set, `by_source_type` entries, `by_source_nodes` entries,
     * `by_target_nodes` entries, and the two scope endpoints.
     */
    private fun buildNodesMap(
        fromNode: HGNode, toNode: HGNode,
        bySourceType: List<Map<String, Any>>,
        bySourceNodes: List<Map<String, Any?>>,
        byTargetNodes: List<Map<String, Any?>>,
        returnedEdges: List<Map<String, Any?>>,
        nodeDisplay: Map<Long, Array<String>>
    ): Map<String, Any> {
        val referenced = linkedSetOf<Long>()
        referenced.add(fromNode.identifier as Long)
        referenced.add(toNode.identifier as Long)
        for (e in bySourceType) {
            referenced.add(e["type"] as Long)
        }
        for (edge in returnedEdges) {
            referenced.add(edge["from"] as Long)
            referenced.add(edge["from_parent"] as Long)
            referenced.add(edge["to"] as Long)
            val toParent = edge["to_parent"]
            if (toParent is Long) referenced.add(toParent)
        }

        val nodes = linkedMapOf<String, Any>()
        // Scope endpoints first
        putSlimNode(nodes, fromNode)
        putSlimNode(nodes, toNode)
        // by_source_nodes / by_target_nodes entries
        for (entry in bySourceNodes) {
            putSlimNode(nodes, graphService.rootNode.lookupNode(entry["node"] as Long))
        }
        for (entry in byTargetNodes) {
            putSlimNode(nodes, graphService.rootNode.lookupNode(entry["node"] as Long))
        }
        for (id in referenced) {
            val disp = nodeDisplay[id]
            if (disp != null) {
                putSlimNode(nodes, id, disp[0], disp[1], disp[2])
            }
        }
        return nodes
    }

    /** Build the empty-edges result for the early-return path. */
    private fun emptyResult(fromNode: HGNode, toNode: HGNode, effectiveRel: String?): Map<String, Any?> {
        val bySourceNodes = computeBySourceNodes(fromNode, toNode)
        val byTargetNodes = computeByTargetNodes(fromNode, toNode)
        val nodes = linkedMapOf<String, Any>()
        putSlimNode(nodes, fromNode)
        putSlimNode(nodes, toNode)
        for (entry in bySourceNodes) {
            putSlimNode(nodes, graphService.rootNode.lookupNode(entry["node"] as Long))
        }
        for (entry in byTargetNodes) {
            putSlimNode(nodes, graphService.rootNode.lookupNode(entry["node"] as Long))
        }
        val summary = linkedMapOf<String, Any?>(
            "total_edges" to 0,
            "returned" to 0,
            "truncated" to false,
            "by_relationship" to if (effectiveRel != null) mapOf(effectiveRel to 0) else emptyMap<String, Int>(),
            "by_source_type" to emptyList<Any>(),
            "by_source_nodes" to bySourceNodes,
            "by_target_nodes" to byTargetNodes
        )
        return linkedMapOf(
            "nodes" to nodes,
            "from_scope" to fromNode.identifier,
            "to_scope" to toNode.identifier,
            "edges" to emptyList<Any>(),
            "summary" to summary
        )
    }

    /**
     * Computes the `by_source_nodes` summary: drills down from `fromNode` through
     * single-child levels to the first level with more than one child, then computes the
     * aggregated outgoing dependency weight from each child at that level to `toNode`.
     */
    private fun computeBySourceNodes(fromNode: HGNode, toNode: HGNode): List<Map<String, Any?>> {
        val children = drillDownToMultiChildLevel(fromNode)
        if (children.isEmpty()) return emptyList()
        return children
            .map { child ->
                val dep: HGAggregatedDependency? = child.getOutgoingDependenciesTo(toNode)
                val weight = dep?.aggregatedWeight ?: 0
                child to weight
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { (child, weight) ->
                linkedMapOf<String, Any?>("node" to (child.identifier as Long), "aggregated_weight" to weight)
            }
    }

    /**
     * Computes the `by_target_nodes` summary: drills down from `toNode` through
     * single-child levels to the first level with more than one child, then computes the
     * aggregated incoming dependency weight from `fromNode` to each child at that level.
     */
    private fun computeByTargetNodes(fromNode: HGNode, toNode: HGNode): List<Map<String, Any?>> {
        val children = drillDownToMultiChildLevel(toNode)
        if (children.isEmpty()) return emptyList()
        return children
            .map { child ->
                val dep: HGAggregatedDependency? = fromNode.getOutgoingDependenciesTo(child)
                val weight = dep?.aggregatedWeight ?: 0
                child to weight
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { (child, weight) ->
                linkedMapOf<String, Any?>("node" to (child.identifier as Long), "aggregated_weight" to weight)
            }
    }

    /**
     * Walks down from `node` through single-child levels until a node with more than
     * one child is found, then returns that node's children.
     */
    private fun drillDownToMultiChildLevel(node: HGNode): List<HGNode> {
        var current = node
        while (current.children.size == 1) {
            current = current.children[0]
        }
        return current.children
    }

    private fun error(code: String, message: String, extra: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("error" to code, "message" to message).apply { putAll(extra) }
}
