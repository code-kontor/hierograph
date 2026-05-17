package org.slizaa.mcp.core;

import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency;
import org.slizaa.hierarchicalgraph.core.model.HGCoreDependency;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.core.algorithms.GraphUtils;
import org.slizaa.hierarchicalgraph.core.algorithms.IDependencyStructureMatrix;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ReachabilityMcpTools extends AbstractGraphMcpTools {

    public ReachabilityMcpTools(HierarchicalGraphService graphService) {
        super(graphService);
    }

    @Tool(name = "find_dependency_path",
            description = "[Hierarchical reachability] Determine whether a transitive dependency path exists from one node to another, " +
                    "and if so, return the shortest path. This is the right tool for transitive reachability " +
                    "questions — it answers 'can A indirectly affect B?' over the in-memory dependency graph. " +
                    "An 'exists: false' result is definitive — there is no dependency chain of any length " +
                    "between the two nodes (up to max_length). Computed via BFS over accumulated core " +
                    "dependencies; cheap given the in-memory graph.")
    public Map<String, Object> findDependencyPath(
            @ToolParam(description = "Source node ID — starting point of the path") long fromId,
            @ToolParam(description = "Target node ID — destination of the path") long toId,
            @ToolParam(description = "Maximum path length to consider (1-20, default 10)",
                    required = false) Integer maxLength) {

        HGNode fromNode = graphService.getRootNode().lookupNode(fromId);
        if (fromNode == null) {
            return Map.of("error", "Source node not found: " + fromId);
        }

        HGNode toNode = graphService.getRootNode().lookupNode(toId);
        if (toNode == null) {
            return Map.of("error", "Target node not found: " + toId);
        }

        int effectiveMaxLength = maxLength != null ? Math.min(Math.max(maxLength, 1), 20) : 10;

        // Determine traversal strategy: if both nodes are non-leaf (have children),
        // use aggregated dependencies at the same hierarchy level.
        // Otherwise, use core (leaf-level) dependencies.
        boolean useAggregatedTraversal = !fromNode.getChildren().isEmpty() && !toNode.getChildren().isEmpty();

        // BFS to find shortest path
        Map<HGNode, HGNode> predecessors = new LinkedHashMap<>();
        Queue<HGNode> queue = new ArrayDeque<>();
        queue.add(fromNode);
        predecessors.put(fromNode, null);

        // For aggregated traversal, collect all sibling nodes at the same level as candidates
        Set<HGNode> candidateNodes = null;
        if (useAggregatedTraversal) {
            candidateNodes = new LinkedHashSet<>();
            // Collect all nodes at the same level (siblings of from and to under root)
            collectAllNodesAtLevel(fromNode, candidateNodes);
        }

        boolean found = false;
        while (!queue.isEmpty() && !found) {
            HGNode current = queue.poll();

            // Check path length: count hops from fromNode to current
            int depth = 0;
            HGNode trace = current;
            while (predecessors.get(trace) != null) {
                depth++;
                trace = predecessors.get(trace);
            }
            if (depth >= effectiveMaxLength) {
                continue;
            }

            // Collect neighbors based on traversal strategy
            Set<HGNode> neighbors = new LinkedHashSet<>();
            if (useAggregatedTraversal) {
                // Use aggregated dependencies: check outgoing to all candidate nodes
                for (HGNode candidate : candidateNodes) {
                    if (candidate.getIdentifier().equals(current.getIdentifier())) continue;
                    HGAggregatedDependency aggDep = current.getOutgoingDependenciesTo(candidate);
                    if (aggDep != null && aggDep.getAggregatedWeight() > 0) {
                        neighbors.add(candidate);
                    }
                }
            } else {
                // Use core (leaf-level) dependencies
                for (HGCoreDependency dep : current.getAccumulatedOutgoingCoreDependencies()) {
                    neighbors.add(dep.getTo());
                }
            }

            for (HGNode neighbor : neighbors) {
                if (predecessors.containsKey(neighbor)) {
                    continue; // already visited
                }
                predecessors.put(neighbor, current);
                if (neighbor.getIdentifier().equals(toNode.getIdentifier())) {
                    found = true;
                    break;
                }
                queue.add(neighbor);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", toNodeRefShort(fromNode));
        result.put("to", toNodeRefShort(toNode));

        if (found) {
            // Reconstruct path
            List<HGNode> pathNodes = new ArrayList<>();
            HGNode step = toNode;
            while (step != null) {
                pathNodes.add(step);
                step = predecessors.get(step);
            }
            Collections.reverse(pathNodes);

            result.put("exists", true);
            result.put("length", pathNodes.size() - 1);
            result.put("path", pathNodes.stream().map(this::toNodeRefShort).toList());
        } else {
            result.put("exists", false);
            result.put("max_length_searched", effectiveMaxLength);
        }

        return result;
    }

    @Tool(name = "pairwise_dependencies",
            description = "[Hierarchical scope-based] Bundled pairwise dependency analysis over a node set. Given a set of nodes " +
                    "(typically siblings, layers, or a user-specified group), return all pairwise aggregated " +
                    "dependencies among them as an edge list, plus server-computed structural insights: " +
                    "density, cycle presence, strongly connected components, and topological order (if acyclic). " +
                    "This is the right tool when you need to understand internal coupling within a group of " +
                    "modules — one call instead of N-squared calls to dependency_between. " +
                    "Use cases: layering violation detection (has_cycles), coupling analysis (density, edge weights), " +
                    "extraction feasibility (internal coupling pattern), and architecture assessment (SCC structure).")
    public Map<String, Object> pairwiseDependencies(
            @ToolParam(description = "List of node IDs to analyze pairwise") List<Long> nodeIds,
            @ToolParam(description = "Whether to include self-loops (internal coupling within each node's subtree). Default false.",
                    required = false) Boolean includeSelfLoops) {

        boolean selfLoops = includeSelfLoops != null && includeSelfLoops;

        // Resolve nodes
        List<HGNode> nodes = new ArrayList<>();
        for (Long id : nodeIds) {
            HGNode node = graphService.getRootNode().lookupNode(id);
            if (node == null) {
                return Map.of("error", "Node not found: " + id);
            }
            nodes.add(node);
        }

        // Build DSM using the existing algorithms library
        IDependencyStructureMatrix dsm = GraphUtils.createDependencyStructureMatrix(nodes);
        List<HGNode> orderedNodes = dsm.getOrderedNodes();
        int size = orderedNodes.size();

        // Node info
        List<Map<String, Object>> nodeInfos = orderedNodes.stream()
                .map(this::toNodeRefShort)
                .toList();

        // Edge list
        List<Map<String, Object>> edges = new ArrayList<>();
        int totalWeight = 0;
        int edgeCount = 0;
        int maxWeight = 0;

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (!selfLoops && i == j) continue;
                int weight = dsm.getWeight(i, j);
                if (weight > 0) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("from", toNodeRefShort(orderedNodes.get(i)));
                    edge.put("to", toNodeRefShort(orderedNodes.get(j)));
                    edge.put("weight", weight);
                    edge.put("in_cycle", dsm.isCellInCycle(i, j));
                    edges.add(edge);
                    totalWeight += weight;
                    edgeCount++;
                    maxWeight = Math.max(maxWeight, weight);
                }
            }
        }

        // Cycles / SCCs
        List<List<HGNode>> cycles = dsm.getCycles();
        List<List<Map<String, Object>>> cycleRefs = cycles.stream()
                .map(cycle -> cycle.stream().map(this::toNodeRefShort).toList())
                .toList();

        // Topological order (the DSM ordered nodes are already topologically sorted if acyclic)
        List<Map<String, Object>> topologicalOrder = null;
        if (cycles.isEmpty()) {
            topologicalOrder = orderedNodes.stream()
                    .map(this::toNodeRefShort)
                    .toList();
        }

        // Density: actual edges / possible edges (excluding self-loops)
        int possibleEdges = size * (size - 1);
        // Count non-self-loop edges for density
        int nonSelfEdges = 0;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (i != j && dsm.getWeight(i, j) > 0) {
                    nonSelfEdges++;
                }
            }
        }
        double density = possibleEdges > 0 ? Math.round(nonSelfEdges * 1000.0 / possibleEdges) / 1000.0 : 0.0;

        // Build summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("node_count", size);
        summary.put("edge_count", edgeCount);
        summary.put("total_weight", totalWeight);
        summary.put("max_edge_weight", maxWeight);
        summary.put("density", density);
        summary.put("has_cycles", !cycles.isEmpty());
        summary.put("strongly_connected_components", cycleRefs);
        if (topologicalOrder != null) {
            summary.put("topological_order", topologicalOrder);
        }

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodeInfos);
        result.put("edges", edges);
        result.put("summary", summary);

        return result;
    }

    @Tool(name = "affected_by",
            description = "[Hierarchical reachability] Return the transitive blast radius of a proposed change to a node — everything that " +
                    "depends on it, directly or indirectly, up to a specified depth — as a structured summary. " +
                    "This is the tool for the full ripple-effect question, distinct from aggregated_incoming " +
                    "(which shows only direct dependents). Traversal walks backwards through core (leaf-level) " +
                    "dependencies from the source's boundary. The source's own subtree is excluded. " +
                    "The response is a structured summary (depth distribution, by-scope breakdown, top affected " +
                    "nodes) rather than an enumeration of every affected node. Depth is measured in core " +
                    "dependency hops. Cycles are handled via visited-set deduplication; each affected node " +
                    "appears once at its shortest reachable depth.")
    public Map<String, Object> affectedBy(
            @ToolParam(description = "The node being changed — starting point for blast radius analysis") long sourceId,
            @ToolParam(description = "Maximum traversal depth in core dependency hops (1-20, default 5)",
                    required = false) Integer maxDepth,
            @ToolParam(description = "Aggregate the by_scope result at the level of children of this scope. " +
                    "Defaults to root (top-level modules).", required = false) Long groupingScopeId,
            @ToolParam(description = "Size of the top_affected_nodes list (1-50, default 10)",
                    required = false) Integer topN) {

        HGNode sourceNode = graphService.getRootNode().lookupNode(sourceId);
        if (sourceNode == null) {
            return Map.of("error", "Source node not found: " + sourceId);
        }

        int effectiveMaxDepth = maxDepth != null ? Math.min(Math.max(maxDepth, 1), 20) : 5;
        int effectiveTopN = topN != null ? Math.min(Math.max(topN, 1), 50) : 10;

        HGNode groupingScope;
        if (groupingScopeId == null) {
            groupingScope = graphService.getRootNode();
        } else {
            groupingScope = graphService.getRootNode().lookupNode(groupingScopeId);
            if (groupingScope == null) {
                return Map.of("error", "Grouping scope node not found: " + groupingScopeId);
            }
        }

        // Collect the source's own leaf descendants (to exclude from results)
        Set<Object> sourceSubtreeIds = new HashSet<>();
        collectDescendantIds(sourceNode, sourceSubtreeIds);

        // BFS backwards through incoming core dependencies
        // Each entry: node -> depth at which it was first reached
        Map<HGNode, Integer> affectedWithDepth = new LinkedHashMap<>();
        // Track weight from predecessor for top_affected_nodes ranking
        Map<HGNode, Integer> affectedWeight = new LinkedHashMap<>();

        // Seed: direct incoming core dependencies crossing the source boundary
        Queue<HGNode> queue = new ArrayDeque<>();

        // Get all leaf nodes in the source subtree and their incoming edges from outside
        List<HGCoreDependency> boundaryIncoming = new ArrayList<>();
        collectBoundaryIncomingDeps(sourceNode, sourceSubtreeIds, boundaryIncoming);

        for (HGCoreDependency dep : boundaryIncoming) {
            HGNode from = dep.getFrom();
            if (!sourceSubtreeIds.contains(from.getIdentifier()) && !affectedWithDepth.containsKey(from)) {
                affectedWithDepth.put(from, 1);
                affectedWeight.put(from, dep.getWeight());
                queue.add(from);
            } else if (affectedWeight.containsKey(from)) {
                affectedWeight.merge(from, dep.getWeight(), Integer::sum);
            }
        }

        // BFS expansion
        while (!queue.isEmpty()) {
            HGNode current = queue.poll();
            int currentDepth = affectedWithDepth.get(current);
            if (currentDepth >= effectiveMaxDepth) continue;

            for (HGCoreDependency dep : current.getIncomingCoreDependencies()) {
                HGNode from = dep.getFrom();
                if (sourceSubtreeIds.contains(from.getIdentifier())) continue;
                if (affectedWithDepth.containsKey(from)) continue;

                affectedWithDepth.put(from, currentDepth + 1);
                affectedWeight.put(from, dep.getWeight());
                queue.add(from);
            }
        }

        // Compute depth distribution
        Map<Integer, Integer> depthDistribution = new TreeMap<>();
        int maxDepthReached = 0;
        for (int depth : affectedWithDepth.values()) {
            depthDistribution.merge(depth, 1, Integer::sum);
            maxDepthReached = Math.max(maxDepthReached, depth);
        }

        // Compute by_scope: group affected leaf nodes by children of groupingScope
        List<? extends HGNode> scopeChildren = groupingScope.getChildren();
        List<Map<String, Object>> byScope = new ArrayList<>();

        for (HGNode scopeChild : scopeChildren) {
            Set<Object> scopeDescIds = new HashSet<>();
            collectDescendantIds(scopeChild, scopeDescIds);

            int affectedCount = 0;
            int minDepthInScope = Integer.MAX_VALUE;
            int maxDepthInScope = 0;

            for (Map.Entry<HGNode, Integer> entry : affectedWithDepth.entrySet()) {
                if (scopeDescIds.contains(entry.getKey().getIdentifier())) {
                    affectedCount++;
                    minDepthInScope = Math.min(minDepthInScope, entry.getValue());
                    maxDepthInScope = Math.max(maxDepthInScope, entry.getValue());
                }
            }

            if (affectedCount > 0) {
                // Compute weight from scope to source
                HGAggregatedDependency aggDep = scopeChild.getOutgoingDependenciesTo(sourceNode);
                int weightToSource = aggDep != null ? aggDep.getAggregatedWeight() : 0;

                Map<String, Object> scopeEntry = new LinkedHashMap<>();
                scopeEntry.put("scope", toNodeRefShort(scopeChild));
                scopeEntry.put("affected_descendant_count", affectedCount);
                scopeEntry.put("min_depth", minDepthInScope);
                scopeEntry.put("max_depth", maxDepthInScope);
                scopeEntry.put("weight_to_source", weightToSource);
                byScope.add(scopeEntry);
            }
        }
        byScope.sort((a, b) -> Integer.compare((int) b.get("affected_descendant_count"),
                (int) a.get("affected_descendant_count")));

        // Top affected nodes: ranked by weight descending
        List<Map<String, Object>> topAffected = affectedWithDepth.entrySet().stream()
                .sorted((a, b) -> {
                    int wa = affectedWeight.getOrDefault(a.getKey(), 0);
                    int wb = affectedWeight.getOrDefault(b.getKey(), 0);
                    return Integer.compare(wb, wa);
                })
                .limit(effectiveTopN)
                .map(entry -> {
                    Map<String, Object> nodeEntry = new LinkedHashMap<>();
                    nodeEntry.put("node", toNodeRefShort(entry.getKey()));
                    nodeEntry.put("depth", entry.getValue());
                    nodeEntry.put("weight_from_predecessor", affectedWeight.getOrDefault(entry.getKey(), 0));
                    return nodeEntry;
                })
                .toList();

        // Build result
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_affected_leaves", affectedWithDepth.size());
        summary.put("max_depth_reached", maxDepthReached);
        summary.put("depth_distribution", depthDistribution);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", toNodeRefShort(sourceNode));
        result.put("max_depth_requested", effectiveMaxDepth);
        result.put("summary", summary);
        result.put("by_scope", byScope);
        result.put("top_affected_nodes", topAffected);

        return result;
    }

    // --- helpers ---

    private void collectAllNodesAtLevel(HGNode referenceNode, Set<HGNode> result) {
        // Determine the depth of the reference node
        int depth = 0;
        HGNode walk = referenceNode;
        while (walk.getParent() != null) {
            depth++;
            walk = walk.getParent();
        }
        // walk is now root; collect all nodes at that depth
        collectNodesAtDepth(walk, depth, 0, result);
    }

    private void collectNodesAtDepth(HGNode node, int targetDepth, int currentDepth, Set<HGNode> result) {
        if (currentDepth == targetDepth) {
            result.add(node);
            return;
        }
        for (HGNode child : node.getChildren()) {
            collectNodesAtDepth(child, targetDepth, currentDepth + 1, result);
        }
    }

    private void collectDescendantIds(HGNode node, Set<Object> ids) {
        ids.add(node.getIdentifier());
        for (HGNode child : node.getChildren()) {
            collectDescendantIds(child, ids);
        }
    }

    private void collectBoundaryIncomingDeps(HGNode node, Set<Object> sourceSubtreeIds,
                                              List<HGCoreDependency> result) {
        // Leaf node: collect incoming deps from outside the subtree
        if (node.getChildren().isEmpty()) {
            for (HGCoreDependency dep : node.getIncomingCoreDependencies()) {
                if (!sourceSubtreeIds.contains(dep.getFrom().getIdentifier())) {
                    result.add(dep);
                }
            }
        } else {
            for (HGNode child : node.getChildren()) {
                collectBoundaryIncomingDeps(child, sourceSubtreeIds, result);
            }
        }
    }
}
