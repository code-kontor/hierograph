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

import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.mcp.server.core.HierarchicalGraphService
import io.hierograph.mcp.javaspec.JavaEdgeAttributes
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantNodeMetadataProvider
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.mcp.server.core.pagination.DataHashProvider
import io.hierograph.mcp.server.core.pagination.PageResult
import io.hierograph.mcp.server.core.pagination.PaginationSpec
import io.hierograph.mcp.server.core.pagination.Paginator
import io.hierograph.mcp.server.core.pagination.QueryHash
import io.hierograph.mcp.server.tools.detail.IDetailDependencies
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
    private val detailDependenciesTool: IDetailDependencies,
    private val dataHashProvider: DataHashProvider
) {

    @Tool(
        name = "outgoing_dependencies",
        description = "[Dependency analysis] " +
                "Return the edges from a source subtree to a target subtree. This is the evidence " +
                "tool — use after an aggregated query reveals a dependency of interest. " +
                "detail_level='type' (default) returns type-to-type edges from the in-memory model (fast). " +
                "detail_level='detail' returns method/field-level edges with source locations (slower). " +
                "The 'relationship' filter is only valid at detail level. " +
                "The by_target, by_source_type, and by_attribute/by_relationship summaries are computed " +
                "over the ENTIRE result set, independent of page size — they give the shape without " +
                "processing all edges. " +
                "WARNING: on a hub node a full page of type-level edges can be hundreds of KB and " +
                "overflow the caller's context. If you only need the ranking or attribute breakdown " +
                "(the common case, especially when to_id is omitted), pass limit=1 and read the " +
                "summary — do NOT page through edges you won't use. " +
                "Direction: this tool shows what the source uses of the target. " +
                "For the reverse, use incoming_dependencies. " +
                "At type level, to_id is optional: omit it to return ALL outgoing dependencies of " +
                "from_id (every type-level edge to anywhere in the graph) — answering 'show me the " +
                "dependencies of X'. That question is almost always answered by the by_target / " +
                "by_source_type summary, not by the edge list — request a minimal page (limit=1). " +
                "The summary always includes 'by_target': the depended-upon " +
                "types ranked by summed weight over the full result set (whole graph when to_id is " +
                "omitted, within the to_id subtree otherwise). to_id is required at detail level."
    )
    fun outgoingDependencies(
        @ToolParam(description = "Source subtree root ID.")
        fromId: Long,
        @ToolParam(
            description = "Target subtree root ID. Optional at type level — omit to return ALL " +
                    "outgoing dependencies of from_id (every edge to anywhere). Required at detail level.",
            required = false
        )
        toId: Long?,
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
            description = "Maximum edges per page. Default: 100 (type) / 80 (detail). Caps: 400 / 250. " +
                    "On a hub node even 100 type-level edges can exceed ~50 KB — pass limit=1 when you " +
                    "only need the summary (by_target / by_source_type / by_attribute).",
            required = false
        )
        limit: Int?,
        @ToolParam(
            description = "Opaque pagination cursor from a previous response's next_cursor. " +
                    "Pass it to retrieve the next page; omit to start from the first page. " +
                    "When continuing, keep the other parameters identical to the original call. " +
                    "If the result set is larger than you need, prefer the full-set summary (limit=1) or " +
                    "narrowing the query (a smaller subtree, or detail-level relationship filter) over " +
                    "paginating through all of it.",
            required = false
        )
        cursor: String?
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
            typeLevelDependencies(fromId, toId, limit, cursor, outgoing = true, spec = TYPE_SPEC)
        } else if (toId == null) {
            // detail level requires an explicit target — the open form is type-level only
            mapOf(
                "error" to mapOf(
                    "code" to "INVALID_PARAMETER",
                    "message" to "to_id is required at detail_level='detail'. The open form " +
                            "(omitted to_id, returning all dependencies) is supported only at detail_level='type'.",
                    "recovery" to "Provide a to_id, or set detail_level='type' to query all dependencies of from_id."
                )
            )
        } else {
            // Delegate to the detail_dependencies provider; it owns detail-level pagination.
            val effectiveRel = if (relationship.isNullOrBlank()) null else relationship
            detailDependenciesTool.detailDependencies(fromId, toId, effectiveRel, limit, cursor, DETAIL_SPEC)
        }
    }

    // ── type-level implementation (shared with IncomingDependenciesTool) ─

    internal fun typeLevelDependencies(
        fromId: Long,
        toId: Long?,
        limit: Int?,
        cursor: String?,
        outgoing: Boolean,
        spec: PaginationSpec
    ): Map<String, Any?> {

        // ── resolve from node (always required) ────────────────────────
        val fromNode = graphService.model.lookupNode(fromId)
            ?: return nodeNotFound(fromId)
        validateNodeKind(fromNode)?.let { return it }

        // ── resolve to node (optional: omitted = unconstrained) ────────
        // When toId is null the counterpart side is left open and every
        // matching edge is returned ("show me the dependencies of X" /
        // "what depends on X"). When provided, edges are filtered to the
        // toId subtree's types.
        var toNode: HGNode? = null
        var otherSideTypeIds: Set<Any>? = null
        if (toId != null) {
            toNode = graphService.model.lookupNode(toId)
                ?: return nodeNotFound(toId)
            validateNodeKind(toNode)?.let { return it }
            otherSideTypeIds = collectTypeIds(toNode)
        }

        // Query hash covers the parameters that define the result set — not limit or cursor.
        // detail_level and direction are baked in so a type-level cursor can never be mistaken
        // for a detail-level one, nor an outgoing cursor for an incoming one beyond the tool name.
        val queryHash = QueryHash.of(
            mapOf(
                "fromId" to fromId,
                "toId" to toId,
                "detailLevel" to "type",
                "direction" to if (outgoing) "outgoing" else "incoming"
            )
        )

        // ── collect type-level edges ───────────────────────────────────
        // Anchor on from_id's types. For the outgoing direction walk each
        // anchor type's outgoing core dependencies; for incoming, its incoming
        // ones. Each HGCoreDependency already encodes (from -> to) in
        // depender->depended-upon orientation, so the edge endpoints are
        // dep.from / dep.to in both directions. The "other" endpoint (the one
        // not on the anchor side) is matched against otherSideTypeIds when
        // to_id is provided; when omitted every edge is kept.
        val anchorTypes = collectTypes(fromNode)

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
        // Summed edge weight per depended-upon endpoint (dep.to). Used only by the
        // open form's by_target rollup; accumulated over the full result set.
        val targetWeights = linkedMapOf<Any, Int>()

        for (anchorType in anchorTypes) {
            val deps = if (outgoing) anchorType.outgoingCoreDependencies
                       else anchorType.incomingCoreDependencies
            for (dep in deps) {
                val otherEndpoint = if (outgoing) dep.to else dep.from
                if (otherSideTypeIds == null || otherEndpoint.identifier in otherSideTypeIds) {
                    allEdges.add(TypeEdge(dep.from, dep.to, dep.weight, dep.attributesBitmap))

                    for ((pos, name) in JavaEdgeAttributes.ALL) {
                        if (JavaEdgeAttributes.isSet(dep.attributesBitmap, pos)) {
                            byAttribute.merge(name, 1) { a, b -> a + b }
                        }
                    }
                    sourceTypeCounts.merge(dep.from.identifier, 1) { a, b -> a + b }
                    targetWeights.merge(dep.to.identifier, dep.weight) { a, b -> a + b }
                }
            }
        }

        // ── sort: source qualified name, then target qualified name ────
        // Identifier tiebreakers make this a total order, so the result sequence is reproducible
        // and pagination cursors stay valid across calls.
        allEdges.sortWith(
            compareBy(
                { JQAssistantNodeMetadataProvider.getQualifiedName(it.from) },
                { JQAssistantNodeMetadataProvider.getQualifiedName(it.to) },
                { it.from.identifier.toString() },
                { it.to.identifier.toString() }
            )
        )

        // ── paginate ───────────────────────────────────────────────────
        val pageResult = Paginator.paginate(
            allItems = allEdges,
            spec = spec,
            queryHash = queryHash,
            dataHash = dataHashProvider.dataHash,
            cursor = cursor,
            limit = limit
        )
        val pageData = when (pageResult) {
            is PageResult.Failed -> return pageResult.error.toResponse()
            is PageResult.Page -> pageResult
        }
        val total = pageData.total
        val truncated = pageData.truncated
        val page = pageData.items

        // ── build slim nodes map ───────────────────────────────────────
        val nodes = linkedMapOf<String, Any>()
        nodeRefFactory.putSlimNode(nodes, fromNode)
        toNode?.let { nodeRefFactory.putSlimNode(nodes, it) }
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

        val summary = linkedMapOf<String, Any?>(
            "total" to total,
            "returned" to page.size,
            "truncated" to truncated,
            "by_attribute" to byAttribute,
            "by_source_type" to bySourceType
        )

        // Rank the depended-upon endpoints (dep.to) by summed weight, top 10.
        // Computed over the FULL result set — not just the returned page — so the
        // ranking is complete even when edges are truncated. In the open form
        // (to_id omitted) this spans the whole graph; in the constrained form it
        // ranks within the to_id subtree. For incoming, dep.to is the from_id type,
        // so this ranks the most heavily used types within from_id; for outgoing, it
        // ranks what from_id leans on most.
        summary["by_target"] = targetWeights.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (id, weight) ->
                linkedMapOf<String, Any?>("id" to id, "weight" to weight)
            }

        val response = linkedMapOf<String, Any?>(
            "nodes" to nodes,
            "edges" to edges,
            "summary" to summary
        )
        // next_cursor is present iff more results follow this page (omitted, not null, on the last page).
        pageData.nextCursor?.let { response["next_cursor"] = it }
        return response
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun collectTypes(node: HGNode): List<HGNode> {
        val hierarchy = graphService.model.hierarchy
        val types = mutableListOf<HGNode>()
        if (JavaKinds.isTypeKind(node.kind)) types.add(node)
        hierarchy.traverse(node) { n ->
            if (JavaKinds.isTypeKind(n.kind)) types.add(n)
        }
        return types
    }

    private fun collectTypeIds(node: HGNode): Set<Any> {
        val hierarchy = graphService.model.hierarchy
        val ids = mutableSetOf<Any>()
        if (JavaKinds.isTypeKind(node.kind)) ids.add(node.identifier)
        hierarchy.traverse(node) { n ->
            if (JavaKinds.isTypeKind(n.kind)) ids.add(n.identifier)
        }
        return ids
    }

    internal fun validateNodeKind(node: HGNode): Map<String, Any?>? {
        val kind = node.kind
        if (kind == JavaKinds.METHOD || kind == JavaKinds.FIELD) {
            val declaringType = graphService.model.hierarchy.parentOf(node)
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

    companion object {
        /** Type-level pagination for outgoing_dependencies (~350 bytes/edge): default 100, cap 400. */
        val TYPE_SPEC = PaginationSpec(tool = "outgoing_dependencies", defaultLimit = 100, maxLimit = 400, bytesPerItem = 350)

        /** Detail-level pagination for outgoing_dependencies (~550 bytes/edge): default 80, cap 250. */
        val DETAIL_SPEC = PaginationSpec(tool = "outgoing_dependencies", defaultLimit = 80, maxLimit = 250, bytesPerItem = 550)
    }
}
