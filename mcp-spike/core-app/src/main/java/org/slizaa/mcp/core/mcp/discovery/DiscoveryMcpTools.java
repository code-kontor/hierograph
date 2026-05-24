package org.slizaa.mcp.core.mcp.discovery;

import org.neo4j.driver.Record;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider;
import org.slizaa.mcp.core.mcp.AbstractGraphMcpTools;
import org.slizaa.mcp.core.HierarchicalGraphService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class DiscoveryMcpTools extends AbstractGraphMcpTools {

    public DiscoveryMcpTools(HierarchicalGraphService graphService) {
        super(graphService);
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

        // TODO All these information shouldn't be provided by the INodeMetadataProvider!!
        // Do we need a separate interface for metadata related stuff?

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

            String nodeKind = mp.getKind(node);
            boolean matches = true;

            // Kind filter
            if (includeKinds != null && !includeKinds.contains(nodeKind)) {
                matches = false;
            }
            if (excludeKinds != null && excludeKinds.contains(nodeKind)) {
                matches = false;
            }

            // Count for summary (all matches, not just returned)
            if (matches) {
                kindCounts.merge(nodeKind, 1, Integer::sum);
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
}
