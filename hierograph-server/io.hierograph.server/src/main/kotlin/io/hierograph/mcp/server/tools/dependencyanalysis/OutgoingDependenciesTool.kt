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
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.server.graphservice.pagination.DataHashProvider
import io.hierograph.mcp.server.pagination.PageResult
import io.hierograph.mcp.server.pagination.PaginationSpec
import io.hierograph.mcp.server.pagination.Paginator
import io.hierograph.mcp.server.pagination.QueryHash
import io.hierograph.mcp.server.tools.detail.IDetailDependencies
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `outgoing_dependencies`
 *
 * Returns edges from a source subtree to a target subtree at the requested zoom level.
 * - `detailLevel: "type"` (default): type-to-type edges, in-memory, fast
 * - `detailLevel: "detail"`: member-level edges with source locations, read from the detail snapshot
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
                "detailLevel='type' (default) returns type-to-type edges from the in-memory model (fast). " +
                "detailLevel='detail' returns method/field-level edges with source locations (slower). " +
                "The 'relationship' filter is only valid at detail level. " +
                "The by_target, by_source_type, and by_attribute/by_relationship summaries are computed " +
                "over the ENTIRE result set, independent of page size — they give the shape without " +
                "processing all edges. " +
                "WARNING: on a hub node a full page of type-level edges can be hundreds of KB and " +
                "overflow the caller's context. If you only need the ranking or attribute breakdown " +
                "(the common case, especially when toId is omitted), pass limit=1 and read the " +
                "summary — do NOT page through edges you won't use. " +
                "Direction: this tool shows what the source uses of the target. " +
                "For the reverse, use incoming_dependencies. " +
                "At type level, toId is optional: omit it to return ALL outgoing dependencies of " +
                "fromId (every type-level edge to anywhere in the graph) — answering 'show me the " +
                "dependencies of X'. That question is almost always answered by the by_target / " +
                "by_source_type summary, not by the edge list — request a minimal page (limit=1). " +
                "The summary always includes 'by_target': the depended-upon " +
                "types ranked by summed weight over the full result set (whole graph when toId is " +
                "omitted, within the toId subtree otherwise). toId is required at detail level."
    )
    fun outgoingDependencies(
        @ToolParam(description = "Source subtree root ID.")
        fromId: Long,
        @ToolParam(
            description = "Target subtree root ID. Optional at type level — omit to return ALL " +
                    "outgoing dependencies of fromId (every edge to anywhere). Required at detail level.",
            required = false
        )
        toId: Long?,
        @ToolParam(
            description = "Zoom level: 'type' (default, in-memory) or 'detail' (member-level, with source lines).",
            required = false
        )
        detailLevel: String?,
        @ToolParam(
            description = "Filter to a specific detail-level relationship kind. Only valid when detailLevel='detail'.",
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

        // ── validate detailLevel ──────────────────────────────────────
        if (level !in listOf("type", "detail")) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_PARAMETER",
                    "message" to "Invalid detailLevel: '$level'. Must be 'type' or 'detail'.",
                    "recovery" to "Use 'type' for type-to-type edges or 'detail' for method/field-level edges."
                )
            )
        }

        // ── validate relationship at type level ────────────────────────
        if (level == "type" && !relationship.isNullOrBlank()) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_PARAMETER",
                    "message" to "The 'relationship' parameter is only valid at detailLevel='detail'. " +
                            "At type level, edges carry attribute flags but cannot be filtered by detail-level relationship.",
                    "recovery" to "Either remove the 'relationship' parameter, or set detailLevel='detail' to filter by relationship kind."
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
                    "message" to "toId is required at detailLevel='detail'. The open form " +
                            "(omitted toId, returning all dependencies) is supported only at detailLevel='type'.",
                    "recovery" to "Provide a toId, or set detailLevel='type' to query all dependencies of fromId."
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
        // detailLevel and direction are baked in so a type-level cursor can never be mistaken
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
        // Anchor on fromId's types. For the outgoing direction walk each
        // anchor type's outgoing core dependencies; for incoming, its incoming
        // ones. Each HGCoreDependency already encodes (from -> to) in
        // depender->depended-upon orientation, so the edge endpoints are
        // dep.from / dep.to in both directions. The "other" endpoint (the one
        // not on the anchor side) is matched against otherSideTypeIds when
        // toId is provided; when omitted every edge is kept.
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
                { NodeMetadata.getQualifiedName(it.from) },
                { NodeMetadata.getQualifiedName(it.to) },
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
        // (toId omitted) this spans the whole graph; in the constrained form it
        // ranks within the toId subtree. For incoming, dep.to is the fromId type,
        // so this ranks the most heavily used types within fromId; for outgoing, it
        // ranks what fromId leans on most.
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
                    "message" to "This tool operates on type-level dependencies at detailLevel='type'. " +
                            "The node is a $kind, not a type.",
                    "actual_kind" to kind.toString(),
                    "declaring_type" to if (declaringType != null)
                        nodeRefFactory.minimalNodeRef(declaringType) else null,
                    "recovery" to "Pass the declaring type's id (${declaringType?.identifier}), " +
                            "or use detailLevel='detail' on the declaring type for member-level evidence."
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
