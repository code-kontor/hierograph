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
package io.hierograph.mcp.server.tools.detail

import io.hierograph.server.mapping.GraphStoreNodeSource
import io.hierograph.graphstore.jvm.model.RefKind
import io.hierograph.graphstore.jvm.model.TypeModifiers
import io.hierograph.graphstore.jvm.parquet.ParquetReader
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.mcp.server.core.INodeRefFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * The engine behind `type_details`, served from the Parquet snapshot. Identity, parent and inner types
 * come from the in-memory model; modifiers and source file from `types.parquet`; superclass, interfaces
 * and annotations from the type's `refs.parquet` rows (so targets the placeholder policy keeps out of the
 * model, like `java.lang.Object`, still appear — as id-less external refs); member counts from
 * `members.parquet` (which, unlike the model, distinguishes constructors).
 *
 * Not in the snapshot, hence reported as unknown (`null`): the modifiers and `is_generic` of a type whose
 * `types.parquet` row is missing or carries none. The source line of a type is not exported either.
 *
 * Nesting is derived from binary names, because a nested type sits beside its enclosing type in the
 * package (see [NestedTypes]). An external type is described by what the model knows of it: its identity
 * and package; everything a scan would provide is `null` or empty.
 */
@Component
class ParquetTypeDetails(
    graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory,
) : AbstractDetailTool(graphService) {

    fun typeDetails(typeId: Long): Map<String, Any?> {
        val hierarchy = graphService.model.hierarchy
        val node = graphService.model.lookupNode(typeId) ?: return notFound(typeId)
        if (node.kind == JavaKinds.EXTERNAL_TYPE) return externalTypeDetails(typeId, node)
        if (node.kind !in JavaKinds.TYPE_KINDS) return wrongKind(typeId, node)

        val source = node.nodeSource as? GraphStoreNodeSource ?: return notFound(typeId)
        val fqn = source.qualifiedName
        val parent = hierarchy.parentOf(node)
        val artifact = owningArtifact(node)
        val dir = readyDetailDir() ?: return detailRebuilding()
        val reader = ParquetReader()

        // Row, refs and members all from the artifact that defines this node, never from a same-FQN twin.
        val facts = artifact?.let { reader.readTypeFacts(dir, it, fqn) }
        val typeRow = facts?.row
        val refs = facts?.refs.orEmpty()
        val members = facts?.members.orEmpty()

        val typeRef = linkedMapOf<String, Any?>(
            "id" to typeId,
            "name" to NodeMetadata.getName(node),
            "qualified_name" to fqn,
            "kind" to node.kind?.toString(),
            "parent_id" to parent?.identifier,
            "parent_kind" to parent?.kind?.toString(),
        )
        val container = NestedTypes.enclosingType(hierarchy, node) ?: parent?.takeIf { it !== hierarchy.rootNode }
        val parentContainer = container?.let { nodeRefFactory.minimalNodeRef(it) }

        val bits = typeRow?.modifiers
        val superclass = refs.firstOrNull { it.kind == RefKind.EXTENDS }?.let { typeRef(it.toKey) }
        val interfaces = refs.filter { it.kind == RefKind.IMPLEMENTS }.map { it.toKey }.distinct().map { typeRef(it) }
        val annotations = refs.filter { it.kind == RefKind.ANNOTATED_BY }.map { it.toKey }.distinct()
            .map { linkedMapOf<String, Any?>("type" to typeRef(it)) }

        val memberSummary = linkedMapOf<String, Any?>(
            // Methods include constructors, as in the containment tree; constructor_count is that subset.
            "method_count" to members.count { it.kind == JavaNodeKind.METHOD || it.kind == JavaNodeKind.CONSTRUCTOR },
            "field_count" to members.count { it.kind == JavaNodeKind.FIELD },
            "constructor_count" to members.count { it.kind == JavaNodeKind.CONSTRUCTOR },
        )
        val innerTypes = NestedTypes.memberTypes(hierarchy, node).map { nodeRefFactory.minimalNodeRef(it) }
        val location = typeRow?.sourceFile?.let { linkedMapOf<String, Any?>("source_file" to it) }

        return linkedMapOf(
            "type" to typeRef,
            "parent_container" to parentContainer,
            "modifiers" to bits?.let { TypeModifiers.modifiers(it) },
            "is_abstract" to bits?.let { TypeModifiers.isAbstract(it) },
            "is_generic" to bits?.let { TypeModifiers.isGeneric(it) },
            "superclass" to superclass,
            "interfaces" to interfaces,
            "annotations" to annotations,
            "member_summary" to memberSummary,
            "inner_types" to innerTypes,
            "location" to location,
        )
    }

    /**
     * The description of an external type: identity, package and kind from the model. It was not
     * scanned, so its modifiers, supertypes, annotations, members and source file are unknown.
     */
    private fun externalTypeDetails(typeId: Long, node: HGNode): Map<String, Any?> {
        val hierarchy = graphService.model.hierarchy
        val parent = hierarchy.parentOf(node)
        return linkedMapOf(
            "type" to linkedMapOf<String, Any?>(
                "id" to typeId,
                "name" to NodeMetadata.getName(node),
                "qualified_name" to NodeMetadata.getQualifiedName(node),
                "kind" to JavaKinds.EXTERNAL_TYPE,
                "parent_id" to parent?.identifier,
                "parent_kind" to parent?.kind?.toString(),
            ),
            "parent_container" to parent?.takeIf { it !== hierarchy.rootNode }?.let { nodeRefFactory.minimalNodeRef(it) },
            "modifiers" to null,
            "is_abstract" to null,
            "is_generic" to null,
            "superclass" to null,
            "interfaces" to emptyList<Any>(),
            "annotations" to emptyList<Any>(),
            "member_summary" to null,
            "inner_types" to emptyList<Any>(),
            "location" to null,
        )
    }

    /**
     * The key of [node]'s owning module (the artifact its `types.parquet` row is filed under). Taken from the
     * GraphStore's own containment, so a type a restructuring view moves into another or a virtual module
     * still reads its facts from the artifact that defines it; the hierarchy is the fallback.
     */
    private fun owningArtifact(node: HGNode): String? {
        val store = graphService.graphStore
        var key = (node.nodeSource as? GraphStoreNodeSource)?.key
        if (store != null) {
            while (key != null) {
                val stored = store.node(key) ?: break
                if (stored.kind == JavaKinds.MODULE) return stored.key
                key = stored.parent
            }
        }
        var n: HGNode? = node
        while (n != null && n.kind != JavaKinds.MODULE) n = graphService.model.hierarchy.parentOf(n)
        return (n?.nodeSource as? GraphStoreNodeSource)?.key
    }

    /** An inline type NodeRef for [fqn]: a model-resolved type, or an id-less external fallback. */
    private fun typeRef(fqn: String): Map<String, Any?> {
        val handle = graphService.graphStore?.typeByFqn(fqn)?.key?.let { NodeHandle.of(it) }
        val node = handle?.let { graphService.model.lookupNode(it) }
        return if (node != null) {
            linkedMapOf(
                "id" to handle,
                "name" to NodeMetadata.getName(node),
                "qualified_name" to fqn,
                "kind" to node.kind?.toString(),
            )
        } else {
            linkedMapOf(
                "id" to null,
                "name" to fqn.substringAfterLast('.'),
                "qualified_name" to fqn,
                "kind" to JavaKinds.EXTERNAL_TYPE,
            )
        }
    }

    private fun notFound(typeId: Long): Map<String, Any?> = mapOf(
        "error" to mapOf(
            "code" to "NODE_NOT_FOUND",
            "message" to "No node with id $typeId exists in the graph.",
            "recovery" to "Use find_node to look up the correct node ID.",
        )
    )

    private fun wrongKind(typeId: Long, node: HGNode): Map<String, Any?> {
        val hierarchy = graphService.model.hierarchy
        val declaringType = hierarchy.parentOf(node)
        val kind = node.kind?.toString()
        val error = linkedMapOf<String, Any?>(
            "code" to "WRONG_NODE_KIND",
            "message" to "Node $typeId is a '$kind', not a type. " +
                    "type_details requires a type-kind node (java.class, java.interface, java.enum, java.record, java.annotation, external.type).",
            "actual_kind" to kind,
        )
        // Only a member has a declaring type; for a package or module the key is left out.
        if (node.kind in JavaKinds.MEMBER_KINDS && declaringType != null) {
            error["declaring_type"] = nodeRefFactory.minimalNodeRef(declaringType)
        }
        error["recovery"] = when (node.kind) {
            JavaKinds.METHOD -> "To inspect the method itself, use method_details(methodId: $typeId)."
            JavaKinds.FIELD -> "To inspect the field itself, use field_details(fieldId: $typeId)."
            else -> "Use list_children or list_descendants to find type nodes."
        }
        return mapOf("error" to error)
    }
}
