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

import io.hierograph.graphstore.model.DetailRef
import io.hierograph.graphstore.model.MemberModifiers
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.graphstore.model.JavaKinds
import io.hierograph.graphstore.model.JavaNodeKind
import io.hierograph.server.graphservice.HierarchicalGraphService
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Parquet-backed `field_details` — the Neo4j-free counterpart of the Cypher path in [FieldDetailsTool],
 * selected when `hierograph.graph.source=parquet`. Identity (name/modifiers/location) comes from
 * `members.parquet`; the field type and annotations from the field's `detail_refs` edges; the read/write
 * access digests from the reverse `reads_field` / `writes_field` edges. Uses the same slim-encoding
 * response shape (a top-level `nodes` map keyed by stringified handle).
 */
@Component
class ParquetFieldDetails(graphService: HierarchicalGraphService) : AbstractDetailTool(graphService) {

    fun fieldDetails(fieldId: Long): Map<String, Any?> {
        val memberKey = graphService.memberHandleIndex[fieldId]
            ?: return notFound(fieldId)
        val dir = Path.of(graphService.parquetDirectory)
        val reader = ParquetReader()
        val member = reader.readMember(dir, memberKey) ?: return notFound(fieldId)
        if (member.kind != JavaNodeKind.FIELD) {
            return linkedMapOf(
                "error" to "WRONG_NODE_KIND", "code" to "WRONG_NODE_KIND",
                "message" to "Node $fieldId is a '${member.kind.value}', not a field. field_details requires a field-kind node.",
                "actual_kind" to member.kind.value,
            )
        }

        val ownerFqn = memberKey.substringBefore('#')
        val modifiers = MemberModifiers.fieldModifiers(member.modifiers)
        val isConstant = "static" in modifiers && "final" in modifiers

        val nodes = linkedMapOf<String, Any>()
        putSlimNode(nodes, fieldId, member.name, memberKey, JavaKinds.FIELD.value)
        val declaringTypeHandle = putType(nodes, ownerFqn)

        val ownEdges = reader.readDetailRefs(dir, fromTypes = listOf(ownerFqn)).filter { it.fromKey == memberKey }

        // field type (has_type)
        val typeFqn = ownEdges.firstOrNull { it.relationship == "has_type" }?.toType
        val typeIdForResponse: Long?
        val typeNameForResponse: String
        when {
            typeFqn == null -> { typeIdForResponse = null; typeNameForResponse = "unknown" }
            typeFqn in JAVA_PRIMITIVES -> { typeIdForResponse = null; typeNameForResponse = typeFqn }
            else -> { typeIdForResponse = putType(nodes, typeFqn); typeNameForResponse = typeFqn }
        }

        // annotations ({type: id})
        val annotations = ownEdges.filter { it.relationship == "annotated_by" }.map { it.toType }.distinct()
            .mapNotNull { fqn -> putType(nodes, fqn)?.let { linkedMapOf<String, Any?>("type" to it) } }

        // read/write access: reverse edges anchored on this field (to_key == fieldKey)
        val readers = reader.readDetailRefs(dir, toTypes = listOf(ownerFqn), relationship = "reads_field").filter { it.toKey == memberKey }
        val writers = reader.readDetailRefs(dir, toTypes = listOf(ownerFqn), relationship = "writes_field").filter { it.toKey == memberKey }
        val readAccess = accessDigest(reader, dir, readers, nodes)
        val writeAccess = accessDigest(reader, dir, writers, nodes)

        val location = member.lineFrom?.takeIf { it > 0 }?.let { linkedMapOf<String, Any>("line_number" to it.toLong()) }

        return linkedMapOf(
            "nodes" to nodes,
            "field" to fieldId,
            "declaring_type" to declaringTypeHandle,
            "modifiers" to modifiers,
            "is_constant" to isConstant,
            "type" to typeIdForResponse,
            "type_name" to typeNameForResponse,
            "annotations" to annotations,
            "read_access" to readAccess,
            "write_access" to writeAccess,
            "location" to location,
        )
    }

    /**
     * Builds a read/write digest from the accessing-method edges (each edge's `from_key` is the
     * reading/writing method, `from_type` its declaring type) and registers the referenced nodes.
     */
    private fun accessDigest(reader: ParquetReader, dir: Path, edges: List<DetailRef>, nodes: MutableMap<String, Any>): MutableMap<String, Any> {
        val methods = edges.associateBy { it.fromKey }.values.sortedBy { it.fromType } // distinct accessing methods
        val sample = methods.take(10)
        val sampleIds = sample.map { graphService.memberHandle(it.fromKey) }

        val declarerCounts = LinkedHashMap<Long, Int>()
        for (m in methods) (typeHandle(m.fromType) ?: continue).let { declarerCounts.merge(it, 1, Int::plus) }
        val byDeclaringType = declarerCounts.entries.sortedByDescending { it.value }.take(10)
            .map { linkedMapOf<String, Any>("type" to it.key, "count" to it.value) }

        for (m in sample) {
            val handle = graphService.memberHandle(m.fromKey)
            val target = reader.readMember(dir, m.fromKey)
            val isCtor = target?.kind == JavaNodeKind.CONSTRUCTOR || m.fromKey.substringAfterLast('#').startsWith("<init>")
            putSlimNode(nodes, handle, target?.name ?: m.fromKey.substringAfterLast('#').substringBefore('('), m.fromKey,
                (if (isCtor) JavaNodeKind.CONSTRUCTOR else JavaNodeKind.METHOD).value)
            putType(nodes, m.fromType)
        }
        for (e in byDeclaringType) graphService.model.lookupNode(e["type"] as Long)?.let { putSlimNode(nodes, it) }

        val digest = linkedMapOf<String, Any>(
            "method_count" to methods.size,
            "methods_sample" to sampleIds,
            "sample_truncated" to (methods.size > sample.size),
            "by_declaring_type" to byDeclaringType,
        )
        if (declarerCounts.size > byDeclaringType.size) digest["others_count"] = declarerCounts.size - byDeclaringType.size
        return digest
    }

    private fun typeHandle(fqn: String): Long? = graphService.graphStore?.typeByFqn(fqn)?.key?.let { NodeHandle.of(it) }

    /** Registers the model node for [fqn] in [nodes] (slim) and returns its handle, or null for primitives/misses. */
    private fun putType(nodes: MutableMap<String, Any>, fqn: String): Long? {
        if (fqn in JAVA_PRIMITIVES) return null
        val handle = typeHandle(fqn) ?: return null
        graphService.model.lookupNode(handle)?.let { putSlimNode(nodes, it) }
        return handle
    }

    private fun notFound(fieldId: Long): Map<String, Any?> = linkedMapOf(
        "error" to "NODE_NOT_FOUND", "code" to "NODE_NOT_FOUND",
        "message" to "Node not found: $fieldId. Re-resolve via detail_dependencies.",
    )
}
