package org.slizaa.mcp.core;

import org.neo4j.driver.Record;
import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency;
import org.slizaa.hierarchicalgraph.core.model.HGCoreDependency;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import org.slizaa.hierarchicalgraph.core.algorithms.GraphUtils;
import org.slizaa.hierarchicalgraph.core.algorithms.IDependencyStructureMatrix;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider;

import java.util.*;

@Component
public class GraphMcpTools {

    private final HierarchicalGraphService graphService;

    public GraphMcpTools(HierarchicalGraphService graphService) {
        this.graphService = graphService;
    }

    @Tool(name = "find_node",
            description = "[Discovery and orientation] Look up nodes by name. This is the primary way to obtain node IDs and should be the " +
                    "first tool called when the user mentions a specific class, package, or artifact by name. " +
                    "Searches by name or fully qualified name using case-insensitive substring matching. " +
                    "Use the kind filter when names are ambiguous across node types (e.g. a package and a class " +
                    "sharing the same name).")
    public List<Map<String, Object>> findNode(
            @ToolParam(description = "Name or fragment to search for, e.g. 'ClusterService', 'payment.api'") String query,
            @ToolParam(description = "Optional node kind filter: 'Class', 'Interface', 'Enum', 'Annotation', 'Package', 'Artifact'",
                    required = false) String kind,
            @ToolParam(description = "Max results to return (1-50, default 10)", required = false) Integer limit) {

        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 50) : 10;
        INodeMetadataProvider mp = getMetadataProvider();

        String cypher = mp.getFindNodeCypherQuery(kind, effectiveLimit);

        var result = graphService.getBoltClient().syncExecCypherQuery(
                cypher, Map.of("query", query));

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Record record : result.records()) {
            long nodeId = record.get("nodeId").asLong();
            HGNode hgNode = graphService.getRootNode().lookupNode(nodeId);

            if (hgNode != null) {
                nodes.add(toNodeRef(hgNode));
            } else {
                // Node found in DB but not in HG model
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", nodeId);
                entry.put("name", record.get("name").asString(null));
                entry.put("qualified_name", record.get("fqn").asString(null));
                entry.put("kind", mp.getKindFromLabels(record.get("labels").asList(org.neo4j.driver.Value::asString)));
                nodes.add(entry);
            }
        }

        return nodes;
    }

    @Tool(name = "list_children",
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
                    "walk a tree to find specific items (use list_descendants or find_node).")
    public List<Map<String, Object>> listChildren(
            @ToolParam(description = "The node ID to list children for. Omit or pass null for root-level nodes.",
                    required = false) Long nodeId,
            @ToolParam(description = "Max results to return (1-200, default 50)", required = false) Integer limit) {

        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 200) : 50;

        List<? extends HGNode> children;
        if (nodeId == null) {
            children = graphService.getRootNode().getChildren();
        } else {
            HGNode node = graphService.getRootNode().lookupNode(nodeId);
            if (node == null) {
                return List.of(Map.of("error", "Node not found: " + nodeId));
            }
            children = node.getChildren();
        }

        return children.stream()
                .limit(effectiveLimit)
                .map(this::toNodeRef)
                .toList();
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

    @Tool(name = "describe_graph",
            description = "[Discovery and orientation] Return a structured overview of the loaded graph or a specified scope. " +
                    "This is the right first call when you don't know what kind of graph you're looking at. " +
                    "It provides node counts by kind, depth statistics, top-level children with dependency counts, " +
                    "and dependency kind distribution. For known graphs, it can be skipped.")
    public Map<String, Object> describeGraph(
            @ToolParam(description = "Scope node ID to describe. Omit for the full graph overview.",
                    required = false) Long scopeId) {

        var boltClient = graphService.getBoltClient();
        INodeMetadataProvider mp = getMetadataProvider();

        // Determine scope
        HGNode scopeNode;
        Map<String, Object> scopeRef;

        if (scopeId == null) {
            scopeNode = graphService.getRootNode();
            scopeRef = Map.of("id", "root", "name", "root", "qualified_name", "", "kind", "root");
        } else {
            scopeNode = graphService.getRootNode().lookupNode(scopeId);
            if (scopeNode == null) {
                return Map.of("error", "Scope node not found: " + scopeId);
            }
            scopeRef = toNodeRefShort(scopeNode);
        }

        Map<String, Object> params = scopeId != null ? Map.of("scopeId", scopeId) : Map.of();

        // Node count by kind
        var nodeCountResult = boltClient.syncExecCypherQuery(mp.getNodeCountCypherQuery(scopeId), params);
        Map<String, Object> nodeCountByKind = new LinkedHashMap<>();
        long totalNodeCount = 0;
        for (Record record : nodeCountResult.records()) {
            long cnt = record.get("cnt").asLong();
            nodeCountByKind.put(record.get("label").asString(), cnt);
            totalNodeCount += cnt;
        }

        // Depth statistics
        var depthResult = boltClient.syncExecCypherQuery(mp.getDepthStatsCypherQuery(scopeId), params);
        Map<String, Object> depthStats = new LinkedHashMap<>();
        if (!depthResult.records().isEmpty()) {
            Record rec = depthResult.records().get(0);
            depthStats.put("max_depth", rec.get("maxDepth").isNull() ? 0 : rec.get("maxDepth").asLong());
            depthStats.put("average_depth", rec.get("avgDepth").isNull() ? 0 :
                    Math.round(rec.get("avgDepth").asDouble() * 10.0) / 10.0);
        }

        // Dependency kind distribution
        var depKindResult = boltClient.syncExecCypherQuery(
                mp.getDependencyKindDistributionCypherQuery(scopeId), params);
        Map<String, Object> dependencyKinds = new LinkedHashMap<>();
        for (Record record : depKindResult.records()) {
            dependencyKinds.put(record.get("kind").asString(), record.get("cnt").asLong());
        }

        // Top-level children from HG model
        List<Map<String, Object>> topChildren = scopeNode.getChildren().stream()
                .map(child -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("node", toNodeRefShort(child));
                    entry.put("descendant_count", countDescendants(child));
                    entry.put("outgoing_dep_count", child.getAccumulatedOutgoingCoreDependencies().size());
                    entry.put("incoming_dep_count", child.getAccumulatedIncomingCoreDependencies().size());
                    return entry;
                })
                .toList();

        // Scan metadata
        var metadataResult = boltClient.syncExecCypherQuery(mp.getScanMetadataCypherQuery());
        Map<String, Object> scanMetadata = new LinkedHashMap<>();
        scanMetadata.put("scanner", mp.getScannerName());
        if (!metadataResult.records().isEmpty()) {
            var val = metadataResult.records().get(0).get("scannedAt");
            if (!val.isNull()) {
                scanMetadata.put("scanned_at", val.asString());
            }
        }

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scopeRef);
        result.put("node_count_total", totalNodeCount);
        result.put("node_count_by_kind", nodeCountByKind);
        result.put("depth_statistics", depthStats);
        result.put("top_level_children", topChildren);
        result.put("dependency_kinds", dependencyKinds);
        result.put("scan_metadata", scanMetadata);

        return result;
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

    @Tool(name = "list_methods",
            description = "[Detail-level] Return the methods declared on a type, with lightweight metadata for each. " +
                    "Use this when you have identified a type and want to understand its method-level composition — " +
                    "for example, 'what does ClusterService contain?' or 'list the public methods of this class.' " +
                    "Returns each method as a NodeRef plus counts: parameter count, throws count, annotation count, " +
                    "plus modifier flags. The counts let you decide which methods are worth investigating further " +
                    "(high annotation_count suggests framework wiring; high throws_count suggests error-handling complexity). " +
                    "The summary block gives a structural overview (visibility distribution, constructor count, " +
                    "declared vs. inherited) that's often more useful than enumerating every method. " +
                    "Common parameter patterns: " +
                    "Just type_id: enumerate all declared methods. " +
                    "type_id + modifier_filter: ['public']: list the public API. " +
                    "type_id + name_pattern: 'init': find initialization-style methods. " +
                    "type_id + include_inherited: true: see the full callable surface, including methods from ancestors. " +
                    "Important: include_inherited only shows methods from ancestor types that were part of the scan. " +
                    "Methods from external libraries (e.g. java.lang.Object, framework base classes) are only visible " +
                    "if those libraries were included in the jQAssistant scan. If inherited_count is 0, it may mean " +
                    "the superclass is outside the scanned codebase, not that there are no inherited methods. " +
                    "For deep information about one specific method (parameters, return type as a NodeRef, throws, " +
                    "annotations, location), use method_details. " +
                    "For 'which methods call this one?' or dependency-driven views, use detail_dependencies.")
    public Map<String, Object> listMethods(
            @ToolParam(description = "The node ID of the type whose methods should be enumerated. " +
                    "Must be a type-kind node (Class, Interface, Enum, Annotation, Record).") long typeId,
            @ToolParam(description = "Optional case-insensitive substring match against the method name.",
                    required = false) String namePattern,
            @ToolParam(description = "Optional list of Java modifiers, ANDed together. " +
                    "Allowed values: public, protected, private, package-private, static, final, abstract, synchronized, native, default.",
                    required = false) List<String> modifierFilter,
            @ToolParam(description = "Whether to include inherited methods from superclasses and interfaces. Default false.",
                    required = false) Boolean includeInherited,
            @ToolParam(description = "Max methods to return (1-500, default 50).", required = false) Integer limit) {

        // Validate modifier_filter values
        Set<String> allowedModifiers = Set.of("public", "protected", "private", "package-private",
                "static", "final", "abstract", "synchronized", "native", "default");
        if (modifierFilter != null) {
            for (String mod : modifierFilter) {
                if (!allowedModifiers.contains(mod)) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", "INVALID_MODIFIER");
                    error.put("message", "Invalid modifier: '" + mod + "'. Allowed values: " + allowedModifiers);
                    error.put("invalid_value", mod);
                    return error;
                }
            }
        }

        // Validate type_id exists in HG model
        HGNode typeNode = graphService.getRootNode().lookupNode(typeId);
        if (typeNode == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "NODE_NOT_FOUND");
            error.put("message", "Node not found: " + typeId + ". Re-resolve via find_node.");
            return error;
        }

        // Validate it's a type kind
        INodeMetadataProvider mp = getMetadataProvider();
        String kind = mp.getKind(typeNode);
        Set<String> typeKinds = Set.of("Class", "Interface", "Enum", "Annotation", "Record");
        if (!typeKinds.contains(kind)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "WRONG_NODE_KIND");
            error.put("message", "Node " + typeId + " is a '" + kind + "', not a type. " +
                    "list_methods requires a Class, Interface, Enum, Annotation, or Record.");
            error.put("actual_kind", kind);
            return error;
        }

        boolean inherited = includeInherited != null && includeInherited;
        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 500) : 50;

        // Build Cypher query
        String cypher = buildListMethodsCypher(inherited);

        var queryResult = graphService.getBoltClient().syncExecCypherQuery(
                cypher, Map.of("typeId", typeId));

        // Process results and apply filters
        List<Map<String, Object>> allMethods = new ArrayList<>();
        int totalPublic = 0, totalProtected = 0, totalPrivate = 0, totalPackagePrivate = 0;
        int totalConstructors = 0, totalAbstract = 0;
        int totalDeclared = 0, totalInherited = 0;

        for (Record record : queryResult.records()) {
            long methodId = record.get("methodId").asLong();
            String methodName = record.get("methodName").asString("");
            String methodFqn = record.get("methodFqn").asString("");
            boolean isConstructor = record.get("isConstructor").asBoolean(false);
            long declaringTypeId = record.get("declaringTypeId").asLong();
            String declaringTypeName = record.get("declaringTypeName").asString("");
            String declaringTypeFqn = record.get("declaringTypeFqn").asString("");
            List<String> declaringTypeLabels = record.get("declaringTypeLabels").asList(org.neo4j.driver.Value::asString);
            String returnTypeName = record.get("returnTypeName").isNull() ? "void" : record.get("returnTypeName").asString("void");
            long paramCount = record.get("paramCount").asLong(0);
            long throwsCount = record.get("throwsCount").asLong(0);
            long annotationCount = record.get("annotationCount").asLong(0);

            // Extract modifiers from method properties
            List<String> modifiers = extractModifiers(record);

            // Determine visibility
            String visibility = getVisibility(modifiers);

            // Apply name_pattern filter
            if (namePattern != null && !namePattern.isBlank()) {
                if (!methodName.toLowerCase().contains(namePattern.toLowerCase())) {
                    continue;
                }
            }

            // Apply modifier_filter
            if (modifierFilter != null && !modifierFilter.isEmpty()) {
                boolean allMatch = true;
                for (String requiredMod : modifierFilter) {
                    if (requiredMod.equals("package-private")) {
                        if (!visibility.equals("package-private")) {
                            allMatch = false;
                            break;
                        }
                    } else if (!modifiers.contains(requiredMod)) {
                        allMatch = false;
                        break;
                    }
                }
                if (!allMatch) continue;
            }

            // Count for summary
            boolean isInherited = declaringTypeId != typeId;
            if (isInherited) totalInherited++; else totalDeclared++;
            switch (visibility) {
                case "public" -> totalPublic++;
                case "protected" -> totalProtected++;
                case "private" -> totalPrivate++;
                case "package-private" -> totalPackagePrivate++;
            }
            if (isConstructor) totalConstructors++;
            if (modifiers.contains("abstract")) totalAbstract++;

            // Build method entry
            Map<String, Object> methodEntry = new LinkedHashMap<>();

            // NodeRef for method
            Map<String, Object> nodeRef = new LinkedHashMap<>();
            nodeRef.put("id", methodId);
            nodeRef.put("name", methodName);
            nodeRef.put("qualified_name", methodFqn);
            nodeRef.put("kind", isConstructor ? "java.constructor" : "java.method");
            nodeRef.put("parent_id", declaringTypeId);
            nodeRef.put("parent_kind", mp.getKindFromLabels(declaringTypeLabels));
            methodEntry.put("node", nodeRef);

            methodEntry.put("modifiers", modifiers);
            methodEntry.put("return_type_name", returnTypeName);
            methodEntry.put("parameter_count", paramCount);
            methodEntry.put("throws_count", throwsCount);
            methodEntry.put("annotation_count", annotationCount);
            methodEntry.put("is_constructor", isConstructor);
            methodEntry.put("is_inherited", isInherited);

            if (isInherited) {
                Map<String, Object> declaredBy = new LinkedHashMap<>();
                declaredBy.put("id", declaringTypeId);
                declaredBy.put("name", declaringTypeName);
                declaredBy.put("qualified_name", declaringTypeFqn);
                declaredBy.put("kind", mp.getKindFromLabels(declaringTypeLabels));
                methodEntry.put("declared_by", declaredBy);
            } else {
                methodEntry.put("declared_by", null);
            }

            allMethods.add(methodEntry);
        }

        int totalMatching = allMethods.size();
        boolean truncated = totalMatching > effectiveLimit;
        List<Map<String, Object>> returnedMethods = allMethods.stream()
                .limit(effectiveLimit)
                .toList();

        // Build type ref
        Map<String, Object> typeRef = new LinkedHashMap<>();
        typeRef.put("id", typeId);
        typeRef.put("name", mp.getName(typeNode));
        typeRef.put("qualified_name", mp.getQualifiedName(typeNode));
        typeRef.put("kind", kind);

        // Build summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_matching", totalMatching);
        summary.put("returned", returnedMethods.size());
        summary.put("truncated", truncated);
        summary.put("declared_count", totalDeclared);
        summary.put("inherited_count", totalInherited);
        Map<String, Object> byVisibility = new LinkedHashMap<>();
        byVisibility.put("public", totalPublic);
        byVisibility.put("protected", totalProtected);
        byVisibility.put("private", totalPrivate);
        byVisibility.put("package-private", totalPackagePrivate);
        summary.put("by_visibility", byVisibility);
        summary.put("constructors", totalConstructors);
        summary.put("abstract_methods", totalAbstract);

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", typeRef);
        result.put("methods", returnedMethods);
        result.put("summary", summary);

        return result;
    }

    @Tool(name = "detail_dependencies",
            description = "[Detail-level] Return the method-level and field-level dependencies between a source subtree " +
                    "and a target subtree. This is the drill-down tool that bridges the hierarchical level and the " +
                    "detail level — given an aggregated dependency you've identified (typically via aggregated_outgoing, " +
                    "aggregated_incoming, or outgoing_core_dependencies), this returns the underlying concrete " +
                    "method/field edges that explain it. " +
                    "Returns each edge with full NodeRefs for source and target (including parent_id), the " +
                    "relationship kind, and source location when available. The summary block groups edges by " +
                    "relationship kind (by_relationship) and by source type (by_source_type) — these are often " +
                    "more useful than enumerating individual edges. " +
                    "Common parameter patterns: " +
                    "from_id + to_id (no relationship): see the full detail-level coupling between two subtrees. " +
                    "from_id + to_id + relationship 'throws': drill into one specific kind of coupling. " +
                    "from_id = root_id + to_id = some_annotation_type: global query — find every method with this annotation. " +
                    "from_id = root_id + to_id = some_type + relationship 'has_type': find every field of this type. " +
                    "from_id = to_id: internal coupling within a subtree at the method/field level. " +
                    "Important: the graph only contains detail-level edges for code that was part of the scan. " +
                    "Dependencies to external library types may not have method-level detail. " +
                    "Relationship kinds: throws, calls, returns, parameter_type, reads_field, writes_field, " +
                    "overrides, annotated_by, parameter_annotated_by, has_type, read_by, written_by.")
    public Map<String, Object> detailDependencies(
            @ToolParam(description = "Source subtree root node ID. All types under this node are included as sources. " +
                    "Pass the root node ID for global queries.") long fromId,
            @ToolParam(description = "Target subtree root node ID. All types under this node are included as targets.") long toId,
            @ToolParam(description = "Optional relationship kind filter. One of: throws, calls, returns, parameter_type, " +
                    "reads_field, writes_field, overrides, annotated_by, parameter_annotated_by, has_type, read_by, written_by.",
                    required = false) String relationship,
            @ToolParam(description = "Max edges to return (1-500, default 50).", required = false) Integer limit) {

        // Allowed relationship kinds
        Set<String> allowedRelationships = Set.of("throws", "calls", "returns",
                "parameter_type", "reads_field", "writes_field", "overrides",
                "annotated_by", "parameter_annotated_by", "has_type", "read_by", "written_by");

        if (relationship != null && !relationship.isBlank() && !allowedRelationships.contains(relationship)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "INVALID_RELATIONSHIP");
            error.put("message", "Invalid relationship: '" + relationship + "'. Allowed values: " + allowedRelationships);
            error.put("invalid_value", relationship);
            return error;
        }

        // Resolve nodes (handle root node specially)
        HGNode fromNode = resolveNodeOrRoot(fromId);
        if (fromNode == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "NODE_NOT_FOUND");
            error.put("message", "Source node not found: " + fromId + ". Re-resolve via find_node.");
            return error;
        }

        HGNode toNode = resolveNodeOrRoot(toId);
        if (toNode == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "NODE_NOT_FOUND");
            error.put("message", "Target node not found: " + toId + ". Re-resolve via find_node.");
            return error;
        }

        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 500) : 50;
        INodeMetadataProvider mp = getMetadataProvider();

        // Resolve subtrees to type IDs
        List<Long> fromTypeIds = collectSubtreeTypeIds(fromNode, mp);
        List<Long> toTypeIds = collectSubtreeTypeIds(toNode, mp);

        if (fromTypeIds.isEmpty() || toTypeIds.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("from_scope", toNodeRefShort(fromNode));
            result.put("to_scope", toNodeRefShort(toNode));
            result.put("edges", List.of());
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_edges", 0);
            summary.put("returned", 0);
            summary.put("truncated", false);
            summary.put("by_relationship", relationship != null ? Map.of(relationship, 0) : Map.of());
            summary.put("by_source_type", List.of());
            result.put("summary", summary);
            return result;
        }

        // Determine effective relationship filter
        String effectiveRel = (relationship != null && !relationship.isBlank()) ? relationship : null;

        // Build and execute Cypher query
        String cypher = buildDetailDependenciesCypher(effectiveRel);
        var queryResult = graphService.getBoltClient().syncExecCypherQuery(
                cypher, Map.of("fromTypes", fromTypeIds, "toTypes", toTypeIds));

        // Process results
        List<Map<String, Object>> allEdges = new ArrayList<>();
        Map<String, Integer> byRelationship = new TreeMap<>();
        Map<Long, Integer> sourceTypeCounts = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> sourceTypeRefs = new LinkedHashMap<>();

        for (Record record : queryResult.records()) {
            String relName = record.get("relName").asString();

            // Build source NodeRef
            long srcId = record.get("srcId").asLong();
            String srcName = record.get("srcName").asString("");
            String srcFqn = record.get("srcFqn").asString("");
            List<String> srcLabels = record.get("srcLabels").asList(org.neo4j.driver.Value::asString);
            long srcTypeId = record.get("srcTypeId").asLong();
            String srcTypeName = record.get("srcTypeName").asString("");
            String srcTypeFqn = record.get("srcTypeFqn").asString("");
            List<String> srcTypeLabels = record.get("srcTypeLabels").asList(org.neo4j.driver.Value::asString);

            // Build target NodeRef
            long tgtId = record.get("tgtId").asLong();
            String tgtName = record.get("tgtName").asString("");
            String tgtFqn = record.get("tgtFqn").asString("");
            List<String> tgtLabels = record.get("tgtLabels").asList(org.neo4j.driver.Value::asString);
            long tgtTypeId = record.get("tgtTypeId").asLong();
            String tgtTypeName = record.get("tgtTypeName").asString("");
            String tgtTypeFqn = record.get("tgtTypeFqn").asString("");
            List<String> tgtTypeLabels = record.get("tgtTypeLabels").asList(org.neo4j.driver.Value::asString);

            // Build edge
            Map<String, Object> edge = new LinkedHashMap<>();

            Map<String, Object> fromRef = new LinkedHashMap<>();
            fromRef.put("id", srcId);
            fromRef.put("name", srcName);
            fromRef.put("qualified_name", srcFqn);
            fromRef.put("kind", deriveDetailKind(srcLabels));
            fromRef.put("parent_id", srcTypeId);
            fromRef.put("parent_kind", mp.getKindFromLabels(srcTypeLabels));
            edge.put("from", fromRef);

            Map<String, Object> toRef = new LinkedHashMap<>();
            toRef.put("id", tgtId);
            toRef.put("name", tgtName);
            toRef.put("qualified_name", tgtFqn);
            toRef.put("kind", deriveDetailKind(tgtLabels));
            if (tgtTypeId != tgtId) {
                toRef.put("parent_id", tgtTypeId);
                toRef.put("parent_kind", mp.getKindFromLabels(tgtTypeLabels));
            }
            edge.put("to", toRef);

            edge.put("relationship", relName);

            // Location (line number if available)
            long lineNumber = record.get("lineNumber").asLong(-1);
            if (lineNumber > 0) {
                Map<String, Object> location = new LinkedHashMap<>();
                location.put("line_number", lineNumber);
                edge.put("location", location);
            } else {
                edge.put("location", null);
            }

            allEdges.add(edge);

            // Summary counts
            byRelationship.merge(relName, 1, Integer::sum);
            sourceTypeCounts.merge(srcTypeId, 1, Integer::sum);
            if (!sourceTypeRefs.containsKey(srcTypeId)) {
                Map<String, Object> typeRef = new LinkedHashMap<>();
                typeRef.put("id", srcTypeId);
                typeRef.put("name", srcTypeName);
                typeRef.put("qualified_name", srcTypeFqn);
                typeRef.put("kind", mp.getKindFromLabels(srcTypeLabels));
                sourceTypeRefs.put(srcTypeId, typeRef);
            }
        }

        // Sort edges: by relationship, then source type FQN, then source name, then line number
        allEdges.sort((a, b) -> {
            int cmp = ((String) a.get("relationship")).compareTo((String) b.get("relationship"));
            if (cmp != 0) return cmp;
            @SuppressWarnings("unchecked")
            Map<String, Object> aFrom = (Map<String, Object>) a.get("from");
            @SuppressWarnings("unchecked")
            Map<String, Object> bFrom = (Map<String, Object>) b.get("from");
            cmp = String.valueOf(aFrom.get("qualified_name")).compareTo(String.valueOf(bFrom.get("qualified_name")));
            if (cmp != 0) return cmp;
            cmp = String.valueOf(aFrom.get("name")).compareTo(String.valueOf(bFrom.get("name")));
            if (cmp != 0) return cmp;
            @SuppressWarnings("unchecked")
            Map<String, Object> aLoc = (Map<String, Object>) a.get("location");
            @SuppressWarnings("unchecked")
            Map<String, Object> bLoc = (Map<String, Object>) b.get("location");
            long aLine = aLoc != null ? (long) aLoc.get("line_number") : 0;
            long bLine = bLoc != null ? (long) bLoc.get("line_number") : 0;
            return Long.compare(aLine, bLine);
        });

        // Truncate
        int totalEdges = allEdges.size();
        boolean truncated = totalEdges > effectiveLimit;
        List<Map<String, Object>> returnedEdges = allEdges.stream()
                .limit(effectiveLimit)
                .toList();

        // Build by_source_type (top 10, sorted by count descending)
        List<Map<String, Object>> bySourceType = sourceTypeCounts.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("type", sourceTypeRefs.get(e.getKey()));
                    entry.put("edge_count", e.getValue());
                    return entry;
                })
                .toList();

        // Build summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_edges", totalEdges);
        summary.put("returned", returnedEdges.size());
        summary.put("truncated", truncated);
        if (effectiveRel != null && !byRelationship.containsKey(effectiveRel)) {
            byRelationship.put(effectiveRel, 0);
        }
        summary.put("by_relationship", byRelationship);
        summary.put("by_source_type", bySourceType);

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from_scope", toNodeRefShort(fromNode));
        result.put("to_scope", toNodeRefShort(toNode));
        result.put("edges", returnedEdges);
        result.put("summary", summary);

        return result;
    }

    private HGNode resolveNodeOrRoot(long nodeId) {
        HGNode node = graphService.getRootNode().lookupNode(nodeId);
        if (node == null) {
            // Check if it's the root node itself
            Object rootId = graphService.getRootNode().getIdentifier();
            if (rootId instanceof Long && (Long) rootId == nodeId) {
                return graphService.getRootNode();
            }
        }
        return node;
    }

    private List<Long> collectSubtreeTypeIds(HGNode node, INodeMetadataProvider mp) {
        Set<String> typeKinds = Set.of("Class", "Interface", "Enum", "Annotation", "Record");
        List<Long> typeIds = new ArrayList<>();
        collectSubtreeTypeIdsRecursive(node, typeKinds, mp, typeIds);
        return typeIds;
    }

    private void collectSubtreeTypeIdsRecursive(HGNode node, Set<String> typeKinds,
                                                 INodeMetadataProvider mp, List<Long> result) {
        String kind = mp.getKind(node);
        if (typeKinds.contains(kind)) {
            result.add((Long) node.getIdentifier());
        }
        for (HGNode child : node.getChildren()) {
            collectSubtreeTypeIdsRecursive(child, typeKinds, mp, result);
        }
    }

    private String deriveDetailKind(List<String> labels) {
        if (labels.contains("Constructor")) return "java.constructor";
        if (labels.contains("Method")) return "java.method";
        if (labels.contains("Field")) return "java.field";
        if (labels.contains("Interface")) return "java.interface";
        if (labels.contains("Enum")) return "java.enum";
        if (labels.contains("Annotation")) return "java.annotation";
        if (labels.contains("Class")) return "java.class";
        if (labels.contains("Type")) return "java.class";
        return "unknown";
    }

    private String buildDetailDependenciesCypher(String relationship) {
        List<String> branches = new ArrayList<>();

        // Determine which groups to query
        Set<String> rels = relationship != null ? Set.of(relationship) :
                Set.of("throws", "calls", "returns", "parameter_type", "reads_field",
                        "writes_field", "overrides", "annotated_by", "parameter_annotated_by",
                        "has_type", "read_by", "written_by");

        // Group A: Method -> Type (throws, returns)
        if (rels.contains("throws")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[r:THROWS]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'throws' AS relName, src.firstLineNumber AS lineNumber""");
        }

        if (rels.contains("returns")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[r:RETURNS]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'returns' AS relName, src.firstLineNumber AS lineNumber""");
        }

        // Group B: Method -> Method (calls, overrides)
        if (rels.contains("calls")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[r:INVOKES]->(tgt:Method)<-[:DECLARES]-(tt:Type)
                WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tt) AS tgtTypeId, tt.name AS tgtTypeName, tt.fqn AS tgtTypeFqn, labels(tt) AS tgtTypeLabels,
                       'calls' AS relName, r.lineNumber AS lineNumber""");
        }

        if (rels.contains("overrides")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[r:OVERRIDES]->(tgt:Method)<-[:DECLARES]-(tt:Type)
                WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tt) AS tgtTypeId, tt.name AS tgtTypeName, tt.fqn AS tgtTypeFqn, labels(tt) AS tgtTypeLabels,
                       'overrides' AS relName, src.firstLineNumber AS lineNumber""");
        }

        // Group C: Method -> Field (reads_field, writes_field)
        if (rels.contains("reads_field")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[r:READS]->(tgt:Field)<-[:DECLARES]-(tt:Type)
                WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tt) AS tgtTypeId, tt.name AS tgtTypeName, tt.fqn AS tgtTypeFqn, labels(tt) AS tgtTypeLabels,
                       'reads_field' AS relName, r.lineNumber AS lineNumber""");
        }

        if (rels.contains("writes_field")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[r:WRITES]->(tgt:Field)<-[:DECLARES]-(tt:Type)
                WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tt) AS tgtTypeId, tt.name AS tgtTypeName, tt.fqn AS tgtTypeFqn, labels(tt) AS tgtTypeLabels,
                       'writes_field' AS relName, r.lineNumber AS lineNumber""");
        }

        // Group D: Field <- Method (read_by, written_by) — reversed direction
        if (rels.contains("read_by")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Field)<-[r:READS]-(tgt:Method)<-[:DECLARES]-(tt:Type)
                WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tt) AS tgtTypeId, tt.name AS tgtTypeName, tt.fqn AS tgtTypeFqn, labels(tt) AS tgtTypeLabels,
                       'read_by' AS relName, r.lineNumber AS lineNumber""");
        }

        if (rels.contains("written_by")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Field)<-[r:WRITES]-(tgt:Method)<-[:DECLARES]-(tt:Type)
                WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tt) AS tgtTypeId, tt.name AS tgtTypeName, tt.fqn AS tgtTypeFqn, labels(tt) AS tgtTypeLabels,
                       'written_by' AS relName, r.lineNumber AS lineNumber""");
        }

        // Group E: Field -> Type (has_type via OF_TYPE)
        if (rels.contains("has_type")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Field)-[r:OF_TYPE]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'has_type' AS relName, null AS lineNumber""");
        }

        // Group F: Method/Field -> Annotation -> Type (annotated_by)
        if (rels.contains("annotated_by")) {
            // Method annotated by (through intermediate annotation node)
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'annotated_by' AS relName, src.firstLineNumber AS lineNumber""");
            // Field annotated by
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Field)-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'annotated_by' AS relName, null AS lineNumber""");
        }

        // Group G: Method -> Parameter -> Type (parameter_type)
        if (rels.contains("parameter_type")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[:HAS]->(p:Parameter)-[:OF_TYPE]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'parameter_type' AS relName, src.firstLineNumber AS lineNumber""");
        }

        // Group H: Method -> Parameter -> Annotation -> Type (parameter_annotated_by)
        if (rels.contains("parameter_annotated_by")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[:HAS]->(p:Parameter)-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'parameter_annotated_by' AS relName, src.firstLineNumber AS lineNumber""");
        }

        if (branches.isEmpty()) {
            // Shouldn't happen, but handle gracefully
            return "RETURN null AS srcId, null AS srcName, null AS srcFqn, null AS srcLabels, " +
                    "null AS srcTypeId, null AS srcTypeName, null AS srcTypeFqn, null AS srcTypeLabels, " +
                    "null AS tgtId, null AS tgtName, null AS tgtFqn, null AS tgtLabels, " +
                    "null AS tgtTypeId, null AS tgtTypeName, null AS tgtTypeFqn, null AS tgtTypeLabels, " +
                    "null AS relName, null AS lineNumber LIMIT 0";
        }

        return String.join(" UNION ALL ", branches);
    }

    @Tool(name = "list_fields",
            description = "[Detail-level] Return the fields declared on a type, with lightweight metadata for each. " +
                    "Use this when you have identified a type and want to understand its data members — " +
                    "for example, 'what fields does UserEntity have?' or 'list the autowired dependencies of this Spring component.' " +
                    "Returns each field as a NodeRef plus metadata: modifiers, field type name, annotation count, and flags " +
                    "like is_constant. The annotation_count is particularly valuable for framework-wiring questions — " +
                    "fields with annotations are often where Spring injection, JPA mappings, or validation rules live. " +
                    "The summary block surfaces aggregate signals like annotated_count, constant_count, and visibility " +
                    "distribution, which often tell the framework story before you even look at individual fields. " +
                    "Common parameter patterns: " +
                    "Just type_id: enumerate all declared fields. " +
                    "type_id + modifier_filter: ['private', 'final']: list constructor-injected dependencies. " +
                    "type_id + modifier_filter: ['static', 'final']: list the constants this type defines. " +
                    "type_id + name_pattern: 'id': find ID-like fields. " +
                    "type_id + include_inherited: true: see all fields, including inherited ones. " +
                    "Important: include_inherited only shows fields from ancestor types that were part of the scan. " +
                    "Fields from external libraries (e.g. framework base classes) are only visible if those libraries " +
                    "were included in the jQAssistant scan. If inherited_count is 0, it may mean the superclass is " +
                    "outside the scanned codebase, not that there are no inherited fields. " +
                    "For deep information about one specific field (full type as a NodeRef, list of annotations, " +
                    "methods that read or write it), use field_details. " +
                    "For 'which methods read this field?' or dependency-driven views, use detail_dependencies. " +
                    "For methods rather than fields, use list_methods (same shape, different entity).")
    public Map<String, Object> listFields(
            @ToolParam(description = "The node ID of the type whose fields should be enumerated. " +
                    "Must be a type-kind node (Class, Interface, Enum, Annotation, Record).") long typeId,
            @ToolParam(description = "Optional case-insensitive substring match against the field name.",
                    required = false) String namePattern,
            @ToolParam(description = "Optional list of Java modifiers, ANDed together. " +
                    "Allowed values: public, protected, private, package-private, static, final, transient, volatile.",
                    required = false) List<String> modifierFilter,
            @ToolParam(description = "Whether to include inherited fields from superclasses. Default false.",
                    required = false) Boolean includeInherited,
            @ToolParam(description = "Max fields to return (1-500, default 50).", required = false) Integer limit) {

        // Validate modifier_filter values
        Set<String> allowedModifiers = Set.of("public", "protected", "private", "package-private",
                "static", "final", "transient", "volatile");
        if (modifierFilter != null) {
            for (String mod : modifierFilter) {
                if (!allowedModifiers.contains(mod)) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", "INVALID_MODIFIER");
                    error.put("message", "Invalid modifier: '" + mod + "'. Allowed values for fields: " + allowedModifiers);
                    error.put("invalid_value", mod);
                    return error;
                }
            }
        }

        // Validate type_id exists in HG model
        HGNode typeNode = graphService.getRootNode().lookupNode(typeId);
        if (typeNode == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "NODE_NOT_FOUND");
            error.put("message", "Node not found: " + typeId + ". Re-resolve via find_node.");
            return error;
        }

        // Validate it's a type kind
        INodeMetadataProvider mp = getMetadataProvider();
        String kind = mp.getKind(typeNode);
        Set<String> typeKinds = Set.of("Class", "Interface", "Enum", "Annotation", "Record");
        if (!typeKinds.contains(kind)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "WRONG_NODE_KIND");
            error.put("message", "Node " + typeId + " is a '" + kind + "', not a type. " +
                    "list_fields requires a Class, Interface, Enum, Annotation, or Record.");
            error.put("actual_kind", kind);
            return error;
        }

        boolean inherited = includeInherited != null && includeInherited;
        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 500) : 50;

        // Build Cypher query
        String cypher = buildListFieldsCypher(inherited);

        var queryResult = graphService.getBoltClient().syncExecCypherQuery(
                cypher, Map.of("typeId", typeId));

        // Process results and apply filters
        List<Map<String, Object>> allFields = new ArrayList<>();
        int totalPublic = 0, totalProtected = 0, totalPrivate = 0, totalPackagePrivate = 0;
        int totalAnnotated = 0, totalStatic = 0, totalFinal = 0, totalConstant = 0;
        int totalDeclared = 0, totalInherited = 0;

        for (Record record : queryResult.records()) {
            long fieldId = record.get("fieldId").asLong();
            String fieldName = record.get("fieldName").asString("");
            String fieldFqn = record.get("fieldFqn").asString("");
            long declaringTypeId = record.get("declaringTypeId").asLong();
            String declaringTypeName = record.get("declaringTypeName").asString("");
            String declaringTypeFqn = record.get("declaringTypeFqn").asString("");
            List<String> declaringTypeLabels = record.get("declaringTypeLabels").asList(org.neo4j.driver.Value::asString);
            String fieldTypeName = record.get("fieldTypeName").isNull() ? "unknown" : record.get("fieldTypeName").asString("unknown");
            long annotationCount = record.get("annotationCount").asLong(0);

            // Extract modifiers
            List<String> modifiers = extractFieldModifiers(record);

            // Determine visibility
            String visibility = getVisibility(modifiers);

            // Apply name_pattern filter
            if (namePattern != null && !namePattern.isBlank()) {
                if (!fieldName.toLowerCase().contains(namePattern.toLowerCase())) {
                    continue;
                }
            }

            // Apply modifier_filter
            if (modifierFilter != null && !modifierFilter.isEmpty()) {
                boolean allMatch = true;
                for (String requiredMod : modifierFilter) {
                    if (requiredMod.equals("package-private")) {
                        if (!visibility.equals("package-private")) {
                            allMatch = false;
                            break;
                        }
                    } else if (!modifiers.contains(requiredMod)) {
                        allMatch = false;
                        break;
                    }
                }
                if (!allMatch) continue;
            }

            // Compute is_constant
            boolean isConstant = modifiers.contains("static") && modifiers.contains("final");

            // Count for summary
            boolean isInherited = declaringTypeId != typeId;
            if (isInherited) totalInherited++; else totalDeclared++;
            switch (visibility) {
                case "public" -> totalPublic++;
                case "protected" -> totalProtected++;
                case "private" -> totalPrivate++;
                case "package-private" -> totalPackagePrivate++;
            }
            if (annotationCount > 0) totalAnnotated++;
            if (modifiers.contains("static")) totalStatic++;
            if (modifiers.contains("final")) totalFinal++;
            if (isConstant) totalConstant++;

            // Build field entry
            Map<String, Object> fieldEntry = new LinkedHashMap<>();

            // NodeRef for field
            Map<String, Object> nodeRef = new LinkedHashMap<>();
            nodeRef.put("id", fieldId);
            nodeRef.put("name", fieldName);
            nodeRef.put("qualified_name", fieldFqn);
            nodeRef.put("kind", "java.field");
            nodeRef.put("parent_id", declaringTypeId);
            nodeRef.put("parent_kind", mp.getKindFromLabels(declaringTypeLabels));
            fieldEntry.put("node", nodeRef);

            fieldEntry.put("modifiers", modifiers);
            fieldEntry.put("field_type_name", fieldTypeName);
            fieldEntry.put("annotation_count", annotationCount);
            fieldEntry.put("is_constant", isConstant);
            fieldEntry.put("is_inherited", isInherited);

            if (isInherited) {
                Map<String, Object> declaredBy = new LinkedHashMap<>();
                declaredBy.put("id", declaringTypeId);
                declaredBy.put("name", declaringTypeName);
                declaredBy.put("qualified_name", declaringTypeFqn);
                declaredBy.put("kind", mp.getKindFromLabels(declaringTypeLabels));
                fieldEntry.put("declared_by", declaredBy);
            } else {
                fieldEntry.put("declared_by", null);
            }

            allFields.add(fieldEntry);
        }

        int totalMatching = allFields.size();
        boolean truncated = totalMatching > effectiveLimit;
        List<Map<String, Object>> returnedFields = allFields.stream()
                .limit(effectiveLimit)
                .toList();

        // Build type ref
        Map<String, Object> typeRef = new LinkedHashMap<>();
        typeRef.put("id", typeId);
        typeRef.put("name", mp.getName(typeNode));
        typeRef.put("qualified_name", mp.getQualifiedName(typeNode));
        typeRef.put("kind", kind);

        // Build summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_matching", totalMatching);
        summary.put("returned", returnedFields.size());
        summary.put("truncated", truncated);
        summary.put("declared_count", totalDeclared);
        summary.put("inherited_count", totalInherited);
        Map<String, Object> byVisibility = new LinkedHashMap<>();
        byVisibility.put("public", totalPublic);
        byVisibility.put("protected", totalProtected);
        byVisibility.put("private", totalPrivate);
        byVisibility.put("package-private", totalPackagePrivate);
        summary.put("by_visibility", byVisibility);
        summary.put("annotated_count", totalAnnotated);
        summary.put("static_count", totalStatic);
        summary.put("final_count", totalFinal);
        summary.put("constant_count", totalConstant);

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", typeRef);
        result.put("fields", returnedFields);
        result.put("summary", summary);

        return result;
    }

    private String buildListFieldsCypher(boolean includeInherited) {
        if (includeInherited) {
            return """
                MATCH (t:Type) WHERE id(t) = $typeId
                CALL {
                    WITH t
                    MATCH (t)-[:DECLARES]->(f:Field)
                    MATCH (dt:Type)-[:DECLARES]->(f)
                    OPTIONAL MATCH (f)-[:OF_TYPE]->(ft:Type)
                    OPTIONAL MATCH (f)-[:ANNOTATED_BY]->(a)
                    RETURN f, dt, ft,
                           count(DISTINCT a) AS annotationCount
                    UNION
                    WITH t
                    MATCH (t)-[:EXTENDS*1..]->(ancestor:Type)-[:DECLARES]->(f:Field)
                    MATCH (dt:Type)-[:DECLARES]->(f)
                    OPTIONAL MATCH (f)-[:OF_TYPE]->(ft:Type)
                    OPTIONAL MATCH (f)-[:ANNOTATED_BY]->(a)
                    RETURN f, dt, ft,
                           count(DISTINCT a) AS annotationCount
                }
                RETURN id(f) AS fieldId,
                       f.name AS fieldName,
                       f.fqn AS fieldFqn,
                       f.visibility AS visibility,
                       f.static AS isStatic,
                       f.final AS isFinal,
                       f.transient AS isTransient,
                       f.volatile AS isVolatile,
                       id(dt) AS declaringTypeId,
                       dt.name AS declaringTypeName,
                       dt.fqn AS declaringTypeFqn,
                       labels(dt) AS declaringTypeLabels,
                       ft.fqn AS fieldTypeName,
                       annotationCount
                """;
        } else {
            return """
                MATCH (t:Type)-[:DECLARES]->(f:Field) WHERE id(t) = $typeId
                OPTIONAL MATCH (f)-[:OF_TYPE]->(ft:Type)
                OPTIONAL MATCH (f)-[:ANNOTATED_BY]->(a)
                RETURN id(f) AS fieldId,
                       f.name AS fieldName,
                       f.fqn AS fieldFqn,
                       f.visibility AS visibility,
                       f.static AS isStatic,
                       f.final AS isFinal,
                       f.transient AS isTransient,
                       f.volatile AS isVolatile,
                       id(t) AS declaringTypeId,
                       t.name AS declaringTypeName,
                       t.fqn AS declaringTypeFqn,
                       labels(t) AS declaringTypeLabels,
                       ft.fqn AS fieldTypeName,
                       count(DISTINCT a) AS annotationCount
                """;
        }
    }

    private List<String> extractFieldModifiers(Record record) {
        List<String> modifiers = new ArrayList<>();

        // Visibility first (canonical order)
        String visibility = record.get("visibility").asString(null);
        if (visibility != null) {
            modifiers.add(visibility.toLowerCase());
        } else {
            modifiers.add("package-private");
        }

        // Storage modifiers in canonical order
        if (record.get("isStatic").asBoolean(false)) modifiers.add("static");
        if (record.get("isFinal").asBoolean(false)) modifiers.add("final");
        if (record.get("isTransient").asBoolean(false)) modifiers.add("transient");
        if (record.get("isVolatile").asBoolean(false)) modifiers.add("volatile");

        return modifiers;
    }

    private String buildListMethodsCypher(boolean includeInherited) {
        if (includeInherited) {
            return """
                MATCH (t:Type) WHERE id(t) = $typeId
                CALL {
                    WITH t
                    MATCH (t)-[:DECLARES]->(m:Method)
                    MATCH (dt:Type)-[:DECLARES]->(m)
                    OPTIONAL MATCH (m)-[:RETURNS]->(rt:Type)
                    OPTIONAL MATCH (m)-[:HAS]->(p:Parameter)
                    OPTIONAL MATCH (m)-[:THROWS]->(ex:Type)
                    OPTIONAL MATCH (m)-[:ANNOTATED_BY]->(a)
                    RETURN m, dt, rt,
                           count(DISTINCT p) AS paramCount,
                           count(DISTINCT ex) AS throwsCount,
                           count(DISTINCT a) AS annotationCount
                    UNION
                    WITH t
                    MATCH (t)-[:EXTENDS|IMPLEMENTS*1..]->(ancestor:Type)-[:DECLARES]->(m:Method)
                    MATCH (dt:Type)-[:DECLARES]->(m)
                    OPTIONAL MATCH (m)-[:RETURNS]->(rt:Type)
                    OPTIONAL MATCH (m)-[:HAS]->(p:Parameter)
                    OPTIONAL MATCH (m)-[:THROWS]->(ex:Type)
                    OPTIONAL MATCH (m)-[:ANNOTATED_BY]->(a)
                    RETURN m, dt, rt,
                           count(DISTINCT p) AS paramCount,
                           count(DISTINCT ex) AS throwsCount,
                           count(DISTINCT a) AS annotationCount
                }
                RETURN id(m) AS methodId,
                       m.name AS methodName,
                       m.fqn AS methodFqn,
                       (m:Constructor) AS isConstructor,
                       m.visibility AS visibility,
                       m.static AS isStatic,
                       m.final AS isFinal,
                       m.abstract AS isAbstract,
                       m.synchronized AS isSynchronized,
                       m.native AS isNative,
                       m.default AS isDefault,
                       id(dt) AS declaringTypeId,
                       dt.name AS declaringTypeName,
                       dt.fqn AS declaringTypeFqn,
                       labels(dt) AS declaringTypeLabels,
                       rt.fqn AS returnTypeName,
                       paramCount, throwsCount, annotationCount
                """;
        } else {
            return """
                MATCH (t:Type)-[:DECLARES]->(m:Method) WHERE id(t) = $typeId
                OPTIONAL MATCH (m)-[:RETURNS]->(rt:Type)
                OPTIONAL MATCH (m)-[:HAS]->(p:Parameter)
                OPTIONAL MATCH (m)-[:THROWS]->(ex:Type)
                OPTIONAL MATCH (m)-[:ANNOTATED_BY]->(a)
                RETURN id(m) AS methodId,
                       m.name AS methodName,
                       m.fqn AS methodFqn,
                       (m:Constructor) AS isConstructor,
                       m.visibility AS visibility,
                       m.static AS isStatic,
                       m.final AS isFinal,
                       m.abstract AS isAbstract,
                       m.synchronized AS isSynchronized,
                       m.native AS isNative,
                       m.default AS isDefault,
                       id(t) AS declaringTypeId,
                       t.name AS declaringTypeName,
                       t.fqn AS declaringTypeFqn,
                       labels(t) AS declaringTypeLabels,
                       rt.fqn AS returnTypeName,
                       count(DISTINCT p) AS paramCount,
                       count(DISTINCT ex) AS throwsCount,
                       count(DISTINCT a) AS annotationCount
                """;
        }
    }

    private List<String> extractModifiers(Record record) {
        List<String> modifiers = new ArrayList<>();

        // Visibility first (canonical order)
        String visibility = record.get("visibility").asString(null);
        if (visibility != null) {
            modifiers.add(visibility.toLowerCase());
        } else {
            modifiers.add("package-private");
        }

        // Other modifiers in canonical order
        if (record.get("isStatic").asBoolean(false)) modifiers.add("static");
        if (record.get("isFinal").asBoolean(false)) modifiers.add("final");
        if (record.get("isAbstract").asBoolean(false)) modifiers.add("abstract");
        if (record.get("isSynchronized").asBoolean(false)) modifiers.add("synchronized");
        if (record.get("isNative").asBoolean(false)) modifiers.add("native");
        if (record.get("isDefault").asBoolean(false)) modifiers.add("default");

        return modifiers;
    }

    private String getVisibility(List<String> modifiers) {
        if (modifiers.contains("public")) return "public";
        if (modifiers.contains("protected")) return "protected";
        if (modifiers.contains("private")) return "private";
        return "package-private";
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

    @Tool(name = "list_descendants",
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
                    "more useful than the raw list — they answer 'what's there?' without needing to enumerate.")
    public Map<String, Object> listDescendants(
            @ToolParam(description = "Root node ID — descendants of this node will be searched") long rootId,
            @ToolParam(description = "Optional list of kinds to include (OR logic). " +
                    "E.g., ['Class', 'Interface'] returns classes and interfaces. " +
                    "Omit to include all kinds.", required = false) List<String> kindFilter,
            @ToolParam(description = "Optional list of kinds to exclude. Exclusion wins over inclusion if both match. " +
                    "E.g., ['Package'] to get everything except packages.", required = false) List<String> excludeKindFilter,
            @ToolParam(description = "Maximum results to return (1-5000, default 500).",
                    required = false) Integer limit) {

        HGNode rootNode = graphService.getRootNode().lookupNode(rootId);
        if (rootNode == null) {
            return Map.of("error", "Root node not found: " + rootId);
        }

        INodeMetadataProvider mp = getMetadataProvider();
        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 5000) : 500;
        int effectiveMaxDepth = 20;
        Set<String> includeKinds = kindFilter != null ? new HashSet<>(kindFilter) : null;
        Set<String> excludeKinds = excludeKindFilter != null ? new HashSet<>(excludeKindFilter) : null;

        // Depth-first preorder traversal collecting all matching descendants
        List<DescendantEntry> allMatches = new ArrayList<>();
        Map<String, Integer> kindCounts = new LinkedHashMap<>();
        Map<Object, Integer> parentCounts = new LinkedHashMap<>(); // parent ID → match count
        Map<Integer, Integer> depthDistribution = new TreeMap<>();

        Deque<DescendantTraversalState> stack = new ArrayDeque<>();
        for (HGNode child : rootNode.getChildren()) {
            stack.addLast(new DescendantTraversalState(child, 1));
        }

        while (!stack.isEmpty()) {
            DescendantTraversalState state = stack.pollFirst(); // preorder: poll from front
            HGNode node = state.node;
            int depth = state.depth;

            String kind = mp.getKind(node);
            boolean matches = true;

            // Kind filter
            if (includeKinds != null && !includeKinds.contains(kind)) {
                matches = false;
            }
            if (excludeKinds != null && excludeKinds.contains(kind)) {
                matches = false;
            }

            // Count for summary (all matches, not just returned)
            if (matches) {
                kindCounts.merge(kind, 1, Integer::sum);
                depthDistribution.merge(depth, 1, Integer::sum);
                Object parentId = node.getParent() != null ? node.getParent().getIdentifier() : null;
                if (parentId != null) {
                    parentCounts.merge(parentId, 1, Integer::sum);
                }
                allMatches.add(new DescendantEntry(node, depth));
            }

            // Continue traversal regardless of match (filter is on results, not traversal)
            if (depth < effectiveMaxDepth) {
                // Add children in reverse order so first child is polled first (preorder)
                List<? extends HGNode> children = node.getChildren();
                for (int i = children.size() - 1; i >= 0; i--) {
                    stack.addFirst(new DescendantTraversalState(children.get(i), depth + 1));
                }
            }
        }

        // Build result list (truncated to limit)
        boolean truncated = allMatches.size() > effectiveLimit;
        List<DescendantEntry> returnedMatches = allMatches.stream()
                .limit(effectiveLimit)
                .toList();

        List<Map<String, Object>> descendants = returnedMatches.stream()
                .map(entry -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("node", toNodeRefShort(entry.node));
                    map.put("parent_id", entry.node.getParent() != null ? entry.node.getParent().getIdentifier() : null);
                    map.put("depth", entry.depth);
                    map.put("outgoing_dep_count", entry.node.getAccumulatedOutgoingCoreDependencies().size());
                    map.put("incoming_dep_count", entry.node.getAccumulatedIncomingCoreDependencies().size());
                    return map;
                })
                .toList();

        // Collect all ancestors between root and each returned match (for tree reconstruction)
        Set<Object> matchIds = new HashSet<>();
        for (DescendantEntry entry : returnedMatches) {
            matchIds.add(entry.node.getIdentifier());
        }
        Map<Object, Map<String, Object>> ancestorMap = new LinkedHashMap<>();
        for (DescendantEntry entry : returnedMatches) {
            HGNode walk = entry.node.getParent();
            while (walk != null && !walk.getIdentifier().equals(rootNode.getIdentifier())) {
                Object walkId = walk.getIdentifier();
                if (ancestorMap.containsKey(walkId) || matchIds.contains(walkId)) {
                    break; // already collected or is itself a match
                }
                Map<String, Object> aEntry = new LinkedHashMap<>();
                aEntry.put("node", toNodeRefShort(walk));
                aEntry.put("parent_id", walk.getParent() != null ? walk.getParent().getIdentifier() : null);
                ancestorMap.put(walkId, aEntry);
                walk = walk.getParent();
            }
        }
        List<Map<String, Object>> ancestors = new ArrayList<>(ancestorMap.values());

        // Build by_parent summary (top 10 parents by match count)
        List<Map<String, Object>> byParent = parentCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .map(e -> {
                    HGNode parentNode = graphService.getRootNode().lookupNode(e.getKey());
                    Map<String, Object> pEntry = new LinkedHashMap<>();
                    pEntry.put("parent", parentNode != null ? toNodeRefShort(parentNode) : Map.of("id", e.getKey()));
                    pEntry.put("match_count", e.getValue());
                    return pEntry;
                })
                .toList();

        // Build summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_matching", allMatches.size());
        summary.put("returned", Math.min(allMatches.size(), effectiveLimit));
        summary.put("truncated", truncated);
        summary.put("by_kind", kindCounts);
        summary.put("by_parent", byParent);
        summary.put("depth_distribution", depthDistribution);

        // Build filters applied
        Map<String, Object> filtersApplied = new LinkedHashMap<>();
        if (kindFilter != null) filtersApplied.put("kind_filter", kindFilter);
        if (excludeKindFilter != null) filtersApplied.put("exclude_kind_filter", excludeKindFilter);
        filtersApplied.put("limit", effectiveLimit);

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", toNodeRefShort(rootNode));
        result.put("filters_applied", filtersApplied);
        result.put("ancestors", ancestors);
        result.put("descendants", descendants);
        result.put("summary", summary);

        return result;
    }

    private record DescendantEntry(HGNode node, int depth) {}
    private record DescendantTraversalState(HGNode node, int depth) {}

    private long countDescendants(HGNode node) {
        long count = 0;
        for (HGNode child : node.getChildren()) {
            count += 1 + countDescendants(child);
        }
        return count;
    }

    private INodeMetadataProvider getMetadataProvider() {
        return graphService.getRootNode().getExtension(INodeMetadataProvider.class);
    }

    private Map<String, Object> toNodeRefShort(HGNode node) {
        INodeMetadataProvider mp = getMetadataProvider();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", node.getIdentifier());
        entry.put("name", mp.getName(node));
        entry.put("qualified_name", mp.getQualifiedName(node));
        entry.put("kind", mp.getKind(node));
        return entry;
    }

    private Map<String, Object> toNodeRef(HGNode node) {
        INodeMetadataProvider mp = getMetadataProvider();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", node.getIdentifier());
        entry.put("name", mp.getName(node));
        entry.put("qualified_name", mp.getQualifiedName(node));
        entry.put("kind", mp.getKind(node));
        entry.put("child_count", node.getChildren().size());
        entry.put("outgoing_dep_count", node.getAccumulatedOutgoingCoreDependencies().size());
        entry.put("incoming_dep_count", node.getAccumulatedIncomingCoreDependencies().size());
        return entry;
    }
}
