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
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.graphstore.jvm.model.JavaEdgeAttributes
import io.hierograph.graphstore.jvm.archive.model.MemberModifiers
import io.hierograph.graphstore.jvm.archive.ParquetReader
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.server.graphservice.HierarchicalGraphService
import org.springframework.stereotype.Component

/**
 * The engine behind `field_details`, served from the Parquet snapshot. Identity, modifiers and the declared
 * type come from `members.parquet`; the annotations from the field's `dependencies` rows; the read/write
 * access digests from the `reads_field` / `writes_field` edges into the field, including accesses compiled
 * against a subtype that inherits it. Uses the same slim-encoding
 * response shape (a top-level `nodes` map keyed by stringified handle).
 */
@Component
class ParquetFieldDetails(graphService: HierarchicalGraphService) : AbstractDetailTool(graphService) {

    fun fieldDetails(fieldId: Long): Map<String, Any?> {
        val memberKey = graphService.memberHandleIndex[fieldId]
            ?: return notAMember(fieldId, "field", "field_details") ?: notFound(fieldId)
        val dir = readyDetailDir() ?: return detailRebuilding()
        val reader = ParquetReader()
        val member = reader.readMember(dir, memberKey) ?: return notFound(fieldId)
        if (member.kind != JavaNodeKind.FIELD) {
            return errorResponse(
                "WRONG_NODE_KIND",
                "Node $fieldId is a 'java.method', not a field. field_details requires a field-kind node.",
                "To inspect the method itself, use method_details(methodId: $fieldId).",
                mapOf("actual_kind" to JavaNodeKind.METHOD.value),
            )
        }

        val ownerFqn = memberKey.substringBefore('#')
        val modifiers = MemberModifiers.fieldModifiers(member.modifiers)
        // A compile-time constant (ConstantValue attribute), not merely static final.
        val isConstant = MemberModifiers.isConstant(member.modifiers)

        val nodes = linkedMapOf<String, Any>()
        putSlimNode(nodes, fieldId, member.signature, memberKey, JavaNodeKind.FIELD.value)
        val declaringTypeHandle = putType(nodes, ownerFqn)

        val ownEdges = reader.readDependencies(dir, fromTypes = listOf(ownerFqn)).filter { it.fromKey == memberKey }

        // The declared type, from the signature `<type> <name>`: a keyword, an FQN, or an array of either.
        // `type` is the id of its element type when that is a node; `type_name` always names it.
        val typeNameForResponse = member.signature.substringBeforeLast(' ')
        val elementType = typeNameForResponse.substringBefore('[')
        val typeIdForResponse = if (elementType in JAVA_PRIMITIVES) null else putType(nodes, elementType)

        // annotations ({type: id or null, type_name}): an annotation type without a node is kept, by name
        val annotations = ownEdges.filter { it.kind == DependencyKind.ANNOTATED_BY }.map { it.toType }.distinct()
            .map { fqn -> linkedMapOf<String, Any?>("type" to putType(nodes, fqn), "type_name" to fqn) }

        // read/write access: edges into this field, compiled against its declaring type or against a subtype
        // that inherits it without declaring a field of the same name
        val owners = inheritingTypes(ownerFqn, member.name)
        val signature = member.signature
        fun accesses(kind: DependencyKind) = reader.readDependencies(dir, toTypes = owners, kind = kind)
            .filter { it.toType in owners && it.toKey == "${it.toType}#$signature" }
        val readAccess = accessDigest(accesses(DependencyKind.READS_FIELD), nodes)
        val writeAccess = accessDigest(accesses(DependencyKind.WRITES_FIELD), nodes)

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
        )
    }

    /**
     * [ownerFqn] and every scanned type that inherits its field [fieldName]: the transitive subtypes (through
     * `extends` and `implements`) that do not declare a field of that name themselves, which would hide it.
     */
    private fun inheritingTypes(ownerFqn: String, fieldName: String): Set<String> {
        val store = graphService.graphStore ?: return setOf(ownerFqn)
        val model = graphService.model
        val owner = store.typeByFqn(ownerFqn)?.let { model.lookupNode(NodeHandle.of(it.key)) } ?: return setOf(ownerFqn)
        val result = linkedSetOf(ownerFqn)
        val queue = ArrayDeque(listOf(owner))
        val seen = hashSetOf(owner.identifier)
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            for (dep in t.incomingCoreDependencies) {
                val bm = dep.attributesBitmap
                val inherits = JavaEdgeAttributes.isSet(bm, JavaEdgeAttributes.IS_EXTENDS) ||
                    JavaEdgeAttributes.isSet(bm, JavaEdgeAttributes.IS_IMPLEMENTS)
                val sub = dep.from
                if (!inherits || !seen.add(sub.identifier)) continue
                val hides = model.hierarchy.childrenOf(sub).any {
                    it.kind == JavaNodeKind.FIELD && NodeMetadata.getName(it).substringAfterLast(' ') == fieldName
                }
                if (hides) continue
                result += NodeMetadata.getQualifiedName(sub)
                queue.add(sub)
            }
        }
        return result
    }

    /**
     * Builds a read/write digest from the accessing-method edges (each edge's `from_key` is the
     * reading/writing method, `from_type` its declaring type) and registers the referenced nodes.
     */
    private fun accessDigest(edges: List<Dependency>, nodes: MutableMap<String, Any>): MutableMap<String, Any> {
        val methods = edges.associateBy { it.fromKey }.values.sortedBy { it.fromKey } // distinct accessing methods, by type then signature
        val sample = methods.take(10)
        val sampleIds = sample.map { graphService.memberHandle(it.fromKey) }

        val declarerCounts = LinkedHashMap<Long, Int>()
        for (m in methods) (typeHandle(m.fromType) ?: continue).let { declarerCounts.merge(it, 1, Int::plus) }
        val byDeclaringType = declarerCounts.entries.sortedByDescending { it.value }.take(10)
            .map { linkedMapOf<String, Any>("type" to it.key, "count" to it.value) }

        for (m in sample) {
            // Named and kinded as in the containment tree: by signature, a constructor as a java.method.
            putSlimNode(nodes, graphService.memberHandle(m.fromKey), m.fromKey.substringAfterLast('#'), m.fromKey, JavaNodeKind.METHOD.value)
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

    private fun notFound(fieldId: Long): Map<String, Any?> = errorResponse(
        "NODE_NOT_FOUND",
        "Node not found: $fieldId.",
        "Look the field up again via list_children on its declaring type or find_node.",
    )
}
