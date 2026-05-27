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

import org.neo4j.driver.Value
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.server.core.HierarchicalGraphService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
class FieldDetailsTool(graphService: HierarchicalGraphService) : AbstractDetailTool(graphService) {

    @Tool(
        name = "field_details",
        description = "[Detail-level] Return the full structural details of a single field, in one call. " +
                "Use this when you've identified a field of interest (via list_fields, detail_dependencies, " +
                "or another tool that surfaces field IDs) and need the complete picture: type, annotations, and " +
                "information about which methods read or write it. " +
                "Response shape (slim encoding): top-level 'nodes' map (each referenced node listed once " +
                "with name, qualified_name, kind, keyed by stringified ID) plus the field's structural details — " +
                "'field', 'declaring_type', 'type' (or null for primitives), 'type_name' (always-present string — " +
                "qualified name for reference types, keyword for primitives), 'annotations' (each entry is " +
                "{type: ID}), 'read_access', 'write_access' — all referencing nodes by ID. " +
                "Read/write access digests carry: method_count (true total across the codebase), methods_sample " +
                "(up to 10 method IDs — resolve via nodes[id]), sample_truncated (boolean), and by_declaring_type " +
                "(list of {type: ID, count: N}, sorted descending by count, capped at 10 entries). For fields with " +
                "many readers (loggers, common dependencies), the digest tells you the structural story without " +
                "needing to enumerate every accessor. " +
                "If you need the full list of readers or writers (beyond the inline sample), use " +
                "detail_dependencies(from=root_id, to=field_id, relationship='reads_field') (or 'writes_field') " +
                "for exhaustive enumeration. " +
                "Primitive field types: 'type' is null and no nodes entry exists for it; read 'type_name' to see " +
                "the primitive keyword (e.g. 'int', 'boolean'). The LLM should not try to use a null type ID as " +
                "input to other tools. " +
                "When to use this vs. neighboring tools: " +
                "For all the fields declared on a type (composition, not single-field detail), use list_fields. " +
                "For 'which methods read this specific field?' with exhaustive enumeration or filters, use " +
                "detail_dependencies with relationship: 'reads_field'. " +
                "For methods rather than fields, use method_details (parallel tool, but uses inline NodeRefs since " +
                "method_details is a single-entity response)."
    )
    fun fieldDetails(
        @ToolParam(
            description = "The node ID of the field to inspect. Must be a field-kind node (java.field). " +
                    "Typically obtained from list_fields or detail_dependencies."
        )
        fieldId: Long
    ): Map<String, Any?> {

        val mp = getMetadataProvider()

        val cypher = buildFieldDetailsCypher()
        val queryResult = graphService.boltClient.syncExecCypherQuery(
            cypher, mapOf("fieldId" to fieldId)
        )

        val records = queryResult.records()
        if (records.isEmpty()) {
            return linkedMapOf(
                "error" to "NODE_NOT_FOUND",
                "code" to "NODE_NOT_FOUND",
                "message" to "Node not found: $fieldId. Re-resolve via find_node or list_fields."
            )
        }

        val record = records[0]
        val fieldLabels = record.get("fieldLabels").asList(Value::asString)
        if ("Field" !in fieldLabels) {
            val actualKind = mp.getKindFromLabels(fieldLabels)
            return linkedMapOf(
                "error" to "WRONG_NODE_KIND",
                "code" to "WRONG_NODE_KIND",
                "message" to "Node $fieldId is a '$actualKind', not a field. field_details requires a field-kind node.",
                "actual_kind" to actualKind
            )
        }

        val fieldName = record.get("fieldName").asString("")
        val fieldFqn = record.get("fieldFqn").asString("")
        val lineNumber = record.get("lineNumber").asLong(-1)

        val declaringTypeId = record.get("declaringTypeId").asLong(-1)
        val declaringTypeName = record.get("declaringTypeName").asString("")
        val declaringTypeFqn = record.get("declaringTypeFqn").asString("")
        val declaringTypeLabels = if (record.get("declaringTypeLabels").isNull)
            emptyList() else record.get("declaringTypeLabels").asList(Value::asString)
        val declaringTypeKind = mp.getKindFromLabels(declaringTypeLabels)

        val modifiers = extractFieldModifiers(record)
        val isConstant = "static" in modifiers && "final" in modifiers

        val fieldTypeId: Long? = if (record.get("fieldTypeId").isNull) null else record.get("fieldTypeId").asLong()
        val fieldTypeFqn: String? = record.get("fieldTypeFqn").asString(null)
        val fieldTypeName: String? = record.get("fieldTypeName").asString(null)
        val fieldTypeLabels = if (record.get("fieldTypeLabels").isNull)
            emptyList() else record.get("fieldTypeLabels").asList(Value::asString)

        val typeIdForResponse: Long?
        val typeNameForResponse: String
        if (fieldTypeFqn != null && fieldTypeFqn in JAVA_PRIMITIVES) {
            typeIdForResponse = null
            typeNameForResponse = fieldTypeFqn
        } else if (fieldTypeId != null) {
            typeIdForResponse = fieldTypeId
            typeNameForResponse = fieldTypeFqn ?: (fieldTypeName ?: "unknown")
        } else {
            typeIdForResponse = null
            typeNameForResponse = "unknown"
        }

        val rawAnnotations = collectMaps(record.get("annotations"))
        val annotations = mutableListOf<Map<String, Any?>>()
        val annotationDisplay = linkedMapOf<Long, Array<String>>()
        for (a in rawAnnotations) {
            val aid = asLong(a["id"]) ?: continue
            annotations.add(linkedMapOf("type" to aid))
            @Suppress("UNCHECKED_CAST")
            val aLabels = a["labels"] as? List<String>
            annotationDisplay[aid] = arrayOf(
                asString(a["name"]), asString(a["fqn"]),
                mp.getKindFromLabels(aLabels ?: emptyList())
            )
        }

        val nodes = linkedMapOf<String, Any>()
        putSlimNode(nodes, fieldId, fieldName, fieldFqn, JavaKinds.FIELD.value)
        putSlimNode(nodes, declaringTypeId, declaringTypeName, declaringTypeFqn, declaringTypeKind)
        if (typeIdForResponse != null) {
            putSlimNode(
                nodes, typeIdForResponse,
                fieldTypeName ?: "",
                fieldTypeFqn ?: "",
                mp.getKindFromLabels(fieldTypeLabels)
            )
        }
        for ((key, d) in annotationDisplay) {
            putSlimNode(nodes, key, d[0], d[1], d[2])
        }

        val readCount = record.get("readCount").asLong(0)
        val rawReaders = collectMaps(record.get("readers"))
        val readAccess = buildAccessDigest(rawReaders, mp, nodes)
        readAccess["method_count"] = readCount

        val writeCount = record.get("writeCount").asLong(0)
        val rawWriters = collectMaps(record.get("writers"))
        val writeAccess = buildAccessDigest(rawWriters, mp, nodes)
        writeAccess["method_count"] = writeCount

        val location: Map<String, Any>? = if (lineNumber > 0) linkedMapOf("line_number" to lineNumber) else null

        return linkedMapOf(
            "nodes" to nodes,
            "field" to fieldId,
            "declaring_type" to declaringTypeId,
            "modifiers" to modifiers,
            "is_constant" to isConstant,
            "type" to typeIdForResponse,
            "type_name" to typeNameForResponse,
            "annotations" to annotations,
            "read_access" to readAccess,
            "write_access" to writeAccess,
            "location" to location
        )
    }

    private fun buildFieldDetailsCypher(): String = """
        MATCH (f) WHERE id(f) = ${'$'}fieldId
        OPTIONAL MATCH (dt:Type)-[:DECLARES]->(f)
        OPTIONAL MATCH (f)-[:OF_TYPE]->(ft:Type)
        CALL {
            WITH f
            OPTIONAL MATCH (f)-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->(at:Type)
            RETURN collect(DISTINCT {id: id(at), name: at.name, fqn: at.fqn, labels: labels(at)}) AS annotations
        }
        CALL {
            WITH f
            OPTIONAL MATCH (reader:Method)-[:READS]->(f)
            OPTIONAL MATCH (readerDt:Type)-[:DECLARES]->(reader)
            RETURN count(DISTINCT reader) AS readCount,
                   collect(DISTINCT {
                       id: id(reader), name: reader.name, fqn: reader.fqn, labels: labels(reader),
                       declarerId: id(readerDt), declarerName: readerDt.name,
                       declarerFqn: readerDt.fqn, declarerLabels: labels(readerDt)
                   }) AS readers
        }
        CALL {
            WITH f
            OPTIONAL MATCH (writer:Method)-[:WRITES]->(f)
            OPTIONAL MATCH (writerDt:Type)-[:DECLARES]->(writer)
            RETURN count(DISTINCT writer) AS writeCount,
                   collect(DISTINCT {
                       id: id(writer), name: writer.name, fqn: writer.fqn, labels: labels(writer),
                       declarerId: id(writerDt), declarerName: writerDt.name,
                       declarerFqn: writerDt.fqn, declarerLabels: labels(writerDt)
                   }) AS writers
        }
        RETURN labels(f) AS fieldLabels,
               f.name AS fieldName, f.fqn AS fieldFqn,
               f.visibility AS visibility,
               f.static AS isStatic, f.final AS isFinal,
               f.transient AS isTransient, f.volatile AS isVolatile,
               f.firstLineNumber AS lineNumber,
               id(dt) AS declaringTypeId, dt.name AS declaringTypeName,
               dt.fqn AS declaringTypeFqn, labels(dt) AS declaringTypeLabels,
               id(ft) AS fieldTypeId, ft.name AS fieldTypeName,
               ft.fqn AS fieldTypeFqn, labels(ft) AS fieldTypeLabels,
               annotations,
               readCount, readers,
               writeCount, writers
    """.trimIndent()

    /**
     * Builds an access digest (read_access or write_access) from the raw list of method maps
     * collected from Cypher and registers the referenced method/declaring-type nodes directly
     * into the supplied slim `nodes` map.
     */
    private fun buildAccessDigest(
        rawMethods: List<Map<String, Any>>,
        mp: INodeMetadataProvider,
        nodes: MutableMap<String, Any>
    ): MutableMap<String, Any> {

        // Drop the all-null entry that OPTIONAL MATCH + collect produces when nothing matched.
        val methods = rawMethods.filter { asLong(it["id"]) != null }
            .sortedBy { asString(it["fqn"]) }

        val sampleSize = minOf(10, methods.size)
        val sampleIds = mutableListOf<Long>()
        val sampleSet = mutableSetOf<Long>()
        for (i in 0 until sampleSize) {
            val id = asLong(methods[i]["id"])!!
            sampleIds.add(id)
            sampleSet.add(id)
        }
        val sampleTruncated = methods.size > sampleIds.size

        val declarerCounts = linkedMapOf<Long, Int>()
        for (m in methods) {
            val declarerId = asLong(m["declarerId"]) ?: continue
            declarerCounts.merge(declarerId, 1, Integer::sum)
        }
        val totalDeclarers = declarerCounts.size
        val byDeclaringType = declarerCounts.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (key, value) ->
                linkedMapOf<String, Any>("type" to key, "count" to value)
            }

        // Register node display fields directly into the slim nodes map
        for (m in methods) {
            val mid = asLong(m["id"])
            if (mid != null && mid in sampleSet) {
                @Suppress("UNCHECKED_CAST")
                val labels = m["labels"] as? List<String>
                putSlimNode(
                    nodes, mid,
                    asString(m["name"]), asString(m["fqn"]),
                    deriveDetailKind(labels ?: emptyList())
                )
            }
            val declarerId = asLong(m["declarerId"])
            if (declarerId != null) {
                @Suppress("UNCHECKED_CAST")
                val declarerLabels = m["declarerLabels"] as? List<String>
                putSlimNode(
                    nodes, declarerId,
                    asString(m["declarerName"]), asString(m["declarerFqn"]),
                    mp.getKindFromLabels(declarerLabels ?: emptyList())
                )
            }
        }

        val digest = linkedMapOf<String, Any>(
            "method_count" to methods.size,  // overwritten by caller with Cypher count
            "methods_sample" to sampleIds,
            "sample_truncated" to sampleTruncated,
            "by_declaring_type" to byDeclaringType
        )
        if (totalDeclarers > byDeclaringType.size) {
            digest["others_count"] = totalDeclarers - byDeclaringType.size
        }
        return digest
    }
}
