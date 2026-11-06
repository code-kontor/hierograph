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

import io.hierograph.graphstore.hgmodel.GraphStoreNodeSource
import io.hierograph.graphstore.model.RefKind
import io.hierograph.graphstore.model.TypeModifiers
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.NodeHandle
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.javaspec.JavaNodeKind
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantNodeMetadataProvider
import io.hierograph.mcp.server.core.HierarchicalGraphService
import io.hierograph.mcp.server.core.INodeRefFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Parquet-backed `type_details` — the Neo4j-free counterpart of the Cypher path in [TypeDetailsTool],
 * selected when `hierograph.graph.source=parquet`, with the same response shape. Identity, parent and
 * inner types come from the in-memory model; modifiers and source file from `types.parquet`; superclass,
 * interfaces and annotations from the type's `refs.parquet` rows (so targets the placeholder policy keeps
 * out of the model, like `java.lang.Object`, still appear — as id-less external refs); member counts from
 * `members.parquet` (which, unlike the model, distinguishes constructors).
 *
 * Not in the snapshot, hence reported as unknown (`null`): `is_generic`, and the modifiers of a snapshot
 * written before `types.parquet` carried them. The source line of a type is not exported either.
 */
@Component
class ParquetTypeDetails(
    graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory,
) : AbstractDetailTool(graphService) {

    fun typeDetails(typeId: Long): Map<String, Any?> {
        val hierarchy = graphService.model.hierarchy
        val node = graphService.model.lookupNode(typeId) ?: return notFound(typeId)
        if (node.kind !in JavaKinds.TYPE_KINDS) return wrongKind(typeId, node)

        val source = node.nodeSource as? GraphStoreNodeSource ?: return notFound(typeId)
        val fqn = source.qualifiedName
        val parent = hierarchy.parentOf(node)
        val artifact = owningArtifact(node)
        val dir = Path.of(graphService.parquetDirectory)
        val reader = ParquetReader()

        val typeRow = reader.readTypeRows(dir, listOf(fqn)).firstOrNull { it.artifact == artifact }
        val refs = reader.readRefsFrom(dir, listOf(fqn))[fqn].orEmpty()
        val members = reader.readMembersByOwner(dir, listOf(fqn))[fqn].orEmpty()

        val typeRef = linkedMapOf<String, Any?>(
            "id" to typeId,
            "name" to JQAssistantNodeMetadataProvider.getName(node),
            "qualified_name" to fqn,
            "kind" to node.kind?.toString(),
            "parent_id" to parent?.identifier,
            "parent_kind" to parent?.kind?.toString(),
        )
        val parentContainer = parent?.takeIf { it !== hierarchy.rootNode }?.let { nodeRefFactory.minimalNodeRef(it) }

        val bits = typeRow?.modifiers
        val superclass = refs.firstOrNull { it.kind == RefKind.EXTENDS }?.let { typeRef(it.toKey) }
        val interfaces = refs.filter { it.kind == RefKind.IMPLEMENTS }.map { it.toKey }.distinct().map { typeRef(it) }
        val annotations = refs.filter { it.kind == RefKind.ANNOTATED_BY }.map { it.toKey }.distinct()
            .map { linkedMapOf<String, Any?>("type" to typeRef(it)) }

        val memberSummary = linkedMapOf<String, Any?>(
            "method_count" to members.count { it.kind == JavaNodeKind.METHOD },
            "field_count" to members.count { it.kind == JavaNodeKind.FIELD },
            "constructor_count" to members.count { it.kind == JavaNodeKind.CONSTRUCTOR },
        )
        val innerTypes = hierarchy.childrenOf(node).filter { it.kind in JavaKinds.TYPE_KINDS }.map { nodeRefFactory.minimalNodeRef(it) }
        val location = typeRow?.sourceFile?.let { linkedMapOf<String, Any?>("source_file" to it) }

        return linkedMapOf(
            "type" to typeRef,
            "parent_container" to parentContainer,
            "modifiers" to bits?.let { TypeModifiers.modifiers(it) },
            "is_abstract" to bits?.let { TypeModifiers.isAbstract(it) },
            "is_generic" to null,
            "superclass" to superclass,
            "interfaces" to interfaces,
            "annotations" to annotations,
            "member_summary" to memberSummary,
            "inner_types" to innerTypes,
            "location" to location,
        )
    }

    /** The key of [node]'s owning module (the artifact its `types.parquet` row is filed under). */
    private fun owningArtifact(node: HGNode): String? {
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
                "name" to JQAssistantNodeMetadataProvider.getName(node),
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
        return mapOf(
            "error" to mapOf(
                "code" to "WRONG_NODE_KIND",
                "message" to "Node $typeId is a '$kind', not a type. " +
                        "type_details requires a type-kind node (java.class, java.interface, java.enum, java.record, java.annotation).",
                "actual_kind" to kind,
                "declaring_type" to declaringType?.takeIf { it !== hierarchy.rootNode }?.let { nodeRefFactory.minimalNodeRef(it) },
                "recovery" to when (node.kind) {
                    JavaKinds.METHOD -> "To inspect the method itself, use method_details(method_id: $typeId)."
                    JavaKinds.FIELD -> "To inspect the field itself, use field_details(field_id: $typeId)."
                    else -> "Use list_children or list_descendants to find type nodes."
                },
            )
        )
    }
}
