package org.slizaa.mcp.core.mcp

import org.slizaa.hierarchicalgraph.core.model.HGNode
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import org.slizaa.mcp.core.HierarchicalGraphService

abstract class AbstractGraphMcpTools(
    protected val graphService: HierarchicalGraphService
) {

    protected fun getMetadataProvider(): INodeMetadataProvider =
        graphService.rootNode.getExtension(INodeMetadataProvider::class.java)

    protected fun toNodeRefShort(node: HGNode): Map<String, Any?> {
        val mp = getMetadataProvider()
        return linkedMapOf(
            "id" to node.identifier,
            "name" to mp.getName(node),
            "qualified_name" to mp.getQualifiedName(node),
            "kind" to mp.getKind(node)
        )
    }

    /**
     * Slim payload encoding (ADR-0001): registers a node's display fields into a per-response
     * `nodes` map keyed by stringified node ID. If the ID is already present, the
     * existing entry is kept -- the first registration wins, so callers should register the
     * most informative form first when ordering matters.
     */
    protected fun putSlimNode(nodes: MutableMap<String, Any>, id: Long, name: String?, fqn: String?, kind: String?) {
        val key = id.toString()
        if (nodes.containsKey(key)) return
        nodes[key] = linkedMapOf(
            "name" to (name ?: ""),
            "qualified_name" to (fqn ?: ""),
            "kind" to (kind ?: "unknown")
        )
    }

    protected fun putSlimNode(nodes: MutableMap<String, Any>, node: HGNode) {
        val mp = getMetadataProvider()
        val idObj = node.identifier
        val id = if (idObj is Number) idObj.toLong() else 0L
        putSlimNode(nodes, id, mp.getName(node), mp.getQualifiedName(node), mp.getKind(node))
    }

    protected fun toNodeRef(node: HGNode): Map<String, Any?> {
        val mp = getMetadataProvider()
        return linkedMapOf(
            "id" to node.identifier,
            "name" to mp.getName(node),
            "qualified_name" to mp.getQualifiedName(node),
            "kind" to mp.getKind(node),
            "child_count" to node.children.size,
            "outgoing_dep_count" to node.accumulatedOutgoingCoreDependencies.size,
            "incoming_dep_count" to node.accumulatedIncomingCoreDependencies.size
        )
    }

    protected fun countDescendants(node: HGNode): Long {
        var count = 0L
        for (child in node.children) {
            count += 1 + countDescendants(child)
        }
        return count
    }
}
