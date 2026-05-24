package org.slizaa.mcp.core.mcp

import org.slizaa.hierarchicalgraph.core.model.HGNode
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import org.slizaa.mcp.javaspec.JavaKinds
import org.slizaa.mcp.core.HierarchicalGraphService
import org.springframework.stereotype.Component

/**
 * Centralises all node-to-map conversion logic used across MCP tool implementations.
 *
 * Provides three flavours:
 * - [minimalNodeRef]  – identity fields only (id, name, qualified_name, kind, parent_id, parent_kind)
 * - [enrichedNodeRef] – identity plus kind-appropriate metadata (counts, modifiers, flags)
 * - [putSlimNode]     – registers a node's display fields into a shared `nodes` map for slim payload encoding (ADR-0001)
 */
@Component
class NodeRefFactory(private val graphService: HierarchicalGraphService) : INodeRefFactory {

    // ── metadata provider ──────────────────────────────────────────────

    private val metadataProvider: INodeMetadataProvider
        get() = graphService.rootNode.getExtension(INodeMetadataProvider::class.java)

    // ── minimal NodeRef ────────────────────────────────────────────────

    /**
     * Identity-only NodeRef: `id`, `name`, `qualified_name`, `kind`, `parent_id`, `parent_kind`.
     * Used when a NodeRef appears inside a larger structure (edge endpoint, path step, etc.).
     */
    override fun minimalNodeRef(node: HGNode): LinkedHashMap<String, Any?> {
        val mp = metadataProvider
        return linkedMapOf(
            "id" to node.identifier,
            "name" to mp.getName(node),
            "qualified_name" to mp.getQualifiedName(node),
            "kind" to mp.getKind(node),
            "parent_id" to node.parent?.identifier,
            "parent_kind" to node.parent?.let { mp.getKind(it) }
        )
    }

    // ── enriched NodeRef ───────────────────────────────────────────────

    /**
     * Identity plus kind-appropriate metadata. Used when the NodeRef *is* the result
     * of a browse-style operation (find_node, list_children, list_descendants, affected_by).
     */
    override fun enrichedNodeRef(node: HGNode): LinkedHashMap<String, Any?> {
        val ref = minimalNodeRef(node)
        val mp = metadataProvider
        val kind = mp.getKind(node)

        when {
            kind == JavaKinds.MODULE -> enrichModule(ref, node)
            kind == JavaKinds.PACKAGE -> enrichPackage(ref, node)
            kind in JavaKinds.TYPE_KINDS -> enrichType(ref, node, mp)
            kind == JavaKinds.METHOD -> enrichMethod(ref, node, mp)
            kind == JavaKinds.FIELD -> enrichField(ref, node, mp)
        }

        return ref
    }

    private fun enrichModule(ref: LinkedHashMap<String, Any?>, node: HGNode) {
        ref["child_count"] = node.children.size
        ref["descendant_type_count"] = countDescendantsByKind(node, JavaKinds.TYPE_KINDS)
        ref["descendant_method_count"] = countDescendantsByKind(node, setOf(JavaKinds.METHOD))
    }

    private fun enrichPackage(ref: LinkedHashMap<String, Any?>, node: HGNode) {
        ref["child_count"] = node.children.size
        ref["descendant_type_count"] = countDescendantsByKind(node, JavaKinds.TYPE_KINDS)
        ref["direct_type_count"] = node.children.count { metadataProvider.getKind(it) in JavaKinds.TYPE_KINDS }
    }

    private fun enrichType(ref: LinkedHashMap<String, Any?>, node: HGNode, mp: INodeMetadataProvider) {
        // TODO: modifiers, annotation_count, interface_count, is_abstract, is_generic, parent_type
        //       require property materialisation — will be filled in when the enriched metadata is available
        val methods = node.children.count { mp.getKind(it) == JavaKinds.METHOD }
        val fields = node.children.count { mp.getKind(it) == JavaKinds.FIELD }
        ref["member_count"] = methods + fields
        ref["method_count"] = methods
        ref["field_count"] = fields
    }

    @Suppress("UNUSED_PARAMETER")
    private fun enrichMethod(ref: LinkedHashMap<String, Any?>, node: HGNode, mp: INodeMetadataProvider) {
        // TODO: modifiers, parameter_count, throws_count, annotation_count, is_constructor
        //       require property materialisation
    }

    @Suppress("UNUSED_PARAMETER")
    private fun enrichField(ref: LinkedHashMap<String, Any?>, node: HGNode, mp: INodeMetadataProvider) {
        // TODO: modifiers, field_type_name, annotation_count, is_constant
        //       require property materialisation
    }

    // ── slim payload encoding (ADR-0001) ───────────────────────────────

    /**
     * Registers a node's display fields into a per-response `nodes` map keyed by stringified ID.
     * First registration wins — callers should register the most informative form first.
     */
    override fun putSlimNode(nodes: MutableMap<String, Any>, id: Long, name: String?, fqn: String?, kind: String?) {
        val key = id.toString()
        if (key in nodes) return
        nodes[key] = linkedMapOf(
            "name" to (name ?: ""),
            "qualified_name" to (fqn ?: ""),
            "kind" to (kind ?: "unknown")
        )
    }

    /**
     * Convenience overload that extracts display fields from an [HGNode].
     */
    override fun putSlimNode(nodes: MutableMap<String, Any>, node: HGNode) {
        val mp = metadataProvider
        val id = (node.identifier as? Number)?.toLong() ?: 0L
        putSlimNode(nodes, id, mp.getName(node), mp.getQualifiedName(node), mp.getKind(node))
    }

    // ── primitive ref ──────────────────────────────────────────────────

    /**
     * Returns a NodeRef for a Java primitive type (void, int, boolean, etc.).
     * These have `id: null` and `kind: "java.primitive"` — they are not graph nodes.
     */
    override fun primitiveRef(name: String): LinkedHashMap<String, Any?> = linkedMapOf(
        "id" to null,
        "name" to name,
        "qualified_name" to name,
        "kind" to JavaKinds.PRIMITIVE
    )

    // ── utility ────────────────────────────────────────────────────────

    /**
     * Counts all descendants of [node] whose kind is in [kinds].
     */
    override fun countDescendantsByKind(node: HGNode, kinds: Set<String>): Int {
        val mp = metadataProvider
        var count = 0
        fun walk(n: HGNode) {
            for (child in n.children) {
                if (mp.getKind(child) in kinds) count++
                walk(child)
            }
        }
        walk(node)
        return count
    }

    /**
     * Counts all descendants of [node] (regardless of kind).
     */
    override fun countDescendants(node: HGNode): Long {
        var count = 0L
        for (child in node.children) {
            count += 1 + countDescendants(child)
        }
        return count
    }

    companion object {
        private fun linkedMapOf(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            for ((k, v) in pairs) map[k] = v
            return map
        }
    }
}
