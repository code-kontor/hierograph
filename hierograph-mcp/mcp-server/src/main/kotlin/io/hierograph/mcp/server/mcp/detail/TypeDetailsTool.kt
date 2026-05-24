package io.hierograph.mcp.server.mcp.detail

import org.neo4j.driver.Value
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import org.slizaa.mcp.core.HierarchicalGraphService
import org.slizaa.mcp.core.mcp.INodeRefFactory
import io.hierograph.mcp.javaspec.JavaKinds
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `type_details`
 *
 * Returns full structural details for a single type: modifiers, superclass,
 * interfaces, annotations, member counts, inner types, and source location.
 * Uses inline NodeRefs (not slim encoding).
 */
@Component
class TypeDetailsTool(
    graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory
) : AbstractDetailTool(graphService) {

    @Tool(
        name = "type_details",
        description = "[Entity detail] " +
                "Return full structural details for a single type — superclass, interfaces, " +
                "annotations, inner types, member counts, modifiers, and source location. " +
                "Input must be a type-kind node ID (java.class, java.interface, java.enum, " +
                "java.record, java.annotation). " +
                "Complements the enriched NodeRef from browse tools: browse tools give counts " +
                "and flags; this tool gives the actual referenced types as navigable NodeRefs. " +
                "For the members of a type (methods, fields), use list_children. " +
                "For full method or field detail, use method_details or field_details."
    )
    fun typeDetails(
        @ToolParam(description = "The node ID of the type to inspect.")
        typeId: Long
    ): Map<String, Any?> {

        val mp = getMetadataProvider()

        // ── query Neo4j ────────────────────────────────────────────────
        val cypher = buildTypeDetailsCypher()
        val queryResult = graphService.boltClient.syncExecCypherQuery(
            cypher, mapOf<String, Any>("typeId" to typeId)
        )

        val records = queryResult.records()
        if (records.isEmpty()) {
            return mapOf(
                "error" to mapOf(
                    "code" to "NODE_NOT_FOUND",
                    "message" to "No node with id $typeId exists in the graph.",
                    "recovery" to "Use find_node to look up the correct node ID."
                )
            )
        }

        val record = records[0]
        val typeLabels = record.get("typeLabels").asList { it.asString() }

        // ── validate kind ──────────────────────────────────────────────
        val typeKindLabels = setOf("Class", "Interface", "Enum", "Annotation", "Record")
        if (typeKindLabels.none { it in typeLabels }) {
            val actualKind = mp.getKindFromLabels(typeLabels)
            val hgNode = graphService.rootNode.lookupNode(typeId)
            val declaringType = hgNode?.parent

            return mapOf(
                "error" to mapOf(
                    "code" to "WRONG_NODE_KIND",
                    "message" to "Node $typeId is a '$actualKind', not a type. " +
                            "type_details requires a type-kind node (java.class, java.interface, java.enum, java.record, java.annotation).",
                    "actual_kind" to actualKind,
                    "declaring_type" to if (declaringType != null && declaringType != graphService.rootNode)
                        nodeRefFactory.minimalNodeRef(declaringType) else null,
                    "recovery" to when {
                        "Method" in typeLabels -> "To inspect the method itself, use method_details(method_id: $typeId)."
                        "Field" in typeLabels -> "To inspect the field itself, use field_details(field_id: $typeId)."
                        else -> "Use list_children or list_descendants to find type nodes."
                    }
                )
            )
        }

        // ── build type NodeRef ─────────────────────────────────────────
        val typeName = record.get("typeName").asString("")
        val typeFqn = record.get("typeFqn").asString("")
        val typeKind = mp.getKindFromLabels(typeLabels)

        val hgNode = graphService.rootNode.lookupNode(typeId)

        val typeRef = linkedMapOf<String, Any?>(
            "id" to typeId,
            "name" to typeName,
            "qualified_name" to typeFqn,
            "kind" to typeKind,
            "parent_id" to hgNode?.parent?.identifier,
            "parent_kind" to hgNode?.parent?.kind?.toString()
        )

        // ── parent container ───────────────────────────────────────────
        val parentContainer = if (hgNode?.parent != null && hgNode.parent != graphService.rootNode) {
            nodeRefFactory.minimalNodeRef(hgNode.parent)
        } else null

        // ── modifiers ──────────────────────────────────────────────────
        val modifiers = buildList {
            val visibility = record.get("visibility").asString(null)
            add(visibility?.lowercase() ?: "package-private")
            if (record.get("isAbstract").asBoolean(false)) add("abstract")
            if (record.get("isFinal").asBoolean(false)) add("final")
            if (record.get("isStatic").asBoolean(false)) add("static")
        }

        val isAbstract = record.get("isAbstract").asBoolean(false)
        val isGeneric = record.get("isGeneric").asBoolean(false)

        // ── superclass ─────────────────────────────────────────────────
        val superclass = if (!record.get("superclassId").isNull) {
            val scId = record.get("superclassId").asLong()
            val scName = record.get("superclassName").asString("")
            val scFqn = record.get("superclassFqn").asString("")
            val scLabels = record.get("superclassLabels").asList { it.asString() }
            linkedMapOf<String, Any?>(
                "id" to scId,
                "name" to scName,
                "qualified_name" to scFqn,
                "kind" to mp.getKindFromLabels(scLabels)
            )
        } else null

        // ── interfaces ─────────────────────────────────────────────────
        val interfaces = buildTypeRefList(record.get("interfaces"), mp)

        // ── annotations ────────────────────────────────────────────────
        val annotationsRaw = buildTypeRefList(record.get("annotations"), mp)
        val annotations = annotationsRaw.map { ref ->
            linkedMapOf<String, Any?>("type" to ref)
        }

        // ── member counts (from in-memory model) ───────────────────────
        val memberSummary = if (hgNode != null) {
            val methods = hgNode.children.count { it.kind == JavaKinds.METHOD }
            val constructors = hgNode.children.count { it.kind == JavaKinds.CONSTRUCTOR }
            val fields = hgNode.children.count { it.kind == JavaKinds.FIELD }
            linkedMapOf<String, Any?>(
                "method_count" to (methods - constructors),
                "field_count" to fields,
                "constructor_count" to constructors
            )
        } else {
            linkedMapOf<String, Any?>(
                "method_count" to 0,
                "field_count" to 0,
                "constructor_count" to 0
            )
        }

        // ── inner types (from in-memory model) ─────────────────────────
        val innerTypes = if (hgNode != null) {
            hgNode.children
                .filter { it.kind in JavaKinds.TYPE_KINDS }
                .map { nodeRefFactory.minimalNodeRef(it) }
        } else emptyList()

        // ── location ───────────────────────────────────────────────────
        val lineNumber = record.get("lineNumber").asLong(-1)
        val sourceFile = record.get("sourceFile").asString(null)
        val location = if (lineNumber > 0 || sourceFile != null) {
            linkedMapOf<String, Any?>().apply {
                if (sourceFile != null) put("source_file", sourceFile)
                if (lineNumber > 0) put("line_number", lineNumber)
            }
        } else null

        // ── assemble response ──────────────────────────────────────────
        return linkedMapOf<String, Any?>(
            "type" to typeRef,
            "parent_container" to parentContainer,
            "modifiers" to modifiers,
            "is_abstract" to isAbstract,
            "is_generic" to isGeneric,
            "superclass" to superclass,
            "interfaces" to interfaces,
            "annotations" to annotations,
            "member_summary" to memberSummary,
            "inner_types" to innerTypes,
            "location" to location
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun buildTypeRefList(value: Value?, mp: INodeMetadataProvider): List<Map<String, Any?>> {
        if (value == null || value.isNull) return emptyList()
        return value.values()
            .map { it.asMap() }
            .filter { asLong(it["id"]) != null }
            .map { m ->
                val id = asLong(m["id"])!!
                val name = m["name"]?.toString() ?: ""
                val fqn = m["fqn"]?.toString() ?: ""
                @Suppress("UNCHECKED_CAST")
                val labels = (m["labels"] as? List<String>) ?: emptyList()
                linkedMapOf<String, Any?>(
                    "id" to id,
                    "name" to name,
                    "qualified_name" to fqn,
                    "kind" to mp.getKindFromLabels(labels)
                )
            }
    }

    private fun buildTypeDetailsCypher(): String = """
        MATCH (t) WHERE id(t) = ${'$'}typeId
        OPTIONAL MATCH (t)-[:EXTENDS]->(sc:Type)
        CALL {
            WITH t
            OPTIONAL MATCH (t)-[:IMPLEMENTS]->(iface:Type)
            RETURN collect(DISTINCT {id: id(iface), name: iface.name, fqn: iface.fqn, labels: labels(iface)}) AS interfaces
        }
        CALL {
            WITH t
            OPTIONAL MATCH (t)-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->(at:Type)
            RETURN collect(DISTINCT {id: id(at), name: at.name, fqn: at.fqn, labels: labels(at)}) AS annotations
        }
        RETURN labels(t) AS typeLabels,
               t.name AS typeName,
               t.fqn AS typeFqn,
               t.visibility AS visibility,
               t.abstract AS isAbstract,
               t.final AS isFinal,
               t.static AS isStatic,
               t.generic AS isGeneric,
               t.firstLineNumber AS lineNumber,
               t.fileName AS sourceFile,
               id(sc) AS superclassId,
               sc.name AS superclassName,
               sc.fqn AS superclassFqn,
               labels(sc) AS superclassLabels,
               interfaces,
               annotations
    """.trimIndent()
}
