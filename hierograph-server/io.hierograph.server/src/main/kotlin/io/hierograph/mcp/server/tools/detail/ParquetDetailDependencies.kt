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
import io.hierograph.hierarchicalgraph.model.AggregatedDependency
import io.hierograph.hierarchicalgraph.model.HGNode
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
 * Parquet-backed `detail_dependencies` — the Neo4j-free counterpart of [DetailDependenciesComponent].
 * Served when `hierograph.graph.source=parquet` (see [DetailDependenciesRouter]); it resolves the scope
 * over the in-memory model, reads member-level edges from `detail_refs.parquet` via [DetailQueryEngine],
 * and maps them into the same response shape using content-addressed [NodeHandle]s for every endpoint.
 *
 * Scopes must be containers (module / package / type) — a specific method/field as `from`/`to` is not
 * supported, a deliberate difference from the Cypher path. Endpoint ids line up with `find_node` /
 * `list_children`: members are hierarchy nodes, so a member endpoint is its (artifact-scoped) member
 * node's handle; only members of unscanned owners fall back to a stable but non-navigable
 * `NodeHandle.of(memberKey)` (see [HierarchicalGraphService.memberHandle]).
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
            return error("INVALID_RELATIONSHIP", "Invalid relationship: '$effectiveRel'. Allowed values: $RELATIONSHIP_KINDS")
        }

        val store = graphService.graphStore
            ?: return error("DETAIL_UNAVAILABLE", "Parquet detail engine requires graph.source=parquet.")
        val parquetDir = readyDetailDir() ?: return detailRebuilding()

        val fromNode = resolveNodeOrRoot(fromId)
            ?: return error("NODE_NOT_FOUND", "Source node not found: $fromId. Re-resolve via find_node.")
        val toNode = resolveNodeOrRoot(toId)
            ?: return error("NODE_NOT_FOUND", "Target node not found: $toId. Re-resolve via find_node.")

        val queryHash = QueryHash.of(mapOf("fromId" to fromId, "toId" to toId, "relationship" to effectiveRel, "detailLevel" to "detail"))

        val fromTypes = expandScope(fromNode)
        val toTypes = expandScope(toNode)
        if (fromTypes.isEmpty() || toTypes.isEmpty()) return emptyResult(fromNode, toNode, effectiveRel)

        // Pull the whole scoped edge set (own pagination below), then keep only edges whose endpoint
        // types are materialized in the model — matching the Cypher path, which drops inheritance-reached
        // types that aren't hierarchy nodes so the detail layer never surfaces a non-navigable type.
        val typeHandle: (String) -> Long? = { fqn -> store.typeByFqn(fqn)?.key?.let { NodeHandle.of(it) } }
        // Parquet direct edges + the load-derived hierarchy edges (overrides / virtual + resolved calls),
        // both scoped the same way; derived edges are the cross-artifact ones no single scan could see.
        val raw = DetailQueryEngine(parquetDir)
            .query(DetailQuery(fromTypes = fromTypes, toTypes = toTypes, relationship = effectiveRel, limit = Int.MAX_VALUE))
            .edges + graphService.derivedDetailRefs(fromTypes, toTypes, effectiveRel)
        val edges = raw.filter { typeHandle(it.fromType) != null && typeHandle(it.toType) != null }

        // Endpoint id = content-addressed handle: a type by its model node key, a member by its member node.
        fun endpointHandle(key: String, type: String): Long =
            if ('#' in key) graphService.memberHandle(key) else typeHandle(type)!!

        // --- summaries over the full (filtered) set ---------------------------------------
        val byRelationship = sortedMapOf<String, Int>()
        val sourceTypeCounts = LinkedHashMap<Long, Int>()
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
        val bySourceNodes = computeDrillDown(fromNode) { child -> graphService.model.hierarchy.getAggregatedDependency(child, toNode) }
        val byTargetNodes = computeDrillDown(toNode) { child -> graphService.model.hierarchy.getAggregatedDependency(fromNode, child) }

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

    /** Collects type FQNs in [node]'s subtree plus their transitive supertypes (the detail scope). */
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
    private fun computeDrillDown(node: HGNode, dep: (HGNode) -> AggregatedDependency?): List<Map<String, Any?>> {
        val hierarchy = graphService.model.hierarchy
        var current = node
        while (hierarchy.childrenOf(current).size == 1) current = hierarchy.childrenOf(current)[0]
        return hierarchy.childrenOf(current)
            .map { it to (dep(it)?.aggregatedWeight ?: 0) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { (child, weight) -> linkedMapOf<String, Any?>("node" to (child.identifier as Long), "aggregated_weight" to weight) }
    }

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
                "total_edges" to 0, "returned" to 0, "truncated" to false,
                "by_relationship" to if (effectiveRel != null) mapOf(effectiveRel to 0) else emptyMap<String, Int>(),
                "by_source_type" to emptyList<Any>(),
                "by_source_nodes" to emptyList<Any>(),
                "by_target_nodes" to emptyList<Any>(),
            ),
        )
    }

    private fun error(code: String, message: String): Map<String, Any?> =
        linkedMapOf("error" to code, "message" to message)

    private companion object {
        /** The detail relationship vocabulary (mirrors DetailDependenciesComponent's BRANCHES kinds). */
        private val RELATIONSHIP_KINDS = setOf(
            "throws", "raises", "returns", "has_type", "calls", "overrides", "reads_field", "writes_field",
            "read_by", "written_by", "annotated_by", "parameter_type", "parameter_annotated_by",
        )
    }
}
