package org.slizaa.mcp.core.mcp.pairwisedependency;

import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency;
import org.slizaa.hierarchicalgraph.core.model.HGCoreDependency;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.mcp.core.mcp.AbstractGraphMcpTools;
import org.slizaa.mcp.core.HierarchicalGraphService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PairwiseDependencyMcpTools extends AbstractGraphMcpTools {

    public PairwiseDependencyMcpTools(HierarchicalGraphService graphService) {
        super(graphService);
    }

    @Tool(name = "dependency_between",
            description = "[Hierarchical pairwise] Check whether a dependency exists from one subtree to another and how strong it is. " +
                    "This is the right tool when you have two specific nodes and want to know 'does A depend on B?' " +
                    "An 'exists: false' result is definitive — you can confidently say there is no dependency. " +
                    "For detailed evidence of an existing dependency, follow up with outgoing_core_dependencies.")
    public Map<String, Object> dependencyBetween(
            @ToolParam(description = "Source subtree root node ID") long fromId,
            @ToolParam(description = "Target subtree root node ID") long toId) {

        HGNode fromNode = graphService.getRootNode().lookupNode(fromId);
        if (fromNode == null) {
            return Map.of("error", "Source node not found: " + fromId);
        }

        HGNode toNode = graphService.getRootNode().lookupNode(toId);
        if (toNode == null) {
            return Map.of("error", "Target node not found: " + toId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", toNodeRefShort(fromNode));
        result.put("to", toNodeRefShort(toNode));

        HGAggregatedDependency aggDep = fromNode.getOutgoingDependenciesTo(toNode);
        if (aggDep != null && aggDep.getAggregatedWeight() > 0) {
            result.put("exists", true);
            result.put("weight", aggDep.getAggregatedWeight());
            Set<String> kinds = new TreeSet<>();
            for (HGCoreDependency coreDep : aggDep.getCoreDependencies()) {
                if (coreDep.getType() != null) {
                    kinds.add(coreDep.getType());
                }
            }
            result.put("kinds", kinds);
        } else {
            result.put("exists", false);
        }

        return result;
    }

    @Tool(name = "outgoing_to",
            description = "[Hierarchical pairwise] Check whether a specific source node has aggregated dependencies to each of a " +
                    "specified list of target nodes. Returns directional yes/no/how-much answers for each target. " +
                    "This is the right tool when you have both a source and specific target candidates in mind " +
                    "and want to know which targets the source actually depends on. " +
                    "An 'exists: false' entry is definitive — you can confidently say there is no dependency. " +
                    "Results appear in input order for clean correspondence with the question. " +
                    "For a single pair, use dependency_between instead. " +
                    "For discovering heaviest dependencies without specific candidates, use aggregated_outgoing. " +
                    "For all-pairs coupling within a node set, use pairwise_dependencies.")
    public Map<String, Object> outgoingTo(
            @ToolParam(description = "The focal source node ID") long sourceId,
            @ToolParam(description = "List of candidate target node IDs to check against (max 50)") List<Long> targetIds,
            @ToolParam(description = "Whether to include explicit exists:false entries for targets with no dependency. " +
                    "Default true.", required = false) Boolean includeMissing) {

        boolean showMissing = includeMissing == null || includeMissing;

        HGNode sourceNode = graphService.getRootNode().lookupNode(sourceId);
        if (sourceNode == null) {
            return Map.of("error", "Source node not found: " + sourceId);
        }

        if (targetIds.size() > 50) {
            return Map.of("error", "Too many targets (" + targetIds.size() + "). Maximum is 50. " +
                    "Use aggregated_outgoing with a scope filter for larger sets.");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int existingCount = 0;
        int missingCount = 0;
        int totalWeight = 0;

        for (Long targetId : targetIds) {
            HGNode targetNode = graphService.getRootNode().lookupNode(targetId);
            if (targetNode == null) {
                if (showMissing) {
                    results.add(Map.of("target", Map.of("id", targetId, "error", "Node not found"), "exists", false));
                    missingCount++;
                }
                continue;
            }

            HGAggregatedDependency aggDep = sourceNode.getOutgoingDependenciesTo(targetNode);
            if (aggDep != null && aggDep.getAggregatedWeight() > 0) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("target", toNodeRefShort(targetNode));
                entry.put("exists", true);
                entry.put("weight", aggDep.getAggregatedWeight());
                Set<String> kinds = new TreeSet<>();
                for (HGCoreDependency coreDep : aggDep.getCoreDependencies()) {
                    if (coreDep.getType() != null) {
                        kinds.add(coreDep.getType());
                    }
                }
                entry.put("kinds", kinds);
                results.add(entry);
                existingCount++;
                totalWeight += aggDep.getAggregatedWeight();
            } else {
                if (showMissing) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("target", toNodeRefShort(targetNode));
                    entry.put("exists", false);
                    results.add(entry);
                }
                missingCount++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("queried_count", targetIds.size());
        summary.put("existing_count", existingCount);
        summary.put("missing_count", missingCount);
        summary.put("total_weight", totalWeight);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", toNodeRefShort(sourceNode));
        result.put("results", results);
        result.put("summary", summary);

        return result;
    }

    @Tool(name = "incoming_from",
            description = "[Hierarchical pairwise] Check whether each of a specified list of source nodes has aggregated dependencies " +
                    "to a specific target node. The target is the focal point; the source list is the set of " +
                    "candidates being checked. Returns directional yes/no/how-much answers for each source. " +
                    "An 'exists: false' entry is definitive. Results appear in input order. " +
                    "This is the right tool for directional usage audits: 'of these candidate modules, which " +
                    "actually depend on the target?' " +
                    "For a single pair, use dependency_between instead. " +
                    "For discovering heaviest dependents without specific candidates, use aggregated_incoming. " +
                    "For all-pairs coupling within a node set, use pairwise_dependencies.")
    public Map<String, Object> incomingFrom(
            @ToolParam(description = "The focal target node ID") long targetId,
            @ToolParam(description = "List of candidate source node IDs to check (max 50)") List<Long> sourceIds,
            @ToolParam(description = "Whether to include explicit exists:false entries for sources with no dependency. " +
                    "Default true.", required = false) Boolean includeMissing) {

        boolean showMissing = includeMissing == null || includeMissing;

        HGNode targetNode = graphService.getRootNode().lookupNode(targetId);
        if (targetNode == null) {
            return Map.of("error", "Target node not found: " + targetId);
        }

        if (sourceIds.size() > 50) {
            return Map.of("error", "Too many sources (" + sourceIds.size() + "). Maximum is 50. " +
                    "Use aggregated_incoming with a scope filter for larger sets.");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int existingCount = 0;
        int missingCount = 0;
        int totalWeight = 0;

        for (Long sourceId : sourceIds) {
            HGNode sourceNode = graphService.getRootNode().lookupNode(sourceId);
            if (sourceNode == null) {
                if (showMissing) {
                    results.add(Map.of("source", Map.of("id", sourceId, "error", "Node not found"), "exists", false));
                    missingCount++;
                }
                continue;
            }

            HGAggregatedDependency aggDep = sourceNode.getOutgoingDependenciesTo(targetNode);
            if (aggDep != null && aggDep.getAggregatedWeight() > 0) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("source", toNodeRefShort(sourceNode));
                entry.put("exists", true);
                entry.put("weight", aggDep.getAggregatedWeight());
                Set<String> kinds = new TreeSet<>();
                for (HGCoreDependency coreDep : aggDep.getCoreDependencies()) {
                    if (coreDep.getType() != null) {
                        kinds.add(coreDep.getType());
                    }
                }
                entry.put("kinds", kinds);
                results.add(entry);
                existingCount++;
                totalWeight += aggDep.getAggregatedWeight();
            } else {
                if (showMissing) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("source", toNodeRefShort(sourceNode));
                    entry.put("exists", false);
                    results.add(entry);
                }
                missingCount++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("queried_count", sourceIds.size());
        summary.put("existing_count", existingCount);
        summary.put("missing_count", missingCount);
        summary.put("total_weight", totalWeight);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", toNodeRefShort(targetNode));
        result.put("results", results);
        result.put("summary", summary);

        return result;
    }
}
