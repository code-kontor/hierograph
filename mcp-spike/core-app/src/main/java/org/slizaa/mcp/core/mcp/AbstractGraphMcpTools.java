package org.slizaa.mcp.core.mcp;

import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider;
import org.slizaa.mcp.core.HierarchicalGraphService;

import java.util.LinkedHashMap;
import java.util.Map;

public abstract class AbstractGraphMcpTools {

    protected final HierarchicalGraphService graphService;

    protected AbstractGraphMcpTools(HierarchicalGraphService graphService) {
        this.graphService = graphService;
    }

    protected INodeMetadataProvider getMetadataProvider() {
        return graphService.getRootNode().getExtension(INodeMetadataProvider.class);
    }

    protected Map<String, Object> toNodeRefShort(HGNode node) {
        INodeMetadataProvider mp = getMetadataProvider();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", node.getIdentifier());
        entry.put("name", mp.getName(node));
        entry.put("qualified_name", mp.getQualifiedName(node));
        entry.put("kind", mp.getKind(node));
        return entry;
    }

    /**
     * Slim payload encoding (ADR-0001): registers a node's display fields into a per-response
     * <code>nodes</code> map keyed by stringified node ID. If the ID is already present, the
     * existing entry is kept — the first registration wins, so callers should register the
     * most informative form first when ordering matters.
     */
    protected void putSlimNode(Map<String, Object> nodes, long id, String name, String fqn, String kind) {
        String key = String.valueOf(id);
        if (nodes.containsKey(key)) return;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name != null ? name : "");
        info.put("qualified_name", fqn != null ? fqn : "");
        info.put("kind", kind != null ? kind : "unknown");
        nodes.put(key, info);
    }

    protected void putSlimNode(Map<String, Object> nodes, HGNode node) {
        INodeMetadataProvider mp = getMetadataProvider();
        Object idObj = node.getIdentifier();
        long id = idObj instanceof Number ? ((Number) idObj).longValue() : 0L;
        putSlimNode(nodes, id, mp.getName(node), mp.getQualifiedName(node), mp.getKind(node));
    }

    protected Map<String, Object> toNodeRef(HGNode node) {
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

    protected long countDescendants(HGNode node) {
        long count = 0;
        for (HGNode child : node.getChildren()) {
            count += 1 + countDescendants(child);
        }
        return count;
    }
}
