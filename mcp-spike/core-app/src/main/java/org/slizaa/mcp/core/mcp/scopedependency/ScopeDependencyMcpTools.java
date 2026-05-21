package org.slizaa.mcp.core.mcp.scopedependency;

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
public class ScopeDependencyMcpTools extends AbstractGraphMcpTools {

    public ScopeDependencyMcpTools(HierarchicalGraphService graphService) {
        super(graphService);
    }

    @Tool(name = "aggregated_outgoing",
            description = "[Hierarchical scope-based] Get aggregated outgoing dependencies from a source node to targets within a scope. " +
                    "Targets default to top-level nodes (children of root), giving a coarse overview. " +
                    "Pass a more specific target_scope_id to aggregate at finer granularity within that scope. " +
                    "The response is aggregated — for code-level evidence, follow up with outgoing_core_dependencies. " +
                    "Each edge includes share_of_total so you can see relative importance at a glance.")
    public Map<String, Object> aggregatedOutgoing(
            @ToolParam(description = "Source subtree root node ID") long sourceId,
            @ToolParam(description = "Scope node whose children are the target candidates. Omit for root (top-level overview).",
                    required = false) Long targetScopeId,
            @ToolParam(description = "Max edges to return (1-100, default 20)", required = false) Integer limit) {

        HGNode sourceNode = graphService.getRootNode().lookupNode(sourceId);
        if (sourceNode == null) {
            return Map.of("error", "Source node not found: " + sourceId);
        }

        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 100) : 20;

        // Determine target scope
        HGNode scopeNode;
        if (targetScopeId == null) {
            scopeNode = graphService.getRootNode();
        } else {
            scopeNode = graphService.getRootNode().lookupNode(targetScopeId);
            if (scopeNode == null) {
                return Map.of("error", "Target scope node not found: " + targetScopeId);
            }
        }

        // Get aggregated dependencies from source to each child of scope
        List<HGAggregatedDependency> aggDeps = sourceNode.getOutgoingDependenciesTo(
                new ArrayList<>(scopeNode.getChildren()));

        // Compute totals
        int totalWeight = 0;
        Set<String> allKinds = new TreeSet<>();
        for (HGAggregatedDependency dep : aggDeps) {
            totalWeight += dep.getAggregatedWeight();
            for (HGCoreDependency coreDep : dep.getCoreDependencies()) {
                if (coreDep.getType() != null) {
                    allKinds.add(coreDep.getType());
                }
            }
        }

        // Sort by weight descending
        int finalTotalWeight = totalWeight;
        List<Map<String, Object>> edges = aggDeps.stream()
                .sorted(Comparator.comparingInt(HGAggregatedDependency::getAggregatedWeight).reversed())
                .limit(effectiveLimit)
                .map(dep -> {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("to", toNodeRefShort(dep.getTo()));
                    edge.put("weight", dep.getAggregatedWeight());
                    Set<String> kinds = new TreeSet<>();
                    for (HGCoreDependency coreDep : dep.getCoreDependencies()) {
                        if (coreDep.getType() != null) {
                            kinds.add(coreDep.getType());
                        }
                    }
                    edge.put("kinds", kinds);
                    edge.put("share_of_total", finalTotalWeight > 0
                            ? Math.round(dep.getAggregatedWeight() * 100.0 / finalTotalWeight) / 100.0
                            : 0.0);
                    return edge;
                })
                .toList();

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", toNodeRefShort(sourceNode));
        result.put("target_scope", toNodeRefShort(scopeNode));
        result.put("summary", Map.of(
                "total_outgoing_weight", totalWeight,
                "total_targets_with_deps", aggDeps.size(),
                "dominant_kinds", allKinds
        ));
        result.put("edges", edges);

        return result;
    }

    @Tool(name = "aggregated_incoming",
            description = "[Hierarchical scope-based] The primary blast-radius tool. Given a target node, return aggregated incoming " +
                    "dependencies from candidate sources within a scope. Use this to answer 'if I change this, " +
                    "what's affected?' in a single call with a structural ranking. " +
                    "Sources default to top-level nodes (children of root). Pass a more specific source_scope_id " +
                    "to see finer-grained dependants within that scope. " +
                    "The response is aggregated — for code-level evidence, follow up with incoming_core_dependencies.")
    public Map<String, Object> aggregatedIncoming(
            @ToolParam(description = "Target subtree root node ID") long targetId,
            @ToolParam(description = "Scope node whose children are the source candidates. Omit for root (top-level overview).",
                    required = false) Long sourceScopeId,
            @ToolParam(description = "Max edges to return (1-100, default 20)", required = false) Integer limit) {

        HGNode targetNode = graphService.getRootNode().lookupNode(targetId);
        if (targetNode == null) {
            return Map.of("error", "Target node not found: " + targetId);
        }

        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 100) : 20;

        HGNode scopeNode;
        if (sourceScopeId == null) {
            scopeNode = graphService.getRootNode();
        } else {
            scopeNode = graphService.getRootNode().lookupNode(sourceScopeId);
            if (scopeNode == null) {
                return Map.of("error", "Source scope node not found: " + sourceScopeId);
            }
        }

        List<HGAggregatedDependency> aggDeps = targetNode.getIncomingDependenciesFrom(
                new ArrayList<>(scopeNode.getChildren()));

        int totalWeight = 0;
        Set<String> allKinds = new TreeSet<>();
        for (HGAggregatedDependency dep : aggDeps) {
            totalWeight += dep.getAggregatedWeight();
            for (HGCoreDependency coreDep : dep.getCoreDependencies()) {
                if (coreDep.getType() != null) {
                    allKinds.add(coreDep.getType());
                }
            }
        }

        int finalTotalWeight = totalWeight;
        List<Map<String, Object>> edges = aggDeps.stream()
                .sorted(Comparator.comparingInt(HGAggregatedDependency::getAggregatedWeight).reversed())
                .limit(effectiveLimit)
                .map(dep -> {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("from", toNodeRefShort(dep.getFrom()));
                    edge.put("weight", dep.getAggregatedWeight());
                    Set<String> kinds = new TreeSet<>();
                    for (HGCoreDependency coreDep : dep.getCoreDependencies()) {
                        if (coreDep.getType() != null) {
                            kinds.add(coreDep.getType());
                        }
                    }
                    edge.put("kinds", kinds);
                    edge.put("share_of_total", finalTotalWeight > 0
                            ? Math.round(dep.getAggregatedWeight() * 100.0 / finalTotalWeight) / 100.0
                            : 0.0);
                    return edge;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", toNodeRefShort(targetNode));
        result.put("source_scope", toNodeRefShort(scopeNode));
        result.put("summary", Map.of(
                "total_incoming_weight", totalWeight,
                "total_sources_with_deps", aggDeps.size(),
                "dominant_kinds", allKinds
        ));
        result.put("edges", edges);

        return result;
    }

    @Tool(name = "outgoing_core_dependencies",
            description = "[Hierarchical type-level evidence] Return concrete leaf-level dependencies (core edges) from one subtree to another. " +
                    "This is the evidence tool — use it after an aggregated query reveals a dependency of interest " +
                    "to see specific call sites and relationships. Both from_id and to_id are required because " +
                    "unfiltered enumeration is rarely useful — use aggregated_outgoing for an overview first, " +
                    "then drill into a specific pair here.")
    public Map<String, Object> outgoingCoreDependencies(
            @ToolParam(description = "Source subtree root node ID") long fromId,
            @ToolParam(description = "Target subtree root node ID") long toId,
            @ToolParam(description = "Max edges to return (1-100, default 20)", required = false) Integer limit) {

        HGNode fromNode = graphService.getRootNode().lookupNode(fromId);
        if (fromNode == null) {
            return Map.of("error", "Source node not found: " + fromId);
        }

        HGNode toNode = graphService.getRootNode().lookupNode(toId);
        if (toNode == null) {
            return Map.of("error", "Target node not found: " + toId);
        }

        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 100) : 20;

        HGAggregatedDependency aggDep = fromNode.getOutgoingDependenciesTo(toNode);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from_scope", toNodeRefShort(fromNode));
        result.put("to_scope", toNodeRefShort(toNode));

        if (aggDep == null || aggDep.getAggregatedWeight() == 0) {
            result.put("total_count", 0);
            result.put("edges", List.of());
            return result;
        }

        List<HGCoreDependency> coreDeps = aggDep.getCoreDependencies();
        result.put("total_count", coreDeps.size());

        List<Map<String, Object>> edges = coreDeps.stream()
                .limit(effectiveLimit)
                .map(dep -> {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("from", toNodeRefShort(dep.getFrom()));
                    edge.put("to", toNodeRefShort(dep.getTo()));
                    edge.put("kind", dep.getType());
                    return edge;
                })
                .toList();

        result.put("edges", edges);
        return result;
    }

    @Tool(name = "incoming_core_dependencies",
            description = "[Hierarchical type-level evidence] Return concrete leaf-level dependencies (core edges) from one subtree into another. " +
                    "Mirror of outgoing_core_dependencies — use after aggregated_incoming reveals a dependency " +
                    "of interest, to see the specific relationships that constitute it.")
    public Map<String, Object> incomingCoreDependencies(
            @ToolParam(description = "Target subtree root node ID") long toId,
            @ToolParam(description = "Source subtree root node ID") long fromId,
            @ToolParam(description = "Max edges to return (1-100, default 20)", required = false) Integer limit) {

        // Reuse outgoing — the aggregated dependency is the same object regardless of direction
        return outgoingCoreDependencies(fromId, toId, limit);
    }
}
