package org.slizaa.mcp.core.mcp.detail

import org.slizaa.mcp.javaspec.JavaKinds
import org.slizaa.mcp.core.HierarchicalGraphService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
class ListMethodsMcpTool(graphService: HierarchicalGraphService) : AbstractDetailMcpTool(graphService) {

    @Tool(
        name = "list_methods",
        description = "[Detail-level] Return the methods declared on a type, with lightweight metadata for each. " +
                "Use this when you have identified a type and want to understand its method-level composition — " +
                "for example, 'what does ClusterService contain?' or 'list the public methods of this class.' " +
                "Response shape (slim encoding, ADR-0001): top-level 'nodes' map (each referenced node listed once " +
                "with name, qualified_name, kind, keyed by stringified ID) plus a 'methods' list where each entry " +
                "references nodes by ID. Each method entry carries: 'node' (method ID — resolve via nodes[id]), " +
                "'parent' (declaring-type ID), plus counts (parameter_count, throws_count, annotation_count), " +
                "modifier flags, is_constructor, is_inherited. The counts let you decide which methods are worth " +
                "investigating further (high annotation_count suggests framework wiring; high throws_count suggests " +
                "error-handling complexity). The summary block gives a structural overview (visibility distribution, " +
                "constructor count, declared vs. inherited) that's often more useful than enumerating every method. " +
                "Common parameter patterns: " +
                "Just type_id: enumerate all declared methods. " +
                "type_id + modifier_filter: ['public']: list the public API. " +
                "type_id + name_pattern: 'init': find initialization-style methods. " +
                "type_id + include_inherited: true: see the full callable surface, including methods from ancestors. " +
                "Important: include_inherited only shows methods from ancestor types that were part of the scan. " +
                "Methods from external libraries (e.g. java.lang.Object, framework base classes) are only visible " +
                "if those libraries were included in the jQAssistant scan. If inherited_count is 0, it may mean " +
                "the superclass is outside the scanned codebase, not that there are no inherited methods. " +
                "For deep information about one specific method (parameters, return type, throws, " +
                "annotations, location), use method_details. " +
                "For 'which methods call this one?' or dependency-driven views, use detail_dependencies."
    )
    fun listMethods(
        @ToolParam(
            description = "The node ID of the type whose methods should be enumerated. " +
                    "Must be a type-kind node (Class, Interface, Enum, Annotation, Record)."
        ) typeId: Long,
        @ToolParam(
            description = "Optional case-insensitive substring match against the method name.",
            required = false
        ) namePattern: String?,
        @ToolParam(
            description = "Optional list of Java modifiers, ANDed together. " +
                    "Allowed values: public, protected, private, package-private, static, final, abstract, synchronized, native, default.",
            required = false
        ) modifierFilter: List<String>?,
        @ToolParam(
            description = "Whether to include inherited methods from superclasses and interfaces. Default false.",
            required = false
        ) includeInherited: Boolean?,
        @ToolParam(description = "Max methods to return (1-500, default 50).", required = false) limit: Int?
    ): Map<String, Any?> {

        val allowedModifiers = setOf(
            "public", "protected", "private", "package-private",
            "static", "final", "abstract", "synchronized", "native", "default"
        )
        if (modifierFilter != null) {
            for (mod in modifierFilter) {
                if (mod !in allowedModifiers) {
                    return linkedMapOf(
                        "error" to "INVALID_MODIFIER",
                        "message" to "Invalid modifier: '$mod'. Allowed values: $allowedModifiers",
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
                        "list_methods requires a Class, Interface, Enum, Annotation, or Record.",
                "actual_kind" to kind
            )
        }

        val inherited = includeInherited == true
        val effectiveLimit = if (limit != null) limit.coerceIn(1, 500) else 50

        val cypher = buildListMethodsCypher(inherited)
        val queryResult = graphService.boltClient.syncExecCypherQuery(
            cypher, mapOf("typeId" to typeId)
        )

        val allMethods = mutableListOf<Map<String, Any?>>()
        val nodeDisplay = linkedMapOf<Long, Array<String>>()
        var totalPublic = 0; var totalProtected = 0; var totalPrivate = 0; var totalPackagePrivate = 0
        var totalConstructors = 0; var totalAbstract = 0
        var totalDeclared = 0; var totalInherited = 0

        for (record in queryResult.records()) {
            val methodId = record.get("methodId").asLong()
            val methodName = record.get("methodName").asString("")
            val methodFqn = record.get("methodFqn").asString("")
            val isConstructor = record.get("isConstructor").asBoolean(false)
            val declaringTypeId = record.get("declaringTypeId").asLong()
            val declaringTypeName = record.get("declaringTypeName").asString("")
            val declaringTypeFqn = record.get("declaringTypeFqn").asString("")
            val declaringTypeLabels = record.get("declaringTypeLabels").asList(org.neo4j.driver.Value::asString)
            val returnTypeName = if (record.get("returnTypeName").isNull) "void" else record.get("returnTypeName").asString("void")
            val paramCount = record.get("paramCount").asLong(0)
            val throwsCount = record.get("throwsCount").asLong(0)
            val annotationCount = record.get("annotationCount").asLong(0)

            val modifiers = extractModifiers(record)
            val visibility = getVisibility(modifiers)

            if (namePattern != null && namePattern.isNotBlank()) {
                if (!methodName.lowercase().contains(namePattern.lowercase())) continue
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

            val isInherited = declaringTypeId != typeId
            if (isInherited) totalInherited++ else totalDeclared++
            when (visibility) {
                "public" -> totalPublic++
                "protected" -> totalProtected++
                "private" -> totalPrivate++
                "package-private" -> totalPackagePrivate++
            }
            if (isConstructor) totalConstructors++
            if ("abstract" in modifiers) totalAbstract++

            allMethods.add(
                linkedMapOf(
                    "node" to methodId,
                    "parent" to declaringTypeId,
                    "modifiers" to modifiers,
                    "return_type_name" to returnTypeName,
                    "parameter_count" to paramCount,
                    "throws_count" to throwsCount,
                    "annotation_count" to annotationCount,
                    "is_constructor" to isConstructor,
                    "is_inherited" to isInherited
                )
            )

            nodeDisplay.putIfAbsent(
                methodId, arrayOf(
                    methodName, methodFqn, if (isConstructor) JavaKinds.CONSTRUCTOR.value else JavaKinds.METHOD.value
                )
            )
            nodeDisplay.putIfAbsent(
                declaringTypeId, arrayOf(
                    declaringTypeName, declaringTypeFqn, mp.getKindFromLabels(declaringTypeLabels)
                )
            )
        }

        val totalMatching = allMethods.size
        val truncated = totalMatching > effectiveLimit
        val returnedMethods = allMethods.take(effectiveLimit)

        val nodes = linkedMapOf<String, Any>()
        putSlimNode(nodes, typeId, mp.getName(typeNode), mp.getQualifiedName(typeNode), kind)
        for (entry in returnedMethods) {
            val mId = entry["node"] as Long
            val parentTypeId = entry["parent"] as Long
            val tDisp = nodeDisplay[parentTypeId]
            val mDisp = nodeDisplay[mId]
            if (tDisp != null) putSlimNode(nodes, parentTypeId, tDisp[0], tDisp[1], tDisp[2])
            if (mDisp != null) putSlimNode(nodes, mId, mDisp[0], mDisp[1], mDisp[2])
        }

        val summary = linkedMapOf<String, Any>(
            "total_matching" to totalMatching,
            "returned" to returnedMethods.size,
            "truncated" to truncated,
            "declared_count" to totalDeclared,
            "inherited_count" to totalInherited,
            "by_visibility" to linkedMapOf(
                "public" to totalPublic,
                "protected" to totalProtected,
                "private" to totalPrivate,
                "package-private" to totalPackagePrivate
            ),
            "constructors" to totalConstructors,
            "abstract_methods" to totalAbstract
        )

        return linkedMapOf(
            "nodes" to nodes,
            "type" to typeId,
            "methods" to returnedMethods,
            "summary" to summary
        )
    }

    private fun buildListMethodsCypher(includeInherited: Boolean): String = if (includeInherited) {
        """
            MATCH (t:Type) WHERE id(t) = ${'$'}typeId
            CALL {
                WITH t
                MATCH (t)-[:DECLARES]->(m:Method)
                MATCH (dt:Type)-[:DECLARES]->(m)
                OPTIONAL MATCH (m)-[:RETURNS]->(rt:Type)
                OPTIONAL MATCH (m)-[:HAS]->(p:Parameter)
                OPTIONAL MATCH (m)-[:THROWS]->(ex:Type)
                OPTIONAL MATCH (m)-[:ANNOTATED_BY]->(a)
                RETURN m, dt, rt,
                       count(DISTINCT p) AS paramCount,
                       count(DISTINCT ex) AS throwsCount,
                       count(DISTINCT a) AS annotationCount
                UNION
                WITH t
                MATCH (t)-[:EXTENDS|IMPLEMENTS*1..]->(ancestor:Type)-[:DECLARES]->(m:Method)
                MATCH (dt:Type)-[:DECLARES]->(m)
                OPTIONAL MATCH (m)-[:RETURNS]->(rt:Type)
                OPTIONAL MATCH (m)-[:HAS]->(p:Parameter)
                OPTIONAL MATCH (m)-[:THROWS]->(ex:Type)
                OPTIONAL MATCH (m)-[:ANNOTATED_BY]->(a)
                RETURN m, dt, rt,
                       count(DISTINCT p) AS paramCount,
                       count(DISTINCT ex) AS throwsCount,
                       count(DISTINCT a) AS annotationCount
            }
            RETURN id(m) AS methodId,
                   m.name AS methodName,
                   m.fqn AS methodFqn,
                   (m:Constructor) AS isConstructor,
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
                   rt.fqn AS returnTypeName,
                   paramCount, throwsCount, annotationCount
        """.trimIndent()
    } else {
        """
            MATCH (t:Type)-[:DECLARES]->(m:Method) WHERE id(t) = ${'$'}typeId
            OPTIONAL MATCH (m)-[:RETURNS]->(rt:Type)
            OPTIONAL MATCH (m)-[:HAS]->(p:Parameter)
            OPTIONAL MATCH (m)-[:THROWS]->(ex:Type)
            OPTIONAL MATCH (m)-[:ANNOTATED_BY]->(a)
            RETURN id(m) AS methodId,
                   m.name AS methodName,
                   m.fqn AS methodFqn,
                   (m:Constructor) AS isConstructor,
                   m.visibility AS visibility,
                   m.static AS isStatic,
                   m.final AS isFinal,
                   m.abstract AS isAbstract,
                   m.synchronized AS isSynchronized,
                   m.native AS isNative,
                   m.default AS isDefault,
                   id(t) AS declaringTypeId,
                   t.name AS declaringTypeName,
                   t.fqn AS declaringTypeFqn,
                   labels(t) AS declaringTypeLabels,
                   rt.fqn AS returnTypeName,
                   count(DISTINCT p) AS paramCount,
                   count(DISTINCT ex) AS throwsCount,
                   count(DISTINCT a) AS annotationCount
        """.trimIndent()
    }
}
