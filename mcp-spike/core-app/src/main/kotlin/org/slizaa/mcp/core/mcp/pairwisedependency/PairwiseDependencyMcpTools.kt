package org.slizaa.mcp.core.mcp.pairwisedependency

import org.slizaa.hierarchicalgraph.core.model.HGNode
import org.slizaa.mcp.core.mcp.AbstractGraphMcpTools
import org.slizaa.mcp.core.HierarchicalGraphService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.TreeSet

@Component
class PairwiseDependencyMcpTools(graphService: HierarchicalGraphService) : AbstractGraphMcpTools(graphService) {

    @Tool(
        name = "dependency_between",
        description = "[Hierarchical pairwise] Check whether a dependency exists from one subtree to another and how strong it is. " +
                "This is the right tool when you have two specific nodes and want to know 'does A depend on B?' " +
                "An 'exists: false' result is definitive — you can confidently say there is no dependency. " +
                "For detailed evidence of an existing dependency, follow up with outgoing_core_dependencies."
    )
    fun dependencyBetween(
        @ToolParam(description = "Source subtree root node ID") fromId: Long,
        @ToolParam(description = "Target subtree root node ID") toId: Long
    ): Map<String, Any> {

        val fromNode = graphService.rootNode.lookupNode(fromId)
            ?: return mapOf("error" to "Source node not found: $fromId")

        val toNode = graphService.rootNode.lookupNode(toId)
            ?: return mapOf("error" to "Target node not found: $toId")

        val result = linkedMapOf<String, Any>(
            "from" to toNodeRefShort(fromNode),
            "to" to toNodeRefShort(toNode)
        )

        val aggDep = fromNode.getOutgoingDependenciesTo(toNode)
        if (aggDep != null && aggDep.aggregatedWeight > 0) {
            result["exists"] = true
            result["weight"] = aggDep.aggregatedWeight
            val kinds = TreeSet<String>()
            for (coreDep in aggDep.coreDependencies) {
                coreDep.type?.let { kinds.add(it) }
            }
            result["kinds"] = kinds
        } else {
            result["exists"] = false
        }

        return result
    }

    @Tool(
        name = "outgoing_to",
        description = "[Hierarchical pairwise] Check whether a specific source node has aggregated dependencies to each of a " +
                "specified list of target nodes. Returns directional yes/no/how-much answers for each target. " +
                "This is the right tool when you have both a source and specific target candidates in mind " +
                "and want to know which targets the source actually depends on. " +
                "An 'exists: false' entry is definitive — you can confidently say there is no dependency. " +
                "Results appear in input order for clean correspondence with the question. " +
                "For a single pair, use dependency_between instead. " +
                "For discovering heaviest dependencies without specific candidates, use aggregated_outgoing. " +
                "For all-pairs coupling within a node set, use pairwise_dependencies."
    )
    fun outgoingTo(
        @ToolParam(description = "The focal source node ID") sourceId: Long,
        @ToolParam(description = "List of candidate target node IDs to check against (max 50)") targetIds: List<Long>,
        @ToolParam(
            description = "Whether to include explicit exists:false entries for targets with no dependency. " +
                    "Default true.",
            required = false
        ) includeMissing: Boolean?
    ): Map<String, Any> {

        val showMissing = includeMissing == null || includeMissing

        val sourceNode = graphService.rootNode.lookupNode(sourceId)
            ?: return mapOf("error" to "Source node not found: $sourceId")

        if (targetIds.size > 50) {
            return mapOf(
                "error" to "Too many targets (${targetIds.size}). Maximum is 50. " +
                        "Use aggregated_outgoing with a scope filter for larger sets."
            )
        }

        val results = mutableListOf<Map<String, Any>>()
        var existingCount = 0
        var missingCount = 0
        var totalWeight = 0

        for (targetId in targetIds) {
            val targetNode = graphService.rootNode.lookupNode(targetId)
            if (targetNode == null) {
                if (showMissing) {
                    results.add(mapOf("target" to mapOf("id" to targetId, "error" to "Node not found"), "exists" to false))
                    missingCount++
                }
                continue
            }

            val aggDep = sourceNode.getOutgoingDependenciesTo(targetNode)
            if (aggDep != null && aggDep.aggregatedWeight > 0) {
                val entry = linkedMapOf<String, Any>(
                    "target" to toNodeRefShort(targetNode),
                    "exists" to true,
                    "weight" to aggDep.aggregatedWeight
                )
                val kinds = TreeSet<String>()
                for (coreDep in aggDep.coreDependencies) {
                    coreDep.type?.let { kinds.add(it) }
                }
                entry["kinds"] = kinds
                results.add(entry)
                existingCount++
                totalWeight += aggDep.aggregatedWeight
            } else {
                if (showMissing) {
                    results.add(
                        linkedMapOf("target" to toNodeRefShort(targetNode), "exists" to false)
                    )
                }
                missingCount++
            }
        }

        return linkedMapOf(
            "source" to toNodeRefShort(sourceNode),
            "results" to results,
            "summary" to linkedMapOf(
                "queried_count" to targetIds.size,
                "existing_count" to existingCount,
                "missing_count" to missingCount,
                "total_weight" to totalWeight
            )
        )
    }

    @Tool(
        name = "incoming_from",
        description = "[Hierarchical pairwise] Check whether each of a specified list of source nodes has aggregated dependencies " +
                "to a specific target node. The target is the focal point; the source list is the set of " +
                "candidates being checked. Returns directional yes/no/how-much answers for each source. " +
                "An 'exists: false' entry is definitive. Results appear in input order. " +
                "This is the right tool for directional usage audits: 'of these candidate modules, which " +
                "actually depend on the target?' " +
                "For a single pair, use dependency_between instead. " +
                "For discovering heaviest dependents without specific candidates, use aggregated_incoming. " +
                "For all-pairs coupling within a node set, use pairwise_dependencies."
    )
    fun incomingFrom(
        @ToolParam(description = "The focal target node ID") targetId: Long,
        @ToolParam(description = "List of candidate source node IDs to check (max 50)") sourceIds: List<Long>,
        @ToolParam(
            description = "Whether to include explicit exists:false entries for sources with no dependency. " +
                    "Default true.",
            required = false
        ) includeMissing: Boolean?
    ): Map<String, Any> {

        val showMissing = includeMissing == null || includeMissing

        val targetNode = graphService.rootNode.lookupNode(targetId)
            ?: return mapOf("error" to "Target node not found: $targetId")

        if (sourceIds.size > 50) {
            return mapOf(
                "error" to "Too many sources (${sourceIds.size}). Maximum is 50. " +
                        "Use aggregated_incoming with a scope filter for larger sets."
            )
        }

        val results = mutableListOf<Map<String, Any>>()
        var existingCount = 0
        var missingCount = 0
        var totalWeight = 0

        for (sourceId in sourceIds) {
            val sourceNode = graphService.rootNode.lookupNode(sourceId)
            if (sourceNode == null) {
                if (showMissing) {
                    results.add(mapOf("source" to mapOf("id" to sourceId, "error" to "Node not found"), "exists" to false))
                    missingCount++
                }
                continue
            }

            val aggDep = sourceNode.getOutgoingDependenciesTo(targetNode)
            if (aggDep != null && aggDep.aggregatedWeight > 0) {
                val entry = linkedMapOf<String, Any>(
                    "source" to toNodeRefShort(sourceNode),
                    "exists" to true,
                    "weight" to aggDep.aggregatedWeight
                )
                val kinds = TreeSet<String>()
                for (coreDep in aggDep.coreDependencies) {
                    coreDep.type?.let { kinds.add(it) }
                }
                entry["kinds"] = kinds
                results.add(entry)
                existingCount++
                totalWeight += aggDep.aggregatedWeight
            } else {
                if (showMissing) {
                    results.add(
                        linkedMapOf("source" to toNodeRefShort(sourceNode), "exists" to false)
                    )
                }
                missingCount++
            }
        }

        return linkedMapOf(
            "target" to toNodeRefShort(targetNode),
            "results" to results,
            "summary" to linkedMapOf(
                "queried_count" to sourceIds.size,
                "existing_count" to existingCount,
                "missing_count" to missingCount,
                "total_weight" to totalWeight
            )
        )
    }
}
