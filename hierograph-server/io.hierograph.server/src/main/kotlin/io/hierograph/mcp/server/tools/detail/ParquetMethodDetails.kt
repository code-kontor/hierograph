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

import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.model.MemberModifiers
import io.hierograph.graphstore.jvm.archive.ParquetReader
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.server.graphservice.HierarchicalGraphService
import org.springframework.stereotype.Component

/**
 * The engine behind `method_details`, served from the Parquet snapshot. Identity (name/kind/modifiers/
 * location) and parameters come from `members.parquet` / `parameters.parquet`; return type, throws,
 * raises and annotations from `dependencies.parquet`; the overridden method from the load-derived
 * `overrides` edges. The method id is a content-addressed member handle, resolved back to its member key
 * via the service's member handle index.
 */
@Component
class ParquetMethodDetails(graphService: HierarchicalGraphService) : AbstractDetailTool(graphService) {

    fun methodDetails(methodId: Long): Map<String, Any?> {
        val memberKey = graphService.memberHandleIndex[methodId]
            ?: return notAMember(methodId, "method", "method_details") ?: notFound(methodId)
        val dir = readyDetailDir() ?: return detailRebuilding()
        val reader = ParquetReader()
        val member = reader.readMember(dir, memberKey) ?: return notFound(methodId)
        if (member.kind != JavaNodeKind.METHOD && member.kind != JavaNodeKind.CONSTRUCTOR) {
            return errorResponse(
                "WRONG_NODE_KIND",
                "Node $methodId is a '${member.kind.value}', not a method. method_details requires a method-kind node.",
                "To inspect the field itself, use field_details(fieldId: $methodId).",
                mapOf("actual_kind" to member.kind.value),
            )
        }

        val isConstructor = member.kind == JavaNodeKind.CONSTRUCTOR
        val ownerFqn = memberKey.substringBefore('#')
        val declaringTypeRef = typeRef(ownerFqn)

        // Named and kinded as in the containment tree: by signature, a constructor as a java.method.
        val methodRef = linkedMapOf<String, Any?>(
            "id" to methodId,
            "name" to member.signature,
            "qualified_name" to memberKey,
            "kind" to JavaNodeKind.METHOD.value,
            "parent_id" to declaringTypeRef["id"],
            "parent_kind" to declaringTypeRef["kind"],
        )

        // Parquet direct edges for this owner + the load-derived ones (overrides/calls), then this member's.
        val edges = (reader.readDependencies(dir, fromTypes = listOf(ownerFqn)) + graphService.derivedDependencies(fromTypes = listOf(ownerFqn)))
            .filter { it.fromKey == memberKey }

        val returnType: Map<String, Any?> = when {
            isConstructor -> declaringTypeRef
            else -> edges.firstOrNull { it.kind == DependencyKind.RETURNS }?.let { typeRef(it.toType) } ?: primitiveRef("void")
        }
        val throwsList = edges.filter { it.kind == DependencyKind.THROWS }.map { it.toType }.distinct().map { typeRef(it) }
        // `throws` is the declared clause; `raises` is the body-thrown `throw new X(...)` (see ClassScanner).
        val raisesList = edges.filter { it.kind == DependencyKind.RAISES }.map { it.toType }.distinct().map { typeRef(it) }
        val annotations = edges.filter { it.kind == DependencyKind.ANNOTATED_BY }.map { it.toType }.distinct()
            .map { linkedMapOf<String, Any?>("type" to typeRef(it)) }
        // Every method this one overrides: a superclass method and interface methods can all apply.
        val overrides = edges.filter { it.kind == DependencyKind.OVERRIDES }.distinctBy { it.toKey }
            .sortedBy { it.toKey }.map { methodRefFor(it.toKey, it.toType) }

        val parameters = member.parameters.map { p ->
            linkedMapOf<String, Any?>(
                "position" to p.index.toLong(),
                "name" to p.name,
                "type" to typeRef(p.typeFqn),
                "annotations" to p.annotations.map { linkedMapOf<String, Any?>("type" to typeRef(it)) },
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
    private fun methodRefFor(memberKey: String, declaringFqn: String): Map<String, Any?> {
        val parent = typeRef(declaringFqn)
        return linkedMapOf(
            "id" to graphService.memberHandle(memberKey),
            "name" to memberKey.substringAfterLast('#'),
            "qualified_name" to memberKey,
            "kind" to JavaNodeKind.METHOD.value,
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
                "kind" to JavaNodeKind.EXTERNAL_TYPE,
            )
        }
    }

    private fun notFound(methodId: Long): Map<String, Any?> = errorResponse(
        "NODE_NOT_FOUND",
        "Node not found: $methodId.",
        "Look the method up again via list_children on its declaring type or find_node.",
    )
}
