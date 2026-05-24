package io.hierograph.mcp.jqa.hierarchicalgraph

import org.slizaa.hierarchicalgraph.core.model.HGNode
import org.slizaa.hierarchicalgraph.core.model.spi.INodeComparator
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbNodeSource

class JQAssistantNodeComparator : INodeComparator {

    override fun category(element: Any?): Int {
        if (!hasGraphDbNodeSource(element)) return 0
        return when {
            hasLabel(element, "Package") -> 10
            hasLabel(element, "Type") -> 20
            else -> 1
        }
    }

    override fun compare(node1: Any?, node2: Any?): Int {
        if (!(hasGraphDbNodeSource(node1) && hasGraphDbNodeSource(node2))) return 0

        if (hasLabel(node1, node2, "Package") || hasLabel(node1, node2, "Type") || hasLabel(node1, node2, "Artifact")) {
            return compareProperties(node1!!, node2!!, "name")
        }
        return -1
    }

    private fun hasLabel(node: Any?, label: String): Boolean {
        val src = (node as? HGNode)?.nodeSource as? GraphDbNodeSource ?: return false
        return label in src.labels
    }

    private fun hasLabel(node1: Any?, node2: Any?, label: String): Boolean =
        hasLabel(node1, label) && hasLabel(node2, label)

    private fun compareProperties(node1: Any, node2: Any, property: String): Int {
        val source1 = ((node1 as HGNode).nodeSource as GraphDbNodeSource)
        val source2 = ((node2 as HGNode).nodeSource as GraphDbNodeSource)
        val v1 = source1.properties[property] ?: return 0
        val v2 = source2.properties[property] ?: return 0
        return v1.compareTo(v2)
    }

    private fun hasGraphDbNodeSource(obj: Any?): Boolean =
        obj is HGNode && obj.nodeSource is GraphDbNodeSource
}
