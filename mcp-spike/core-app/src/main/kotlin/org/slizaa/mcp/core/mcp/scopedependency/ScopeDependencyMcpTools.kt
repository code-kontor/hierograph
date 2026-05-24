package org.slizaa.mcp.core.mcp.scopedependency

import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency
import org.slizaa.hierarchicalgraph.core.model.HGNode
import org.slizaa.mcp.core.mcp.AbstractGraphMcpTools
import org.slizaa.mcp.core.HierarchicalGraphService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.Comparator
import java.util.TreeSet

@Component
class ScopeDependencyMcpTools(graphService: HierarchicalGraphService) : AbstractGraphMcpTools(graphService) {

    @Tool(
        name = "aggregated_outgoing",
        description = "[Hierarchical scope-based] Get aggregated outgoing dependencies from a source node to targets within a scope. " +
                "Targets default to top-level nodes (children of root), giving a coarse overview. " +
                "Pass a more specific target_scope_id to aggregate at finer granularity within that scope. " +
                "The response is aggregated — for code-level evidence, follow up with outgoing_core_dependencies. " +
                "Each edge includes share_of_total so you can see relative importance at a glance."
    )
    fun aggregatedOutgoing(
        @ToolParam(description = "Source subtree root node ID") sourceId: Long,
        @ToolParam(
            description = "Scope node whose children are the target candidates. Omit for root (top-level overview).",
            required = false
        ) targetScopeId: Long?,
        @ToolParam(description = "Max edges to return (1-100, default 20)", required = false) limit: Int?
    ): Map<String, Any> {

        val sourceNode = graphService.rootNode.lookupNode(sourceId)
            ?: return mapOf("error" to "Source node not found: $sourceId")

        val effectiveLimit = if (limit != null) limit.coerceIn(1, 100) else 20

        // Determine target scope
        val scopeNode: HGNode = if (targetScopeId == null) {
            graphService.rootNode
        } else {
            graphService.rootNode.lookupNode(targetScopeId)
                ?: return mapOf("error" to "Target scope node not found: $targetScopeId")
        }

        // Get aggregated dependencies from source to each child of scope
        val aggDeps: List<HGAggregatedDependency> = sourceNode.getOutgoingDependenciesTo(
            ArrayList(scopeNode.children)
        )

        // Compute totals
        var totalWeight = 0
        val allKinds = TreeSet<String>()
        for (dep in aggDeps) {
            totalWeight += dep.aggregatedWeight
            for (coreDep in dep.coreDependencies) {
                coreDep.type?.let { allKinds.add(it) }
            }
        }

        // Sort by weight descending
        val finalTotalWeight = totalWeight
        val edges = aggDeps.asSequence()
            .sortedByDescending { it.aggregatedWeight }
            .take(effectiveLimit)
            .map { dep ->
                val kinds = TreeSet<String>()
                for (coreDep in dep.coreDependencies) {
                    coreDep.type?.let { kinds.add(it) }
                }
                linkedMapOf<String, Any>(
                    "to" to toNodeRefShort(dep.to),
                    "weight" to dep.aggregatedWeight,
                    "kinds" to kinds,
                    "share_of_total" to if (finalTotalWeight > 0)
                        Math.round(dep.aggregatedWeight * 100.0 / finalTotalWeight) / 100.0
                    else 0.0
                )
            }
            .toList()

        return linkedMapOf(
            "source" to toNodeRefShort(sourceNode),
            "target_scope" to toNodeRefShort(scopeNode),
            "summary" to mapOf(
                "total_outgoing_weight" to totalWeight,
                "total_targets_with_deps" to aggDeps.size,
                "dominant_kinds" to allKinds
            ),
            "edges" to edges
        )
    }

    @Tool(
        name = "aggregated_incoming",
        description = "[Hierarchical scope-based] The primary blast-radius tool. Given a target node, return aggregated incoming " +
                "dependencies from candidate sources within a scope. Use this to answer 'if I change this, " +
                "what's affected?' in a single call with a structural ranking. " +
                "Sources default to top-level nodes (children of root). Pass a more specific source_scope_id " +
                "to see finer-grained dependants within that scope. " +
                "The response is aggregated — for code-level evidence, follow up with incoming_core_dependencies."
    )
    fun aggregatedIncoming(
        @ToolParam(description = "Target subtree root node ID") targetId: Long,
        @ToolParam(
            description = "Scope node whose children are the source candidates. Omit for root (top-level overview).",
            required = false
        ) sourceScopeId: Long?,
        @ToolParam(description = "Max edges to return (1-100, default 20)", required = false) limit: Int?
    ): Map<String, Any> {

        val targetNode = graphService.rootNode.lookupNode(targetId)
            ?: return mapOf("error" to "Target node not found: $targetId")

        val effectiveLimit = if (limit != null) limit.coerceIn(1, 100) else 20

        val scopeNode: HGNode = if (sourceScopeId == null) {
            graphService.rootNode
        } else {
            graphService.rootNode.lookupNode(sourceScopeId)
                ?: return mapOf("error" to "Source scope node not found: $sourceScopeId")
        }

        val aggDeps: List<HGAggregatedDependency> = targetNode.getIncomingDependenciesFrom(
            ArrayList(scopeNode.children)
        )

        var totalWeight = 0
        val allKinds = TreeSet<String>()
        for (dep in aggDeps) {
            totalWeight += dep.aggregatedWeight
            for (coreDep in dep.coreDependencies) {
                coreDep.type?.let { allKinds.add(it) }
            }
        }

        val finalTotalWeight = totalWeight
        val edges = aggDeps.asSequence()
            .sortedByDescending { it.aggregatedWeight }
            .take(effectiveLimit)
            .map { dep ->
                val kinds = TreeSet<String>()
                for (coreDep in dep.coreDependencies) {
                    coreDep.type?.let { kinds.add(it) }
                }
                linkedMapOf<String, Any>(
                    "from" to toNodeRefShort(dep.from),
                    "weight" to dep.aggregatedWeight,
                    "kinds" to kinds,
                    "share_of_total" to if (finalTotalWeight > 0)
                        Math.round(dep.aggregatedWeight * 100.0 / finalTotalWeight) / 100.0
                    else 0.0
                )
            }
            .toList()

        return linkedMapOf(
            "target" to toNodeRefShort(targetNode),
            "source_scope" to toNodeRefShort(scopeNode),
            "summary" to mapOf(
                "total_incoming_weight" to totalWeight,
                "total_sources_with_deps" to aggDeps.size,
                "dominant_kinds" to allKinds
            ),
            "edges" to edges
        )
    }

    @Tool(
        name = "outgoing_core_dependencies",
        description = "[Hierarchical type-level evidence] Return concrete leaf-level dependencies (core edges) from one subtree to another. " +
                "This is the evidence tool — use it after an aggregated query reveals a dependency of interest " +
                "to see specific call sites and relationships. Both from_id and to_id are required because " +
                "unfiltered enumeration is rarely useful — use aggregated_outgoing for an overview first, " +
                "then drill into a specific pair here."
    )
    fun outgoingCoreDependencies(
        @ToolParam(description = "Source subtree root node ID") fromId: Long,
        @ToolParam(description = "Target subtree root node ID") toId: Long,
        @ToolParam(description = "Max edges to return (1-100, default 20)", required = false) limit: Int?
    ): Map<String, Any> {

        val fromNode = graphService.rootNode.lookupNode(fromId)
            ?: return mapOf("error" to "Source node not found: $fromId")

        val toNode = graphService.rootNode.lookupNode(toId)
            ?: return mapOf("error" to "Target node not found: $toId")

        val effectiveLimit = if (limit != null) limit.coerceIn(1, 100) else 20

        val aggDep = fromNode.getOutgoingDependenciesTo(toNode)

        val result = linkedMapOf<String, Any>(
            "from_scope" to toNodeRefShort(fromNode),
            "to_scope" to toNodeRefShort(toNode)
        )

        if (aggDep == null || aggDep.aggregatedWeight == 0) {
            result["total_count"] = 0
            result["edges"] = emptyList<Any>()
            return result
        }

        val coreDeps = aggDep.coreDependencies
        result["total_count"] = coreDeps.size

        val edges = coreDeps.asSequence()
            .take(effectiveLimit)
            .map { dep ->
                linkedMapOf<String, Any?>(
                    "from" to toNodeRefShort(dep.from),
                    "to" to toNodeRefShort(dep.to),
                    "kind" to dep.type
                )
            }
            .toList()

        result["edges"] = edges
        return result
    }

    @Tool(
        name = "incoming_core_dependencies",
        description = "[Hierarchical type-level evidence] Return concrete leaf-level dependencies (core edges) from one subtree into another. " +
                "Mirror of outgoing_core_dependencies — use after aggregated_incoming reveals a dependency " +
                "of interest, to see the specific relationships that constitute it."
    )
    fun incomingCoreDependencies(
        @ToolParam(description = "Target subtree root node ID") toId: Long,
        @ToolParam(description = "Source subtree root node ID") fromId: Long,
        @ToolParam(description = "Max edges to return (1-100, default 20)", required = false) limit: Int?
    ): Map<String, Any> = outgoingCoreDependencies(fromId, toId, limit)
}
