package org.slizaa.mcp.core;

import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider;

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
