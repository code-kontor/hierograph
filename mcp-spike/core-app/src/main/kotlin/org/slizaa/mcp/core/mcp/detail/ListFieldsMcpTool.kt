package org.slizaa.mcp.core.mcp.detail

import org.slizaa.mcp.javaspec.JavaKinds
import org.slizaa.mcp.core.HierarchicalGraphService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
class ListFieldsMcpTool(graphService: HierarchicalGraphService) : AbstractDetailMcpTool(graphService) {

    @Tool(
        name = "list_fields",
        description = "[Detail-level] Return the fields declared on a type, with lightweight metadata for each. " +
                "Use this when you have identified a type and want to understand its data members — " +
                "for example, 'what fields does UserEntity have?' or 'list the autowired dependencies of this Spring component.' " +
                "Response shape (slim encoding, ADR-0001): top-level 'nodes' map (each referenced node listed once " +
                "with name, qualified_name, kind, keyed by stringified ID) plus a 'fields' list where each entry " +
                "references nodes by ID. Each field entry carries: 'node' (field ID — resolve via nodes[id]), " +
                "'parent' (declaring-type ID), modifiers, field_type_name, annotation_count, is_constant, is_inherited. " +
                "The annotation_count is particularly valuable for framework-wiring questions — fields with annotations " +
                "are often where Spring injection, JPA mappings, or validation rules live. The summary block surfaces " +
                "aggregate signals like annotated_count, constant_count, and visibility distribution, which often tell " +
                "the framework story before you even look at individual fields. " +
                "Common parameter patterns: " +
                "Just type_id: enumerate all declared fields. " +
                "type_id + modifier_filter: ['private', 'final']: list constructor-injected dependencies. " +
                "type_id + modifier_filter: ['static', 'final']: list the constants this type defines. " +
                "type_id + name_pattern: 'id': find ID-like fields. " +
                "type_id + include_inherited: true: see all fields, including inherited ones. " +
                "Important: include_inherited only shows fields from ancestor types that were part of the scan. " +
                "Fields from external libraries (e.g. framework base classes) are only visible if those libraries " +
                "were included in the jQAssistant scan. If inherited_count is 0, it may mean the superclass is " +
                "outside the scanned codebase, not that there are no inherited fields. " +
                "For deep information about one specific field (full type, list of annotations, " +
                "methods that read or write it), use field_details. " +
                "For 'which methods read this field?' or dependency-driven views, use detail_dependencies. " +
                "For methods rather than fields, use list_methods (same shape, different entity)."
    )
    fun listFields(
        @ToolParam(
            description = "The node ID of the type whose fields should be enumerated. " +
                    "Must be a type-kind node (Class, Interface, Enum, Annotation, Record)."
        ) typeId: Long,
        @ToolParam(
            description = "Optional case-insensitive substring match against the field name.",
            required = false
        ) namePattern: String?,
        @ToolParam(
            description = "Optional list of Java modifiers, ANDed together. " +
                    "Allowed values: public, protected, private, package-private, static, final, transient, volatile.",
            required = false
        ) modifierFilter: List<String>?,
        @ToolParam(
            description = "Whether to include inherited fields from superclasses. Default false.",
            required = false
        ) includeInherited: Boolean?,
        @ToolParam(description = "Max fields to return (1-500, default 50).", required = false) limit: Int?
    ): Map<String, Any?> {

        val allowedModifiers = setOf(
            "public", "protected", "private", "package-private",
            "static", "final", "transient", "volatile"
        )
        if (modifierFilter != null) {
            for (mod in modifierFilter) {
                if (mod !in allowedModifiers) {
                    return linkedMapOf(
                        "error" to "INVALID_MODIFIER",
                        "message" to "Invalid modifier: '$mod'. Allowed values for fields: $allowedModifiers",
                        "invalid_value" to mod
                    )
                }
            }
        }

        val typeNode = graphService.rootNode.lookupNode(typeId)
            ?: return linkedMapOf(
                "error" to "NODE_NOT_FOUND",
                "message" to "Node not found: $typeId. Re-resolve via find_node."
            )

        val mp = getMetadataProvider()
        val kind = mp.getKind(typeNode)
        val typeKinds = setOf("Class", "Interface", "Enum", "Annotation", "Record")
        if (kind !in typeKinds) {
            return linkedMapOf(
                "error" to "WRONG_NODE_KIND",
                "message" to "Node $typeId is a '$kind', not a type. " +
                        "list_fields requires a Class, Interface, Enum, Annotation, or Record.",
                "actual_kind" to kind
            )
        }

        val inherited = includeInherited == true
        val effectiveLimit = if (limit != null) limit.coerceIn(1, 500) else 50

        val cypher = buildListFieldsCypher(inherited)
        val queryResult = graphService.boltClient.syncExecCypherQuery(
            cypher, mapOf("typeId" to typeId)
        )

        val allFields = mutableListOf<Map<String, Any?>>()
        val nodeDisplay = linkedMapOf<Long, Array<String>>()
        var totalPublic = 0; var totalProtected = 0; var totalPrivate = 0; var totalPackagePrivate = 0
        var totalAnnotated = 0; var totalStatic = 0; var totalFinal = 0; var totalConstant = 0
        var totalDeclared = 0; var totalInherited = 0

        for (record in queryResult.records()) {
            val fieldId = record.get("fieldId").asLong()
            val fieldName = record.get("fieldName").asString("")
            val fieldFqn = record.get("fieldFqn").asString("")
            val declaringTypeId = record.get("declaringTypeId").asLong()
            val declaringTypeName = record.get("declaringTypeName").asString("")
            val declaringTypeFqn = record.get("declaringTypeFqn").asString("")
            val declaringTypeLabels = record.get("declaringTypeLabels").asList(org.neo4j.driver.Value::asString)
            val fieldTypeName = if (record.get("fieldTypeName").isNull) "unknown" else record.get("fieldTypeName").asString("unknown")
            val annotationCount = record.get("annotationCount").asLong(0)

            val modifiers = extractFieldModifiers(record)
            val visibility = getVisibility(modifiers)

            if (namePattern != null && namePattern.isNotBlank()) {
                if (!fieldName.lowercase().contains(namePattern.lowercase())) continue
            }

            if (modifierFilter != null && modifierFilter.isNotEmpty()) {
                var allMatch = true
                for (requiredMod in modifierFilter) {
                    if (requiredMod == "package-private") {
                        if (visibility != "package-private") { allMatch = false; break }
                    } else if (requiredMod !in modifiers) {
                        allMatch = false; break
                    }
                }
                if (!allMatch) continue
            }

            val isConstant = "static" in modifiers && "final" in modifiers
            val isInherited = declaringTypeId != typeId
            if (isInherited) totalInherited++ else totalDeclared++
            when (visibility) {
                "public" -> totalPublic++
                "protected" -> totalProtected++
                "private" -> totalPrivate++
                "package-private" -> totalPackagePrivate++
            }
            if (annotationCount > 0) totalAnnotated++
            if ("static" in modifiers) totalStatic++
            if ("final" in modifiers) totalFinal++
            if (isConstant) totalConstant++

            allFields.add(
                linkedMapOf(
                    "node" to fieldId,
                    "parent" to declaringTypeId,
                    "modifiers" to modifiers,
                    "field_type_name" to fieldTypeName,
                    "annotation_count" to annotationCount,
                    "is_constant" to isConstant,
                    "is_inherited" to isInherited
                )
            )

            nodeDisplay.putIfAbsent(fieldId, arrayOf(fieldName, fieldFqn, JavaKinds.FIELD.value))
            nodeDisplay.putIfAbsent(
                declaringTypeId, arrayOf(
                    declaringTypeName, declaringTypeFqn, mp.getKindFromLabels(declaringTypeLabels)
                )
            )
        }

        val totalMatching = allFields.size
        val truncated = totalMatching > effectiveLimit
        val returnedFields = allFields.take(effectiveLimit)

        val nodes = linkedMapOf<String, Any>()
        putSlimNode(nodes, typeId, mp.getName(typeNode), mp.getQualifiedName(typeNode), kind)
        for (entry in returnedFields) {
            val fId = entry["node"] as Long
            val parentTypeId = entry["parent"] as Long
            val tDisp = nodeDisplay[parentTypeId]
            val fDisp = nodeDisplay[fId]
            if (tDisp != null) putSlimNode(nodes, parentTypeId, tDisp[0], tDisp[1], tDisp[2])
            if (fDisp != null) putSlimNode(nodes, fId, fDisp[0], fDisp[1], fDisp[2])
        }

        val summary = linkedMapOf<String, Any>(
            "total_matching" to totalMatching,
            "returned" to returnedFields.size,
            "truncated" to truncated,
            "declared_count" to totalDeclared,
            "inherited_count" to totalInherited,
            "by_visibility" to linkedMapOf(
                "public" to totalPublic,
                "protected" to totalProtected,
                "private" to totalPrivate,
                "package-private" to totalPackagePrivate
            ),
            "annotated_count" to totalAnnotated,
            "static_count" to totalStatic,
            "final_count" to totalFinal,
            "constant_count" to totalConstant
        )

        return linkedMapOf(
            "nodes" to nodes,
            "type" to typeId,
            "fields" to returnedFields,
            "summary" to summary
        )
    }

    private fun buildListFieldsCypher(includeInherited: Boolean): String = if (includeInherited) {
        """
            MATCH (t:Type) WHERE id(t) = ${'$'}typeId
            CALL {
                WITH t
                MATCH (t)-[:DECLARES]->(f:Field)
                MATCH (dt:Type)-[:DECLARES]->(f)
                OPTIONAL MATCH (f)-[:OF_TYPE]->(ft:Type)
                OPTIONAL MATCH (f)-[:ANNOTATED_BY]->(a)
                RETURN f, dt, ft,
                       count(DISTINCT a) AS annotationCount
                UNION
                WITH t
                MATCH (t)-[:EXTENDS*1..]->(ancestor:Type)-[:DECLARES]->(f:Field)
                MATCH (dt:Type)-[:DECLARES]->(f)
                OPTIONAL MATCH (f)-[:OF_TYPE]->(ft:Type)
                OPTIONAL MATCH (f)-[:ANNOTATED_BY]->(a)
                RETURN f, dt, ft,
                       count(DISTINCT a) AS annotationCount
            }
            RETURN id(f) AS fieldId,
                   f.name AS fieldName,
                   f.fqn AS fieldFqn,
                   f.visibility AS visibility,
                   f.static AS isStatic,
                   f.final AS isFinal,
                   f.transient AS isTransient,
                   f.volatile AS isVolatile,
                   id(dt) AS declaringTypeId,
                   dt.name AS declaringTypeName,
                   dt.fqn AS declaringTypeFqn,
                   labels(dt) AS declaringTypeLabels,
                   ft.fqn AS fieldTypeName,
                   annotationCount
        """.trimIndent()
    } else {
        """
            MATCH (t:Type)-[:DECLARES]->(f:Field) WHERE id(t) = ${'$'}typeId
            OPTIONAL MATCH (f)-[:OF_TYPE]->(ft:Type)
            OPTIONAL MATCH (f)-[:ANNOTATED_BY]->(a)
            RETURN id(f) AS fieldId,
                   f.name AS fieldName,
                   f.fqn AS fieldFqn,
                   f.visibility AS visibility,
                   f.static AS isStatic,
                   f.final AS isFinal,
                   f.transient AS isTransient,
                   f.volatile AS isVolatile,
                   id(t) AS declaringTypeId,
                   t.name AS declaringTypeName,
                   t.fqn AS declaringTypeFqn,
                   labels(t) AS declaringTypeLabels,
                   ft.fqn AS fieldTypeName,
                   count(DISTINCT a) AS annotationCount
        """.trimIndent()
    }
}
