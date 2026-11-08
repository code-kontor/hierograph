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

import io.hierograph.graphstore.model.MemberModifiers
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.graphstore.model.JavaKinds
import io.hierograph.graphstore.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.server.graphservice.HierarchicalGraphService
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Parquet-backed `method_details` — the Neo4j-free counterpart of the Cypher path in [MethodDetailsTool],
 * selected when `hierograph.graph.source=parquet`. Identity (name/kind/modifiers/location) and parameters
 * come from `members.parquet` / `parameters.parquet`; return type, throws, annotations and the overridden
 * method come from `detail_refs.parquet`. The method id is a content-addressed handle, resolved back to
 * its member key via the service's handle index (members are not nodes in the bridged model).
 */
@Component
class ParquetMethodDetails(graphService: HierarchicalGraphService) : AbstractDetailTool(graphService) {

    fun methodDetails(methodId: Long): Map<String, Any?> {
        val memberKey = graphService.memberHandleIndex[methodId]
            ?: return notFound(methodId)
        val dir = readyDetailDir() ?: return detailRebuilding()
        val reader = ParquetReader()
        val member = reader.readMember(dir, memberKey) ?: return notFound(methodId)
        if (member.kind != JavaNodeKind.METHOD && member.kind != JavaNodeKind.CONSTRUCTOR) {
            return linkedMapOf(
                "error" to "WRONG_NODE_KIND", "code" to "WRONG_NODE_KIND",
                "message" to "Node $methodId is a '${member.kind.value}', not a method. method_details requires a method-kind node.",
                "actual_kind" to member.kind.value,
            )
        }

        val isConstructor = member.kind == JavaNodeKind.CONSTRUCTOR
        val ownerFqn = memberKey.substringBefore('#')
        val declaringTypeRef = typeRef(ownerFqn)

        val methodRef = linkedMapOf<String, Any?>(
            "id" to methodId,
            "name" to member.name,
            "qualified_name" to memberKey,
            "kind" to (if (isConstructor) JavaNodeKind.CONSTRUCTOR else JavaNodeKind.METHOD).value,
            "parent_id" to declaringTypeRef["id"],
            "parent_kind" to declaringTypeRef["kind"],
        )

        // Parquet direct edges for this owner + the load-derived ones (overrides/calls), then this member's.
        val edges = (reader.readDetailRefs(dir, fromTypes = listOf(ownerFqn)) + graphService.derivedDetailRefs(fromTypes = listOf(ownerFqn)))
            .filter { it.fromKey == memberKey }

        val returnType: Map<String, Any?> = when {
            isConstructor -> declaringTypeRef
            else -> edges.firstOrNull { it.relationship == "returns" }?.let { typeRef(it.toType) } ?: primitiveRef("void")
        }
        val throwsList = edges.filter { it.relationship == "throws" }.map { it.toType }.distinct().map { typeRef(it) }
        // `throws` is the declared clause; `raises` is the body-thrown `throw new X(...)` (see ClassScanner).
        val raisesList = edges.filter { it.relationship == "raises" }.map { it.toType }.distinct().map { typeRef(it) }
        val annotations = edges.filter { it.relationship == "annotated_by" }.map { it.toType }.distinct()
            .map { linkedMapOf<String, Any?>("type" to typeRef(it)) }
        val overrides = edges.firstOrNull { it.relationship == "overrides" }?.let { methodRefFor(reader, dir, it.toKey, it.toType) }

        val parameters = member.parameters.map { p ->
            linkedMapOf<String, Any?>(
                "position" to p.index.toLong(),
                "name" to p.name,
                "type" to typeRef(p.typeFqn),
                "annotations" to emptyList<Any>(), // parameter_annotated_by is not persisted in the MVP snapshot
            )
        }
        val location = member.lineFrom?.takeIf { it > 0 }?.let { linkedMapOf<String, Any>("line_number" to it.toLong()) }

        return linkedMapOf(
            "method" to methodRef,
            "declaring_type" to declaringTypeRef,
            "modifiers" to MemberModifiers.methodModifiers(member.modifiers),
            "is_constructor" to isConstructor,
            "return_type" to returnType,
            "parameters" to parameters,
            "throws" to throwsList,
            "raises" to raisesList,
            "annotations" to annotations,
            "overrides" to overrides,
            "location" to location,
        )
    }

    /** A method NodeRef for an override target, reading the target member's display when it was scanned. */
    private fun methodRefFor(reader: ParquetReader, dir: Path, memberKey: String, declaringFqn: String): Map<String, Any?> {
        val target = reader.readMember(dir, memberKey)
        val isCtor = target?.kind == JavaNodeKind.CONSTRUCTOR || memberKey.substringAfterLast('#').startsWith("<init>")
        val parent = typeRef(declaringFqn)
        return linkedMapOf(
            "id" to graphService.memberHandle(memberKey),
            "name" to (target?.name ?: memberKey.substringAfterLast('#').substringBefore('(')),
            "qualified_name" to memberKey,
            "kind" to (if (isCtor) JavaNodeKind.CONSTRUCTOR else JavaNodeKind.METHOD).value,
            "parent_id" to parent["id"],
            "parent_kind" to parent["kind"],
        )
    }

    /** An inline type NodeRef for [fqn]: primitive, a model-resolved type, or an external fallback. */
    private fun typeRef(fqn: String): Map<String, Any?> {
        if (fqn in JAVA_PRIMITIVES) return primitiveRef(fqn)
        val handle = graphService.graphStore?.typeByFqn(fqn)?.key?.let { NodeHandle.of(it) }
        val node = handle?.let { graphService.model.lookupNode(it) }
        return if (node != null) {
            linkedMapOf(
                "id" to handle,
                "name" to NodeMetadata.getName(node),
                "qualified_name" to NodeMetadata.getQualifiedName(node),
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

    private fun notFound(methodId: Long): Map<String, Any?> = linkedMapOf(
        "error" to "NODE_NOT_FOUND", "code" to "NODE_NOT_FOUND",
        "message" to "Node not found: $methodId. Re-resolve via detail_dependencies.",
    )
}
