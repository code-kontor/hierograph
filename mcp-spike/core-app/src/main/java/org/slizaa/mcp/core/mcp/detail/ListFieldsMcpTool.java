package org.slizaa.mcp.core.mcp.detail;

import org.neo4j.driver.Record;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider;
import org.slizaa.mcp.javaspec.JavaKinds;
import org.slizaa.mcp.core.HierarchicalGraphService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ListFieldsMcpTool extends AbstractDetailMcpTool {

    public ListFieldsMcpTool(HierarchicalGraphService graphService) {
        super(graphService);
    }

    @Tool(name = "list_fields",
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
                    "For methods rather than fields, use list_methods (same shape, different entity).")
    public Map<String, Object> listFields(
            @ToolParam(description = "The node ID of the type whose fields should be enumerated. " +
                    "Must be a type-kind node (Class, Interface, Enum, Annotation, Record).") long typeId,
            @ToolParam(description = "Optional case-insensitive substring match against the field name.",
                    required = false) String namePattern,
            @ToolParam(description = "Optional list of Java modifiers, ANDed together. " +
                    "Allowed values: public, protected, private, package-private, static, final, transient, volatile.",
                    required = false) List<String> modifierFilter,
            @ToolParam(description = "Whether to include inherited fields from superclasses. Default false.",
                    required = false) Boolean includeInherited,
            @ToolParam(description = "Max fields to return (1-500, default 50).", required = false) Integer limit) {

        Set<String> allowedModifiers = Set.of("public", "protected", "private", "package-private",
                "static", "final", "transient", "volatile");
        if (modifierFilter != null) {
            for (String mod : modifierFilter) {
                if (!allowedModifiers.contains(mod)) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", "INVALID_MODIFIER");
                    error.put("message", "Invalid modifier: '" + mod + "'. Allowed values for fields: " + allowedModifiers);
                    error.put("invalid_value", mod);
                    return error;
                }
            }
        }

        HGNode typeNode = graphService.getRootNode().lookupNode(typeId);
        if (typeNode == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "NODE_NOT_FOUND");
            error.put("message", "Node not found: " + typeId + ". Re-resolve via find_node.");
            return error;
        }

        INodeMetadataProvider mp = getMetadataProvider();
        String kind = mp.getKind(typeNode);
        Set<String> typeKinds = Set.of("Class", "Interface", "Enum", "Annotation", "Record");
        if (!typeKinds.contains(kind)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "WRONG_NODE_KIND");
            error.put("message", "Node " + typeId + " is a '" + kind + "', not a type. " +
                    "list_fields requires a Class, Interface, Enum, Annotation, or Record.");
            error.put("actual_kind", kind);
            return error;
        }

        boolean inherited = includeInherited != null && includeInherited;
        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 500) : 50;

        String cypher = buildListFieldsCypher(inherited);
        var queryResult = graphService.getBoltClient().syncExecCypherQuery(
                cypher, Map.of("typeId", typeId));

        List<Map<String, Object>> allFields = new ArrayList<>();
        Map<Long, String[]> nodeDisplay = new LinkedHashMap<>();
        int totalPublic = 0, totalProtected = 0, totalPrivate = 0, totalPackagePrivate = 0;
        int totalAnnotated = 0, totalStatic = 0, totalFinal = 0, totalConstant = 0;
        int totalDeclared = 0, totalInherited = 0;

        for (Record record : queryResult.records()) {
            long fieldId = record.get("fieldId").asLong();
            String fieldName = record.get("fieldName").asString("");
            String fieldFqn = record.get("fieldFqn").asString("");
            long declaringTypeId = record.get("declaringTypeId").asLong();
            String declaringTypeName = record.get("declaringTypeName").asString("");
            String declaringTypeFqn = record.get("declaringTypeFqn").asString("");
            List<String> declaringTypeLabels = record.get("declaringTypeLabels").asList(org.neo4j.driver.Value::asString);
            String fieldTypeName = record.get("fieldTypeName").isNull() ? "unknown" : record.get("fieldTypeName").asString("unknown");
            long annotationCount = record.get("annotationCount").asLong(0);

            List<String> modifiers = extractFieldModifiers(record);
            String visibility = getVisibility(modifiers);

            if (namePattern != null && !namePattern.isBlank()) {
                if (!fieldName.toLowerCase().contains(namePattern.toLowerCase())) continue;
            }

            if (modifierFilter != null && !modifierFilter.isEmpty()) {
                boolean allMatch = true;
                for (String requiredMod : modifierFilter) {
                    if (requiredMod.equals("package-private")) {
                        if (!visibility.equals("package-private")) { allMatch = false; break; }
                    } else if (!modifiers.contains(requiredMod)) {
                        allMatch = false; break;
                    }
                }
                if (!allMatch) continue;
            }

            boolean isConstant = modifiers.contains("static") && modifiers.contains("final");
            boolean isInherited = declaringTypeId != typeId;
            if (isInherited) totalInherited++; else totalDeclared++;
            switch (visibility) {
                case "public" -> totalPublic++;
                case "protected" -> totalProtected++;
                case "private" -> totalPrivate++;
                case "package-private" -> totalPackagePrivate++;
            }
            if (annotationCount > 0) totalAnnotated++;
            if (modifiers.contains("static")) totalStatic++;
            if (modifiers.contains("final")) totalFinal++;
            if (isConstant) totalConstant++;

            Map<String, Object> fieldEntry = new LinkedHashMap<>();
            fieldEntry.put("node", fieldId);
            fieldEntry.put("parent", declaringTypeId);
            fieldEntry.put("modifiers", modifiers);
            fieldEntry.put("field_type_name", fieldTypeName);
            fieldEntry.put("annotation_count", annotationCount);
            fieldEntry.put("is_constant", isConstant);
            fieldEntry.put("is_inherited", isInherited);
            allFields.add(fieldEntry);

            nodeDisplay.putIfAbsent(fieldId, new String[]{fieldName, fieldFqn, JavaKinds.FIELD});
            nodeDisplay.putIfAbsent(declaringTypeId, new String[]{
                    declaringTypeName, declaringTypeFqn, mp.getKindFromLabels(declaringTypeLabels)});
        }

        int totalMatching = allFields.size();
        boolean truncated = totalMatching > effectiveLimit;
        List<Map<String, Object>> returnedFields = allFields.stream()
                .limit(effectiveLimit)
                .toList();

        Map<String, Object> nodes = new LinkedHashMap<>();
        putSlimNode(nodes, typeId, mp.getName(typeNode), mp.getQualifiedName(typeNode), kind);
        for (Map<String, Object> entry : returnedFields) {
            long fieldId = (long) entry.get("node");
            long parentTypeId = (long) entry.get("parent");
            String[] tDisp = nodeDisplay.get(parentTypeId);
            String[] fDisp = nodeDisplay.get(fieldId);
            if (tDisp != null) putSlimNode(nodes, parentTypeId, tDisp[0], tDisp[1], tDisp[2]);
            if (fDisp != null) putSlimNode(nodes, fieldId, fDisp[0], fDisp[1], fDisp[2]);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_matching", totalMatching);
        summary.put("returned", returnedFields.size());
        summary.put("truncated", truncated);
        summary.put("declared_count", totalDeclared);
        summary.put("inherited_count", totalInherited);
        Map<String, Object> byVisibility = new LinkedHashMap<>();
        byVisibility.put("public", totalPublic);
        byVisibility.put("protected", totalProtected);
        byVisibility.put("private", totalPrivate);
        byVisibility.put("package-private", totalPackagePrivate);
        summary.put("by_visibility", byVisibility);
        summary.put("annotated_count", totalAnnotated);
        summary.put("static_count", totalStatic);
        summary.put("final_count", totalFinal);
        summary.put("constant_count", totalConstant);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("type", typeId);
        result.put("fields", returnedFields);
        result.put("summary", summary);
        return result;
    }

    private String buildListFieldsCypher(boolean includeInherited) {
        if (includeInherited) {
            return """
                MATCH (t:Type) WHERE id(t) = $typeId
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
                """;
        } else {
            return """
                MATCH (t:Type)-[:DECLARES]->(f:Field) WHERE id(t) = $typeId
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
                """;
        }
    }
}
