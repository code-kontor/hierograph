package org.slizaa.mcp.core.mcp.discovery

import org.slizaa.hierarchicalgraph.core.model.HGNode
import org.slizaa.mcp.core.mcp.AbstractGraphMcpTools
import org.slizaa.mcp.core.HierarchicalGraphService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.ArrayDeque
import java.util.TreeMap

@Component
class DiscoveryMcpTools(graphService: HierarchicalGraphService) : AbstractGraphMcpTools(graphService) {

    @Tool(
        name = "list_children",
        description = "[Discovery and orientation] Returns the immediate direct children of a node — only one level. " +
                "Use this for shallow exploration when you want to see what's directly contained " +
                "in a specific node. " +
                "Returns ONLY the immediate direct children (depth = 1). For anything beyond one level, use list_descendants." +
                "Do NOT use this tool recursively to enumerate descendants across multiple levels. " +
                "If you find yourself wanting to call list_children more than once or twice to walk " +
                "down a tree, you're using the wrong tool — use list_descendants instead, which returns " +
                "matching descendants from across the entire subtree in a single call. " +
                "Specifically, do not use list_children to: " +
                "build a list of types/classes in a package or module (use list_descendants with a kind filter), " +
                "survey all nodes of a kind under some root (use list_descendants), " +
                "walk a tree to find specific items (use list_descendants or find_node)."
    )
    fun listChildren(
        @ToolParam(
            description = "The node ID to list children for. Omit or pass null for root-level nodes.",
            required = false
        ) nodeId: Long?,
        @ToolParam(description = "Max results to return (1-200, default 50)", required = false) limit: Int?
    ): List<Map<String, Any?>> {

        val effectiveLimit = if (limit != null) limit.coerceIn(1, 200) else 50

        val children: List<out HGNode> = if (nodeId == null) {
            graphService.rootNode.children
        } else {
            val node = graphService.rootNode.lookupNode(nodeId)
                ?: return listOf(mapOf("error" to "Node not found: $nodeId"))
            node.children
        }

        return children.asSequence()
            .take(effectiveLimit)
            .map { toNodeRef(it) }
            .toList()
    }

    @Tool(
        name = "describe_graph",
        description = "[Discovery and orientation] Return a structured overview of the loaded graph or a specified scope. " +
                "This is the right first call when you don't know what kind of graph you're looking at. " +
                "It provides node counts by kind, depth statistics, top-level children with dependency counts, " +
                "and dependency kind distribution. For known graphs, it can be skipped."
    )
    fun describeGraph(
        @ToolParam(
            description = "Scope node ID to describe. Omit for the full graph overview.",
            required = false
        ) scopeId: Long?
    ): Map<String, Any> {

        val boltClient = graphService.boltClient
        val mp = getMetadataProvider()

        // Determine scope
        val scopeNode: HGNode
        val scopeRef: Map<String, Any?>

        if (scopeId == null) {
            scopeNode = graphService.rootNode
            scopeRef = mapOf("id" to "root", "name" to "root", "qualified_name" to "", "kind" to "root")
        } else {
            scopeNode = graphService.rootNode.lookupNode(scopeId)
                ?: return mapOf("error" to "Scope node not found: $scopeId")
            scopeRef = toNodeRefShort(scopeNode)
        }

        val params: Map<String, Any> = if (scopeId != null) mapOf("scopeId" to scopeId) else emptyMap()

        // Node count by kind
        val nodeCountResult = boltClient.syncExecCypherQuery(mp.getNodeCountCypherQuery(scopeId), params)
        val nodeCountByKind = linkedMapOf<String, Any>()
        var totalNodeCount = 0L
        for (record in nodeCountResult.records()) {
            val cnt = record.get("cnt").asLong()
            nodeCountByKind[record.get("label").asString()] = cnt
            totalNodeCount += cnt
        }

        // Depth statistics
        val depthResult = boltClient.syncExecCypherQuery(mp.getDepthStatsCypherQuery(scopeId), params)
        val depthStats = linkedMapOf<String, Any>()
        if (depthResult.records().isNotEmpty()) {
            val rec = depthResult.records()[0]
            depthStats["max_depth"] = if (rec.get("maxDepth").isNull) 0 else rec.get("maxDepth").asLong()
            depthStats["average_depth"] = if (rec.get("avgDepth").isNull) 0
            else Math.round(rec.get("avgDepth").asDouble() * 10.0) / 10.0
        }

        // Dependency kind distribution
        val depKindResult = boltClient.syncExecCypherQuery(
            mp.getDependencyKindDistributionCypherQuery(scopeId), params
        )
        val dependencyKinds = linkedMapOf<String, Any>()
        for (record in depKindResult.records()) {
            dependencyKinds[record.get("kind").asString()] = record.get("cnt").asLong()
        }

        // Top-level children from HG model
        val topChildren = scopeNode.children.map { child ->
            linkedMapOf<String, Any>(
                "node" to toNodeRefShort(child),
                "descendant_count" to countDescendants(child),
                "outgoing_dep_count" to child.accumulatedOutgoingCoreDependencies.size,
                "incoming_dep_count" to child.accumulatedIncomingCoreDependencies.size
            )
        }

        // Scan metadata
        val metadataResult = boltClient.syncExecCypherQuery(mp.scanMetadataCypherQuery)
        val scanMetadata = linkedMapOf<String, Any>("scanner" to mp.scannerName)
        if (metadataResult.records().isNotEmpty()) {
            val v = metadataResult.records()[0].get("scannedAt")
            if (!v.isNull) {
                scanMetadata["scanned_at"] = v.asString()
            }
        }

        // Build result
        return linkedMapOf(
            "scope" to scopeRef,
            "node_count_total" to totalNodeCount,
            "node_count_by_kind" to nodeCountByKind,
            "depth_statistics" to depthStats,
            "top_level_children" to topChildren,
            "dependency_kinds" to dependencyKinds,
            "scan_metadata" to scanMetadata
        )
    }

    @Tool(
        name = "list_descendants",
        description = "[Discovery and orientation] Return descendants of a node matching specified filters, in a single call. " +
                "This is the right tool for any 'show me all X in subtree Y' question. " +
                "Common use cases — use this tool when you want to: " +
                "list all types (classes, interfaces, enums) in a package or module, " +
                "find every node of a particular kind under some root, " +
                "search a subtree by name pattern, " +
                "build a tree view of any subtree (the response includes parent_id for tree reconstruction). " +
                "This tool replaces what would otherwise be repeated list_children calls. " +
                "If your question can be expressed as 'all the X under Y,' this is the tool — " +
                "do not walk the tree node-by-node. " +
                "Returns up to limit matching descendants with their parent IDs and a summary including " +
                "total_matching, by_kind distribution, and by_parent grouping. The summary fields are often " +
                "more useful than the raw list — they answer 'what's there?' without needing to enumerate."
    )
    fun listDescendants(
        @ToolParam(description = "Root node ID — descendants of this node will be searched") rootId: Long,
        @ToolParam(
            description = "Optional list of kinds to include (OR logic). " +
                    "E.g., ['Class', 'Interface'] returns classes and interfaces. " +
                    "Omit to include all kinds.",
            required = false
        ) kindFilter: List<String>?,
        @ToolParam(
            description = "Optional list of kinds to exclude. Exclusion wins over inclusion if both match. " +
                    "E.g., ['Package'] to get everything except packages.",
            required = false
        ) excludeKindFilter: List<String>?,
        @ToolParam(
            description = "Maximum results to return (1-5000, default 500).",
            required = false
        ) limit: Int?
    ): Map<String, Any> {

        val rootNode = graphService.rootNode.lookupNode(rootId)
            ?: return mapOf("error" to "Root node not found: $rootId")

        val mp = getMetadataProvider()
        val effectiveLimit = if (limit != null) limit.coerceIn(1, 5000) else 500
        val effectiveMaxDepth = 20
        val includeKinds = kindFilter?.toHashSet()
        val excludeKinds = excludeKindFilter?.toHashSet()

        // Depth-first preorder traversal collecting all matching descendants
        val allMatches = mutableListOf<DescendantEntry>()
        val kindCounts = linkedMapOf<String, Int>()
        val parentCounts = linkedMapOf<Any, Int>()
        val depthDistribution = TreeMap<Int, Int>()

        val stack = ArrayDeque<DescendantTraversalState>()
        for (child in rootNode.children) {
            stack.addLast(DescendantTraversalState(child, 1))
        }

        while (stack.isNotEmpty()) {
            val state = stack.pollFirst() // preorder: poll from front
            val node = state.node
            val depth = state.depth

            val nodeKind = mp.getKind(node)
            var matches = true

            // Kind filter
            if (includeKinds != null && nodeKind !in includeKinds) {
                matches = false
            }
            if (excludeKinds != null && nodeKind in excludeKinds) {
                matches = false
            }

            // Count for summary (all matches, not just returned)
            if (matches) {
                kindCounts.merge(nodeKind, 1, Integer::sum)
                depthDistribution.merge(depth, 1, Integer::sum)
                val parentId = node.parent?.identifier
                if (parentId != null) {
                    parentCounts.merge(parentId, 1, Integer::sum)
                }
                allMatches.add(DescendantEntry(node, depth))
            }

            // Continue traversal regardless of match (filter is on results, not traversal)
            if (depth < effectiveMaxDepth) {
                val children = node.children
                for (i in children.indices.reversed()) {
                    stack.addFirst(DescendantTraversalState(children[i], depth + 1))
                }
            }
        }

        // Build result list (truncated to limit)
        val truncated = allMatches.size > effectiveLimit
        val returnedMatches = allMatches.take(effectiveLimit)

        val descendants = returnedMatches.map { entry ->
            linkedMapOf<String, Any?>(
                "node" to toNodeRefShort(entry.node),
                "parent_id" to entry.node.parent?.identifier,
                "depth" to entry.depth,
                "outgoing_dep_count" to entry.node.accumulatedOutgoingCoreDependencies.size,
                "incoming_dep_count" to entry.node.accumulatedIncomingCoreDependencies.size
            )
        }

        // Collect all ancestors between root and each returned match (for tree reconstruction)
        val matchIds = returnedMatches.map { it.node.identifier }.toHashSet()
        val ancestorMap = linkedMapOf<Any, Map<String, Any?>>()
        for (entry in returnedMatches) {
            var walk = entry.node.parent
            while (walk != null && walk.identifier != rootNode.identifier) {
                val walkId = walk.identifier
                if (walkId in ancestorMap || walkId in matchIds) break
                ancestorMap[walkId] = linkedMapOf(
                    "node" to toNodeRefShort(walk),
                    "parent_id" to walk.parent?.identifier
                )
                walk = walk.parent
            }
        }
        val ancestors = ancestorMap.values.toList()

        // Build by_parent summary (top 10 parents by match count)
        val byParent = parentCounts.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (key, value) ->
                val parentNode = graphService.rootNode.lookupNode(key)
                linkedMapOf<String, Any>(
                    "parent" to (if (parentNode != null) toNodeRefShort(parentNode) else mapOf("id" to key)),
                    "match_count" to value
                )
            }

        // Build summary
        val summary = linkedMapOf<String, Any>(
            "total_matching" to allMatches.size,
            "returned" to minOf(allMatches.size, effectiveLimit),
            "truncated" to truncated,
            "by_kind" to kindCounts,
            "by_parent" to byParent,
            "depth_distribution" to depthDistribution
        )

        // Build filters applied
        val filtersApplied = linkedMapOf<String, Any>()
        if (kindFilter != null) filtersApplied["kind_filter"] = kindFilter
        if (excludeKindFilter != null) filtersApplied["exclude_kind_filter"] = excludeKindFilter
        filtersApplied["limit"] = effectiveLimit

        // Build result
        return linkedMapOf(
            "root" to toNodeRefShort(rootNode),
            "filters_applied" to filtersApplied,
            "ancestors" to ancestors,
            "descendants" to descendants,
            "summary" to summary
        )
    }

    private data class DescendantEntry(val node: HGNode, val depth: Int)
    private data class DescendantTraversalState(val node: HGNode, val depth: Int)
}
