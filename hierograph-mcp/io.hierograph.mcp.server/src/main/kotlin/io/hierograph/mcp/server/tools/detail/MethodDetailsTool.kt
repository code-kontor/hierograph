/*
 * Copyright 2024 Gerd Wuetherich
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

import org.neo4j.driver.Record
import org.neo4j.driver.Value
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.server.core.HierarchicalGraphService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
class MethodDetailsTool(graphService: HierarchicalGraphService) : AbstractDetailTool(graphService) {

    @Tool(
        name = "method_details",
        description = "[Detail-level] Return the full structural details of a single method, in one call. " +
                "Use this when you've identified a method of interest (via list_methods, detail_dependencies, " +
                "or another tool that surfaces method IDs) and need the complete picture: modifiers, return type, " +
                "parameters with names and types, declared exceptions, annotations, the method it overrides " +
                "(if any), and source location. " +
                "Response shape: single-entity inline form (no slim 'nodes' wrapper map). " +
                "Single-entity responses with one declaring type don't benefit from slim encoding; inline " +
                "NodeRefs are kept. The declaring_type, return_type, parameter types, throws types, annotation " +
                "types, and overrides target are all full NodeRefs — feed these into other tools (find_node, " +
                "aggregated_incoming, list_methods, etc.) to investigate. " +
                "Primitive types (void, int, boolean, etc.) appear with id: null and kind: 'java.primitive' — " +
                "these are not first-class entities in the graph and cannot be used as input to other tools. " +
                "For generic types like List<String>, the erased type (java.util.List) is reported; type " +
                "parameters are not surfaced in v0.2. " +
                "Use the location field together with your file-reading tools when you need to inspect the " +
                "actual method implementation (the line number points to the method declaration; the body " +
                "follows from there). " +
                "When to use this vs. neighboring tools: " +
                "For the methods declared on a type (composition, not single-method detail), use list_methods. " +
                "For 'which methods call this one?' or 'which methods throw this exception?', use " +
                "detail_dependencies — that's the dependency-driven view rather than the entity-detail view. " +
                "For fields rather than methods, use field_details (uses slim encoding because of read/write digest)."
    )
    fun methodDetails(
        @ToolParam(
            description = "The node ID of the method to inspect. Must be a method-kind node " +
                    "(java.method or java.constructor). Typically obtained from list_methods or detail_dependencies."
        )
        methodId: Long
    ): Map<String, Any?> {

        val mp = getMetadataProvider()

        val cypher = buildMethodDetailsCypher()
        val queryResult = graphService.boltClient.syncExecCypherQuery(
            cypher, mapOf("methodId" to methodId)
        )

        val records = queryResult.records()
        if (records.isEmpty()) {
            return linkedMapOf(
                "error" to "NODE_NOT_FOUND",
                "code" to "NODE_NOT_FOUND",
                "message" to "Node not found: $methodId. Re-resolve via find_node or list_methods."
            )
        }

        val record = records[0]
        val methodLabels = record.get("methodLabels").asList(Value::asString)
        if ("Method" !in methodLabels) {
            val actualKind = mp.getKindFromLabels(methodLabels)
            return linkedMapOf(
                "error" to "WRONG_NODE_KIND",
                "code" to "WRONG_NODE_KIND",
                "message" to "Node $methodId is a '$actualKind', not a method. " +
                        "method_details requires a method-kind node.",
                "actual_kind" to actualKind
            )
        }

        val isConstructor = "Constructor" in methodLabels
        val methodName = record.get("methodName").asString("")
        val methodFqn = record.get("methodFqn").asString("")
        val lineNumber = record.get("lineNumber").asLong(-1)

        val declaringTypeId = record.get("declaringTypeId").asLong(-1)
        val declaringTypeName = record.get("declaringTypeName").asString("")
        val declaringTypeFqn = record.get("declaringTypeFqn").asString("")
        val declaringTypeLabels = if (record.get("declaringTypeLabels").isNull)
            emptyList() else record.get("declaringTypeLabels").asList(Value::asString)
        val declaringTypeKind = mp.getKindFromLabels(declaringTypeLabels)

        val modifiers = extractModifiers(record)

        val methodRef = linkedMapOf<String, Any?>(
            "id" to methodId,
            "name" to methodName,
            "qualified_name" to methodFqn,
            "kind" to if (isConstructor) JavaKinds.CONSTRUCTOR.value else JavaKinds.METHOD.value,
            "parent_id" to declaringTypeId,
            "parent_kind" to declaringTypeKind
        )

        val declaringTypeRef = linkedMapOf<String, Any?>(
            "id" to declaringTypeId,
            "name" to declaringTypeName,
            "qualified_name" to declaringTypeFqn,
            "kind" to declaringTypeKind
        )

        val returnTypeRef: Map<String, Any?> = if (record.get("returnTypeId").isNull) {
            if (isConstructor) declaringTypeRef else primitiveRef("void")
        } else {
            val rtId = record.get("returnTypeId").asLong()
            val rtName = record.get("returnTypeName").asString("")
            val rtFqn = record.get("returnTypeFqn").asString("")
            val rtLabels = record.get("returnTypeLabels").asList(Value::asString)
            toTypeRef(rtId, rtName, rtFqn, rtLabels, mp)
        }

        val parameters = buildParameters(record, mp)
        val throwsList = buildTypeRefList(record.get("throwsList"), mp)
        val methodAnnotations = buildAnnotationList(record.get("methodAnnotations"), mp)

        val overridesRef: Map<String, Any?>? = if (!record.get("overrideId").isNull) {
            val ovId = record.get("overrideId").asLong()
            val ovName = record.get("overrideName").asString("")
            val ovFqn = record.get("overrideFqn").asString("")
            val ovLabels = record.get("overrideLabels").asList(Value::asString)
            val ovIsCtor = "Constructor" in ovLabels
            val ovDtId = record.get("overrideDeclTypeId").asLong(-1)
            val ovDtLabels = if (record.get("overrideDeclTypeLabels").isNull)
                emptyList() else record.get("overrideDeclTypeLabels").asList(Value::asString)

            linkedMapOf(
                "id" to ovId,
                "name" to ovName,
                "qualified_name" to ovFqn,
                "kind" to if (ovIsCtor) JavaKinds.CONSTRUCTOR.value else JavaKinds.METHOD.value,
                "parent_id" to ovDtId,
                "parent_kind" to mp.getKindFromLabels(ovDtLabels)
            )
        } else null

        val location: Map<String, Any>? = if (lineNumber > 0) linkedMapOf("line_number" to lineNumber) else null

        return linkedMapOf(
            "method" to methodRef,
            "declaring_type" to declaringTypeRef,
            "modifiers" to modifiers,
            "is_constructor" to isConstructor,
            "return_type" to returnTypeRef,
            "parameters" to parameters,
            "throws" to throwsList,
            "annotations" to methodAnnotations,
            "overrides" to overridesRef,
            "location" to location
        )
    }

    private fun buildMethodDetailsCypher(): String = """
        MATCH (m) WHERE id(m) = ${'$'}methodId
        OPTIONAL MATCH (dt:Type)-[:DECLARES]->(m)
        OPTIONAL MATCH (m)-[:RETURNS]->(rt:Type)
        OPTIONAL MATCH (m)-[:OVERRIDES]->(ov:Method)
        OPTIONAL MATCH (odt:Type)-[:DECLARES]->(ov)
        CALL {
            WITH m
            OPTIONAL MATCH (m)-[:HAS]->(p:Parameter)
            WITH p WHERE p IS NOT NULL
            OPTIONAL MATCH (p)-[:OF_TYPE]->(pt:Type)
            CALL {
                WITH p
                OPTIONAL MATCH (p)-[:ANNOTATED_BY]->(pa)-[:OF_TYPE]->(pat:Type)
                RETURN collect(DISTINCT {id: id(pat), name: pat.name, fqn: pat.fqn, labels: labels(pat)}) AS pAnns
            }
            RETURN collect({
                index: p.index,
                name: p.name,
                paramTypeId: id(pt),
                paramTypeName: pt.name,
                paramTypeFqn: pt.fqn,
                paramTypeLabels: labels(pt),
                annotations: pAnns
            }) AS parameters
        }
        CALL {
            WITH m
            OPTIONAL MATCH (m)-[:THROWS]->(ex:Type)
            RETURN collect(DISTINCT {id: id(ex), name: ex.name, fqn: ex.fqn, labels: labels(ex)}) AS throwsList
        }
        CALL {
            WITH m
            OPTIONAL MATCH (m)-[:ANNOTATED_BY]->(ma)-[:OF_TYPE]->(at:Type)
            RETURN collect(DISTINCT {id: id(at), name: at.name, fqn: at.fqn, labels: labels(at)}) AS methodAnnotations
        }
        RETURN labels(m) AS methodLabels,
               m.name AS methodName,
               m.fqn AS methodFqn,
               m.firstLineNumber AS lineNumber,
               m.visibility AS visibility,
               m.static AS isStatic,
               m.final AS isFinal,
               m.abstract AS isAbstract,
               m.synchronized AS isSynchronized,
               m.native AS isNative,
               m.default AS isDefault,
               id(dt) AS declaringTypeId,
               dt.name AS declaringTypeName,
               dt.fqn AS declaringTypeFqn,
               labels(dt) AS declaringTypeLabels,
               id(rt) AS returnTypeId,
               rt.name AS returnTypeName,
               rt.fqn AS returnTypeFqn,
               labels(rt) AS returnTypeLabels,
               id(ov) AS overrideId,
               ov.name AS overrideName,
               ov.fqn AS overrideFqn,
               labels(ov) AS overrideLabels,
               id(odt) AS overrideDeclTypeId,
               labels(odt) AS overrideDeclTypeLabels,
               parameters,
               throwsList,
               methodAnnotations
    """.trimIndent()

    private fun buildParameters(record: Record, mp: INodeMetadataProvider): List<Map<String, Any?>> {
        val paramsValue = record.get("parameters")
        if (paramsValue.isNull) return emptyList()

        val raw = paramsValue.values().map { it.asMap() }.toMutableList()

        raw.sortBy { m ->
            val idx = m["index"]
            if (idx is Number) idx.toLong() else 0L
        }

        return raw.mapIndexed { i, p ->
            val idx = p["index"]
            val position = if (idx is Number) idx.toLong() else i.toLong()

            val ptId = asLong(p["paramTypeId"])
            val ptName = p["paramTypeName"] as? String
            val ptFqn = p["paramTypeFqn"] as? String
            @Suppress("UNCHECKED_CAST")
            val ptLabels = p["paramTypeLabels"] as? List<String>

            @Suppress("UNCHECKED_CAST")
            val annsRaw = p["annotations"] as? List<Map<String, Any>>

            linkedMapOf<String, Any?>(
                "position" to position,
                "name" to p["name"],
                "type" to toTypeRef(ptId, ptName, ptFqn, ptLabels, mp),
                "annotations" to buildAnnotationListFromMaps(annsRaw, mp)
            )
        }
    }

    private fun buildTypeRefList(value: Value, mp: INodeMetadataProvider): List<Map<String, Any?>> {
        if (value.isNull) return emptyList()
        return value.values().mapNotNull { v ->
            val m = v.asMap()
            val id = asLong(m["id"]) ?: return@mapNotNull null
            val name = m["name"] as? String
            val fqn = m["fqn"] as? String
            @Suppress("UNCHECKED_CAST")
            val labels = m["labels"] as? List<String>
            toTypeRef(id, name, fqn, labels, mp)
        }
    }

    private fun buildAnnotationList(value: Value, mp: INodeMetadataProvider): List<Map<String, Any?>> {
        if (value.isNull) return emptyList()
        val raw = value.values().map { it.asMap() }
        return buildAnnotationListFromMaps(raw, mp)
    }

    private fun buildAnnotationListFromMaps(raw: List<Map<String, Any>>?, mp: INodeMetadataProvider): List<Map<String, Any?>> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.mapNotNull { m ->
            val id = asLong(m["id"]) ?: return@mapNotNull null
            val name = m["name"] as? String
            val fqn = m["fqn"] as? String
            @Suppress("UNCHECKED_CAST")
            val labels = m["labels"] as? List<String>
            linkedMapOf<String, Any?>("type" to toTypeRef(id, name, fqn, labels, mp))
        }
    }

    private fun toTypeRef(id: Long?, name: String?, fqn: String?, labels: List<String>?, mp: INodeMetadataProvider): Map<String, Any?> {
        if (fqn != null && fqn in JAVA_PRIMITIVES) {
            return primitiveRef(fqn)
        }
        return linkedMapOf(
            "id" to id,
            "name" to (name ?: ""),
            "qualified_name" to (fqn ?: ""),
            "kind" to mp.getKindFromLabels(labels ?: emptyList())
        )
    }
}
