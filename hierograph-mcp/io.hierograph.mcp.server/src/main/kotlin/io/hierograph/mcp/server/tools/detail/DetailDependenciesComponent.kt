/*
 * Copyright 2024 Gerd Wuetherich
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
package io.hierograph.mcp.server.tools.detail

import org.slf4j.LoggerFactory
import io.hierograph.hierarchicalgraph.core.model.HGAggregatedDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbNodeSource
import io.hierograph.mcp.server.core.HierarchicalGraphService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Comparator
import java.util.TreeMap

/**
 * Implementation of the `detail_dependencies` tool. See the spec at
 * `docs/tool-specifications/detail-level/hierograph-detail-dependencies-spec.md`.
 *
 * Returns method/field-level edges between a source subtree and a target subtree, with
 * optional filtering by Hierograph relationship kind. The Hierograph vocabulary is mapped
 * to jQAssistant edge labels by the [BRANCHES] table -- the single place to look
 * when adding, renaming, or remapping a relationship kind.
 */
@Component
class DetailDependenciesComponent(graphService: HierarchicalGraphService) : AbstractDetailTool(graphService) {

    private val log = LoggerFactory.getLogger(DetailDependenciesComponent::class.java)

    /**
     * When true, the Cypher statement built for each invocation is logged at INFO together
     * with the effective `relationship` filter and the resolved `fromTypes` /
     * `toTypes` parameters. Set via
     * `hierograph.mcp.tools.detail-dependencies.log-cypher=true` in
     * `application.properties`; defaults to `false`. Each detail tool gets its
     * own flag as the need arises.
     */
    @Value("\${hierograph.mcp.tools.detail-dependencies.log-cypher:false}")
    private var logCypher: Boolean = false

    /**
     * Returns method/field-level edges between two subtrees with optional relationship filtering.
     * Called internally by [org.slizaa.mcp.core.mcp.dependencyanalysis.OutgoingDependenciesTool] and [org.slizaa.mcp.core.mcp.dependencyanalysis.IncomingDependenciesTool] at detail level.
     */
    fun detailDependencies(
        fromId: Long,
        toId: Long,
        relationship: String?,
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

        val fromScope = resolveScopeInfo(fromNode, mp)
        val toScope = resolveScopeInfo(toNode, mp)

        // Empty subtree on either side -> no edges, but still return a well-formed response.
        if (fromScope.typeIds.isEmpty() || toScope.typeIds.isEmpty()) {
            return emptyResult(fromNode, toNode, effectiveRel)
        }

        // --- Build & (optionally) log the Cypher ------------------------------------------
        val cypher = buildCypher(effectiveRel, fromScope, toScope)
        val params = mutableMapOf<String, Any>(
            "fromTypes" to fromScope.typeIds,
            "toTypes" to toScope.typeIds
        )
        if (fromScope.memberId != null) params["fromMemberId"] = fromScope.memberId
        if (toScope.memberId != null) params["toMemberId"] = toScope.memberId

        if (logCypher) {
            log.info(
                "detail_dependencies cypher (relationship={}, fromScope={}, toScope={}):\n{}",
                effectiveRel, fromScope, toScope, cypher
            )
        }
        val queryResult = graphService.boltClient.syncExecCypherQuery(cypher, params)

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
     * Declarative description of one Hierograph relationship and how it maps to a Cypher
     * branch. This table is the single source of truth for the Hierograph <-> jQAssistant
     * vocabulary mapping -- adding or remapping a relationship kind only requires editing
     * one row here.
     */
    private data class BranchSpec(
        val hierographKind: String,
        val srcLabel: String,
        val shape: BranchShape,
        val tgtLabel: String,
        val middle: String,
        val lineNumberExpr: String
    )

    /**
     * Describes how a scope parameter (from_id / to_id) was resolved. For container nodes
     * (module, package, type), [typeIds] holds the expanded set of type IDs and [memberId]
     * is null. For member nodes (method, field), [typeIds] holds the declaring type's ID,
     * [memberId] is the member's node ID, and [memberNeoLabel] is the Neo4j label
     * ("Method" or "Field") used for branch filtering.
     */
    private data class ScopeInfo(
        val typeIds: List<Long>,
        val memberId: Long? = null,
        val memberNeoLabel: String? = null
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
         * The full Hierograph relationship vocabulary mapped to jQAssistant edge patterns.
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

        /** Distinct Hierograph relationship kinds -- derived from [BRANCHES] for validation. */
        private val RELATIONSHIP_KINDS: Set<String> = BRANCHES.map { it.hierographKind }.toSet()

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
     *
     * When [fromScope] or [toScope] is a member scope, branches are filtered to those
     * whose src/tgt label matches the member's Neo4j label, and the Cypher anchors
     * directly on the member node instead of expanding from the type level.
     */
    private fun buildCypher(relationship: String?, fromScope: ScopeInfo, toScope: ScopeInfo): String {
        val branches = BRANCHES
            .filter { relationship == null || it.hierographKind == relationship }
            .mapNotNull { renderBranch(it, fromScope, toScope) }

        if (branches.isEmpty()) {
            return EMPTY_CYPHER
        }
        return branches.joinToString(" UNION ALL ")
    }

    /**
     * Renders one branch from a [BranchSpec], taking member scopes into account.
     * Returns `null` when the branch is inapplicable (e.g., source is a Field but
     * the branch expects a Method source, or target is a member but the branch
     * targets a Type).
     */
    private fun renderBranch(b: BranchSpec, fromScope: ScopeInfo, toScope: ScopeInfo): String? {

        // --- Filter inapplicable branches ------------------------------------------------

        // When from_id is a member, only include branches whose srcLabel matches
        if (fromScope.memberNeoLabel != null && b.srcLabel != fromScope.memberNeoLabel) return null

        // When to_id is a member and the branch targets a Type (e.g. throws, returns),
        // a member can't be a Type target → skip
        if (toScope.memberId != null && b.shape == BranchShape.TO_TYPE) return null

        // When to_id is a member, only include branches whose tgtLabel matches
        if (toScope.memberNeoLabel != null && b.tgtLabel != toScope.memberNeoLabel) return null

        // --- Source side -----------------------------------------------------------------

        val srcDeclarer: String
        val srcWhere: String
        if (fromScope.memberId != null) {
            // Member scope: anchor on the specific member, resolve declaring type for projection
            srcDeclarer = "(so:Type)-[:DECLARES]->(src:${b.srcLabel})"
            srcWhere = "id(src) = \$fromMemberId"
        } else {
            // Container scope: traverse inheritance from type set
            srcDeclarer = "(st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:${b.srcLabel})"
            srcWhere = "id(st) IN \$fromTypes"
        }
        val srcProj = "id(so) AS srcTypeId, so.name AS srcTypeName, so.fqn AS srcTypeFqn, labels(so) AS srcTypeLabels"

        // --- Target side -----------------------------------------------------------------

        val tgtMatch: String
        val tgtProj: String
        val whereTgt: String
        if (b.shape == BranchShape.TO_TYPE) {
            // TO_TYPE: target is a Type node (already filtered out member toScope above)
            tgtMatch = "(tgt:${b.tgtLabel})"
            tgtProj = "id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels"
            whereTgt = "id(tgt) IN \$toTypes"
        } else if (toScope.memberId != null) {
            // TO_ENTITY / REVERSE_FROM_ENTITY with member scope: anchor on the specific member
            tgtMatch = "(tgt:${b.tgtLabel})<-[:DECLARES]-(to:Type)"
            tgtProj = "id(to) AS tgtTypeId, to.name AS tgtTypeName, to.fqn AS tgtTypeFqn, labels(to) AS tgtTypeLabels"
            whereTgt = "id(tgt) = \$toMemberId"
        } else {
            // TO_ENTITY / REVERSE_FROM_ENTITY with container scope: traverse inheritance
            tgtMatch = "(tgt:${b.tgtLabel})<-[:DECLARES]-(to:Type)<-[:EXTENDS|IMPLEMENTS*0..]-(tt:Type)"
            tgtProj = "id(to) AS tgtTypeId, to.name AS tgtTypeName, to.fqn AS tgtTypeFqn, labels(to) AS tgtTypeLabels"
            whereTgt = "id(tt) IN \$toTypes"
        }

        return "MATCH $srcDeclarer${b.middle}$tgtMatch " +
                "WHERE $srcWhere AND $whereTgt " +
                "RETURN DISTINCT " +
                "id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels, " +
                "$srcProj, " +
                "id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels, " +
                "$tgtProj, " +
                "'${b.hierographKind}' AS relName, ${b.lineNumberExpr} AS lineNumber"
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

    /**
     * Resolves a scope node into a [ScopeInfo]. For container nodes (module, package, type),
     * expands to contained type IDs. For member nodes (method, field), returns the declaring
     * type's ID plus the member's own ID and Neo4j label for Cypher anchoring.
     */
    private fun resolveScopeInfo(node: HGNode, mp: INodeMetadataProvider): ScopeInfo {
        val neoLabel = neoLabelForMember(node)
        if (neoLabel != null) {
            // Member node: use declaring type (parent) for the type set
            val parentNode = node.parent ?: return ScopeInfo(emptyList())
            val parentTypeIds = collectSubtreeTypeIds(parentNode, mp)
            return ScopeInfo(parentTypeIds, node.identifier as Long, neoLabel)
        }
        // Container node: expand subtree to type IDs
        return ScopeInfo(collectSubtreeTypeIds(node, mp))
    }

    /**
     * Returns the Neo4j label ("Method" or "Field") if the node is a member, or `null`
     * if it is a container (module, package, type). Constructors are mapped to "Method"
     * since they carry the Neo4j `Method` label.
     */
    private fun neoLabelForMember(node: HGNode): String? {
        val src = node.nodeSource as? GraphDbNodeSource ?: return null
        val labels = src.labels
        return when {
            "Field" in labels -> "Field"
            "Method" in labels -> "Method"  // includes constructors (Constructor + Method labels)
            else -> null
        }
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
            graphService.rootNode.lookupNode(entry["node"] as Long)?.let { putSlimNode(nodes, it) }
        }
        for (entry in byTargetNodes) {
            graphService.rootNode.lookupNode(entry["node"] as Long)?.let { putSlimNode(nodes, it) }
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
            graphService.rootNode.lookupNode(entry["node"] as Long)?.let { putSlimNode(nodes, it) }
        }
        for (entry in byTargetNodes) {
            graphService.rootNode.lookupNode(entry["node"] as Long)?.let { putSlimNode(nodes, it) }
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