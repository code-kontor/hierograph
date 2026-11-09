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
package io.hierograph.mcp.server.tools.detail

import io.hierograph.graphstore.jvm.detail.DetailEnricher
import io.hierograph.graphstore.jvm.detail.DetailQuery
import io.hierograph.graphstore.jvm.detail.DetailQueryEngine
import io.hierograph.graphstore.jvm.detail.NodeDisplay
import io.hierograph.graphstore.jvm.model.DetailRef
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.graphstore.jvm.model.JavaEdgeAttributes
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.pagination.DataHashProvider
import io.hierograph.mcp.server.pagination.PageResult
import io.hierograph.mcp.server.pagination.PaginationSpec
import io.hierograph.mcp.server.pagination.Paginator
import io.hierograph.mcp.server.pagination.QueryHash
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * The detail level of `outgoing_dependencies` and `incoming_dependencies`. It resolves the scope over
 * the in-memory model, reads member-level edges from `detail_refs.parquet` via [DetailQueryEngine]
 * plus the load-derived edges, and maps them into the response shape using content-addressed
 * [NodeHandle]s for every endpoint.
 *
 * Scopes are containers (root / module / package / type). A method or field as scope contains no
 * type and yields an empty result. Endpoint ids line up with `find_node` / `list_children`: members
 * are hierarchy nodes, so a member endpoint is its (artifact-scoped) member node's handle; only
 * members of unscanned owners fall back to a stable but non-navigable `NodeHandle.of(memberKey)`
 * (see [HierarchicalGraphService.memberHandle]).
 */
@Component
class ParquetDetailDependencies(
    graphService: HierarchicalGraphService,
    private val dataHashProvider: DataHashProvider,
) : AbstractDetailTool(graphService), IDetailDependencies {

    private val log = LoggerFactory.getLogger(ParquetDetailDependencies::class.java)

    override fun detailDependencies(
        fromId: Long,
        toId: Long,
        relationship: String?,
        limit: Int?,
        cursor: String?,
        spec: PaginationSpec,
    ): Map<String, Any?> {

        val effectiveRel = relationship?.takeIf { it.isNotBlank() }
        if (effectiveRel != null && effectiveRel !in RELATIONSHIP_KINDS) {
            return errorResponse(
                "INVALID_RELATIONSHIP",
                "Invalid relationship: '$effectiveRel'. Allowed values: ${RELATIONSHIP_KINDS.joinToString()}.",
                "Use one of the allowed relationship values, or omit relationship.",
            )
        }

        val store = graphService.graphStore
            ?: return errorResponse(
                "DETAIL_UNAVAILABLE",
                "No detail-level data is available for the loaded graph.",
                "Use detailLevel='type' for the type-level edges.",
            )
        val parquetDir = readyDetailDir() ?: return detailRebuilding()

        val fromNode = resolveNodeOrRoot(fromId)
            ?: return errorResponse("NODE_NOT_FOUND", "No node with id $fromId exists in the graph.", "Use find_node to look up the correct node ID.")
        val toNode = resolveNodeOrRoot(toId)
            ?: return errorResponse("NODE_NOT_FOUND", "No node with id $toId exists in the graph.", "Use find_node to look up the correct node ID.")

        val queryHash = QueryHash.of(mapOf("fromId" to fromId, "toId" to toId, "relationship" to effectiveRel, "detailLevel" to "detail"))

        // A member scope (method or field) is its declaring type's scope, narrowed below to the rows whose
        // endpoint on that side is the member itself.
        val fromMember = memberKeyOf(fromNode)
        val toMember = memberKeyOf(toNode)
        val fromTypes = expandScope(scopeOf(fromNode))
        val toTypes = expandScope(scopeOf(toNode))
        // The scopes' own types, without the supertypes the closure adds: a row outside them is inherited.
        val ownFromTypes = typesIn(scopeOf(fromNode))
        val ownToTypes = typesIn(scopeOf(toNode))
        if (fromTypes.isEmpty() || toTypes.isEmpty()) return emptyResult(fromNode, toNode, effectiveRel)

        // Pull the whole scoped edge set (own pagination below), then keep only edges whose endpoint
        // types are known to the GraphStore, so the detail layer never surfaces a type without a handle.
        val typeHandle: (String) -> Long? = { fqn -> store.typeByFqn(fqn)?.key?.let { NodeHandle.of(it) } }
        // Parquet direct edges + the load-derived hierarchy edges (overrides / virtual + resolved calls),
        // both scoped the same way; derived edges are the cross-artifact ones no single scan could see.
        val raw = DetailQueryEngine(parquetDir)
            .query(DetailQuery(fromTypes = fromTypes, toTypes = toTypes, relationship = effectiveRel, limit = Int.MAX_VALUE))
            .edges + graphService.derivedDetailRefs(fromTypes, toTypes, effectiveRel)
        // Sorted as a whole, in the engine's order, so the derived rows take their place among the stored
        // ones rather than trailing them.
        val edges = raw.filter {
            typeHandle(it.fromType) != null && typeHandle(it.toType) != null &&
                (fromMember == null || it.fromKey == fromMember) && (toMember == null || it.toKey == toMember)
        }.sortedWith(ROW_ORDER)

        // Endpoint id = content-addressed handle: a type by its model node key, a member by its member node.
        fun endpointHandle(key: String, type: String): Long =
            if ('#' in key) graphService.memberHandle(key) else typeHandle(type)!!

        // --- summaries over the full (filtered) set ---------------------------------------
        val byRelationship = sortedMapOf<String, Int>()
        val sourceTypeCounts = LinkedHashMap<Long, Int>()
        fun inherited(e: DetailRef) = e.fromType !in ownFromTypes || e.toType !in ownToTypes
        val inheritedCount = edges.count(::inherited)
        for (e in edges) {
            byRelationship.merge(e.relationship, 1, Int::plus)
            sourceTypeCounts.merge(typeHandle(e.fromType)!!, 1, Int::plus)
        }
        if (effectiveRel != null) byRelationship.putIfAbsent(effectiveRel, 0)
        val bySourceType = sourceTypeCounts.entries.sortedByDescending { it.value }.take(10)
            .map { (handle, count) -> linkedMapOf<String, Any>("type" to handle, "edge_count" to count) }

        // --- map to edge rows (engine order is already stable), keeping each ref with its row ----
        val mapped = edges.map { ref ->
            val row = linkedMapOf<String, Any?>(
                "from" to endpointHandle(ref.fromKey, ref.fromType),
                "from_parent" to typeHandle(ref.fromType)!!,
                "to" to endpointHandle(ref.toKey, ref.toType),
            )
            if ('#' in ref.toKey) row["to_parent"] = typeHandle(ref.toType)!!
            row["relationship"] = ref.relationship
            row["location"] = ref.line?.let { mapOf("line_number" to it.toLong()) }
            if (inherited(ref)) row["inherited"] = true
            Row(row, ref)
        }

        // --- paginate ---------------------------------------------------------------------
        val pageResult = Paginator.paginate(mapped, spec, queryHash, dataHashProvider.dataHash, cursor, limit)
        val page = when (pageResult) {
            is PageResult.Failed -> return pageResult.error.toResponse()
            is PageResult.Page -> pageResult
        }
        val returnedEdges = page.items.map { it.row }

        // --- summaries over the hierarchy (model-only) + node display --------------------
        val enricher = DetailEnricher(store, parquetDir)
        val display = enricher.nodesFor(page.items.map { it.ref })
        val hierarchy = graphService.model.hierarchy
        val bySourceNodes = drillDown(hierarchy, fromNode) { child -> hierarchy.getAggregatedDependency(child, toNode)?.aggregatedWeight ?: 0 }
        val byTargetNodes = drillDown(hierarchy, toNode) { child -> hierarchy.getAggregatedDependency(fromNode, child)?.aggregatedWeight ?: 0 }

        val nodes = linkedMapOf<String, Any>()
        putSlimNode(nodes, fromNode)
        putSlimNode(nodes, toNode)
        for (entry in bySourceNodes + byTargetNodes) {
            graphService.model.lookupNode(entry["node"] as Long)?.let { putSlimNode(nodes, it) }
        }
        for (entry in bySourceType) {
            graphService.model.lookupNode(entry["type"] as Long)?.let { putSlimNode(nodes, it) }
        }
        for (item in page.items) {
            val ref = item.ref
            registerEndpoint(nodes, item.row["from"] as Long, ref.fromKey, display)
            registerEndpoint(nodes, item.row["from_parent"] as Long, ref.fromType, display)
            registerEndpoint(nodes, item.row["to"] as Long, ref.toKey, display)
            (item.row["to_parent"] as? Long)?.let { registerEndpoint(nodes, it, ref.toType, display) }
        }

        val summary = linkedMapOf<String, Any?>(
            "total_edges" to page.total,
            "returned" to returnedEdges.size,
            "truncated" to page.truncated,
            "inherited" to inheritedCount,
            "by_relationship" to byRelationship,
            "by_source_type" to bySourceType,
        )
        if (sourceTypeCounts.size > bySourceType.size) summary["others_count"] = sourceTypeCounts.size - bySourceType.size
        summary["by_source_nodes"] = bySourceNodes
        summary["by_target_nodes"] = byTargetNodes

        val result = linkedMapOf<String, Any?>(
            "nodes" to nodes,
            "from_scope" to fromNode.identifier,
            "to_scope" to toNode.identifier,
            "edges" to returnedEdges,
            "summary" to summary,
        )
        page.nextCursor?.let { result["next_cursor"] = it }
        return result
    }

    /** Pairs an edge row with the [DetailRef] it came from, so pagination keeps them aligned. */
    private class Row(val row: LinkedHashMap<String, Any?>, val ref: DetailRef)

    /** Puts a slim node for [handle]: a model node when it is a type, else the member display for [key]. */
    private fun registerEndpoint(nodes: MutableMap<String, Any>, handle: Long, key: String, display: Map<String, NodeDisplay>) {
        if (nodes.containsKey(handle.toString())) return
        val modelNode = graphService.model.lookupNode(handle)
        if (modelNode != null) {
            putSlimNode(nodes, modelNode)
            return
        }
        val d = display[key]
        putSlimNode(nodes, handle, d?.name ?: key.substringAfterLast('#'), d?.qualifiedName ?: key, d?.kind ?: "unknown")
    }

    /** The member key (`<owner fqn>#<signature>`) of a method or field node; `null` for any other node. */
    private fun memberKeyOf(node: HGNode): String? =
        if (node.kind in JavaKinds.MEMBER_KINDS) NodeMetadata.getQualifiedName(node) else null

    /** The node whose subtree defines the scope: a member's declaring type, otherwise the node itself. */
    private fun scopeOf(node: HGNode): HGNode =
        if (node.kind in JavaKinds.MEMBER_KINDS) graphService.model.hierarchy.parentOf(node) ?: node else node

    /** The FQNs of the types in [node]'s subtree, [node] included when it is a type. */
    private fun typesIn(node: HGNode): Set<String> {
        val fqns = HashSet<String>()
        if (JavaKinds.isTypeKind(node.kind)) fqns += NodeMetadata.getQualifiedName(node)
        graphService.model.hierarchy.traverse(node) { if (JavaKinds.isTypeKind(it.kind)) fqns += NodeMetadata.getQualifiedName(it) }
        return fqns
    }

    /**
     * Collects type FQNs in [node]'s subtree plus their transitive supertypes (the detail scope). The
     * supertypes are included because a type's behaviour includes what it inherits: a call made by an
     * inherited method, or a call to a method a target type inherits, belongs to the dependency. Rows that
     * only the supertypes contribute are marked `inherited`.
     */
    private fun expandScope(node: HGNode): Set<String> {
        val hierarchy = graphService.model.hierarchy
        val seed = ArrayList<HGNode>()
        // Include the scope node itself when it is a type: `traverse` visits descendants only, so a scope
        // that *is* a single type (a class whose children are members, or a childless annotation/interface)
        // would otherwise collect nothing. Container scopes (module/package) still gather their descendant
        // types via the traversal below.
        if (JavaKinds.isTypeKind(node.kind)) seed.add(node)
        hierarchy.traverse(node) { if (JavaKinds.isTypeKind(it.kind)) seed.add(it) }
        val fqns = linkedSetOf<String>()
        val visited = HashSet<Any?>()
        val queue = ArrayDeque(seed)
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            if (!visited.add(t.identifier)) continue
            fqns += NodeMetadata.getQualifiedName(t)
            for (dep in t.outgoingCoreDependencies) {
                val bm = dep.attributesBitmap
                if (JavaEdgeAttributes.isSet(bm, JavaEdgeAttributes.IS_EXTENDS) || JavaEdgeAttributes.isSet(bm, JavaEdgeAttributes.IS_IMPLEMENTS)) {
                    queue.add(dep.to)
                }
            }
        }
        return fqns
    }

    /** Drill down from [node] to the first multi-child level and weight each child by [dep] to the other scope. */

    private fun resolveNodeOrRoot(nodeId: Long): HGNode? {
        graphService.model.lookupNode(nodeId)?.let { return it }
        val root = graphService.model.hierarchy.rootNode
        return if (root.identifier.let { it is Long && it == nodeId }) root else null
    }

    private fun emptyResult(fromNode: HGNode, toNode: HGNode, effectiveRel: String?): Map<String, Any?> {
        val nodes = linkedMapOf<String, Any>()
        putSlimNode(nodes, fromNode)
        putSlimNode(nodes, toNode)
        return linkedMapOf(
            "nodes" to nodes,
            "from_scope" to fromNode.identifier,
            "to_scope" to toNode.identifier,
            "edges" to emptyList<Any>(),
            "summary" to linkedMapOf<String, Any?>(
                "total_edges" to 0, "returned" to 0, "truncated" to false, "inherited" to 0,
                "by_relationship" to if (effectiveRel != null) mapOf(effectiveRel to 0) else emptyMap<String, Int>(),
                "by_source_type" to emptyList<Any>(),
                "by_source_nodes" to emptyList<Any>(),
                "by_target_nodes" to emptyList<Any>(),
            ),
        )
    }


    internal companion object {

        /**
         * The drill-down of a scope ([node]) behind `by_source_nodes` / `by_target_nodes`: the next,
         * narrower scopes a caller can query at type level. From [node] it descends while a container
         * (root, module, package) has exactly one child that is itself a container, then weighs each
         * package or type below that point with [weight] (its type-level aggregated weight to the other
         * scope), drops zero weights and sorts by weight, highest first.
         *
         * The descent never enters a type: a type's children are members, which carry no type-level
         * weight. A package holding a single type therefore lists that type, and a type scope has no
         * narrower type-level scope, so its drill-down is empty.
         */
        fun drillDown(hierarchy: Hierarchy, node: HGNode, weight: (HGNode) -> Int): List<Map<String, Any?>> {
            fun isType(n: HGNode) = JavaKinds.isTypeKind(n.kind)
            var current = node
            while (!isType(current)) {
                val only = hierarchy.childrenOf(current).singleOrNull() ?: break
                if (isType(only)) break
                current = only
            }
            if (isType(current)) return emptyList()
            return hierarchy.childrenOf(current)
                .filter { it.kind !in JavaKinds.MEMBER_KINDS }
                .map { it to weight(it) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .map { (child, w) -> linkedMapOf<String, Any?>("node" to (child.identifier as Long), "aggregated_weight" to w) }
        }

        /**
         * The order of the edge list: depending type, depending member, target type, target member,
         * relationship, line. The same order [DetailQueryEngine] sorts the stored rows in.
         */
        private val ROW_ORDER = compareBy<DetailRef>({ it.fromType }, { it.fromKey }, { it.toType }, { it.toKey }, { it.relationship }, { it.line ?: -1 })

        /** The relationship values accepted by the `relationship` filter. */
        private val RELATIONSHIP_KINDS = setOf(
            "throws", "raises", "returns", "has_type", "calls", "overrides", "reads_field", "writes_field",
            "read_by", "written_by", "annotated_by", "parameter_type", "parameter_annotated_by",
        )
    }
}
