/*
 * Copyright 2026 Gerd Wuetherich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hierograph.mcp.server.tools

import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantNodeMetadataProvider
import io.hierograph.mcp.server.core.HierarchicalGraphService
import io.hierograph.mcp.server.core.INodeRefFactory
import org.springframework.stereotype.Component

/**
 * Centralises all node-to-map conversion logic used across MCP tool implementations.
 *
 * Provides three flavours:
 * - [minimalNodeRef]  – identity fields only (id, name, qualified_name, kind, parent_id, parent_kind)
 * - [enrichedNodeRef] – identity plus kind-appropriate metadata (counts, modifiers, flags)
 * - [putSlimNode]     – registers a node's display fields into a shared `nodes` map for slim payload encoding
 */
@Component
class NodeRefFactory(private val graphService: HierarchicalGraphService) : INodeRefFactory {

    // ── minimal NodeRef ────────────────────────────────────────────────

    /**
     * Identity-only NodeRef: `id`, `name`, `qualified_name`, `kind`, `parent_id`, `parent_kind`.
     * Used when a NodeRef appears inside a larger structure (edge endpoint, path step, etc.).
     */
    override fun minimalNodeRef(node: HGNode): LinkedHashMap<String, Any?> {
        val hierarchy = graphService.model.hierarchy
        val parent = hierarchy.parentOf(node)
        return linkedMapOf(
            "id" to node.identifier,
            "name" to JQAssistantNodeMetadataProvider.getName(node),
            "qualified_name" to JQAssistantNodeMetadataProvider.getQualifiedName(node),
            "kind" to node.kind?.toString(),
            "parent_id" to parent?.identifier,
            "parent_kind" to parent?.kind?.toString()
        )
    }

    // ── enriched NodeRef ───────────────────────────────────────────────

    /**
     * Identity plus kind-appropriate metadata. Used when the NodeRef *is* the result
     * of a browse-style operation (find_node, list_children, list_descendants, affected_by).
     */
    override fun enrichedNodeRef(node: HGNode): LinkedHashMap<String, Any?> {
        val ref = minimalNodeRef(node)
        val kind = node.kind

        when (kind) {
            JavaKinds.MODULE -> enrichModule(ref, node)
            JavaKinds.PACKAGE -> enrichPackage(ref, node)
            in JavaKinds.TYPE_KINDS -> enrichType(ref, node)
            JavaKinds.METHOD -> enrichMethod(ref, node)
            JavaKinds.FIELD -> enrichField(ref, node)
        }

        return ref
    }

    private fun enrichModule(ref: LinkedHashMap<String, Any?>, node: HGNode) {
        val hierarchy = graphService.model.hierarchy
        val children = hierarchy.childrenOf(node)
        ref["child_count"] = children.size
        ref["descendant_type_count"] = countDescendantsByKind(node, JavaKinds.TYPE_KINDS)
        ref["descendant_method_count"] = countDescendantsByKind(node, setOf(JavaKinds.METHOD))
    }

    private fun enrichPackage(ref: LinkedHashMap<String, Any?>, node: HGNode) {
        val hierarchy = graphService.model.hierarchy
        val children = hierarchy.childrenOf(node)
        ref["child_count"] = children.size
        ref["descendant_type_count"] = countDescendantsByKind(node, JavaKinds.TYPE_KINDS)
        ref["direct_type_count"] = children.count { it.kind in JavaKinds.TYPE_KINDS }
    }

    private fun enrichType(ref: LinkedHashMap<String, Any?>, node: HGNode) {
        val hierarchy = graphService.model.hierarchy
        val children = hierarchy.childrenOf(node)
        // TODO: modifiers, annotation_count, interface_count, is_abstract, is_generic, parent_type
        //       require property materialisation — will be filled in when the enriched metadata is available
        val methods = children.count { it.kind == JavaKinds.METHOD }
        val fields = children.count { it.kind == JavaKinds.FIELD }
        ref["member_count"] = methods + fields
        ref["method_count"] = methods
        ref["field_count"] = fields
    }

    @Suppress("UNUSED_PARAMETER")
    private fun enrichMethod(ref: LinkedHashMap<String, Any?>, node: HGNode) {
        // TODO: modifiers, parameter_count, throws_count, annotation_count, is_constructor
        //       require property materialisation
    }

    @Suppress("UNUSED_PARAMETER")
    private fun enrichField(ref: LinkedHashMap<String, Any?>, node: HGNode) {
        // TODO: modifiers, field_type_name, annotation_count, is_constant
        //       require property materialisation
    }

    // ── slim payload encoding ───────────────────────────────

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
     * Convenience overload that extracts display fields from a [HGNode].
     */
    override fun putSlimNode(nodes: MutableMap<String, Any>, node: HGNode) {
        val id = (node.identifier as? Number)?.toLong() ?: 0L
        putSlimNode(nodes, id, JQAssistantNodeMetadataProvider.getName(node), JQAssistantNodeMetadataProvider.getQualifiedName(node), JQAssistantNodeMetadataProvider.getKind(node))
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
        "kind" to JavaKinds.PRIMITIVE.value
    )

    // ── utility ────────────────────────────────────────────────────────

    /**
     * Counts all descendants of [node] whose kind is in [kinds].
     */
    override fun countDescendantsByKind(node: HGNode, kinds: Set<*>): Int {
        val hierarchy = graphService.model.hierarchy
        var count = 0
        fun walk(n: HGNode) {
            for (child in hierarchy.childrenOf(n)) {
                if (child.kind in kinds) count++
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
        val hierarchy = graphService.model.hierarchy
        var count = 0L
        for (child in hierarchy.childrenOf(node)) {
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
