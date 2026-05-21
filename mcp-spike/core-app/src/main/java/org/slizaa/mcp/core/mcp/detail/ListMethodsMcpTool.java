package org.slizaa.mcp.core.mcp.detail;

import org.neo4j.driver.Record;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider;
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
public class ListMethodsMcpTool extends AbstractDetailMcpTool {

    public ListMethodsMcpTool(HierarchicalGraphService graphService) {
        super(graphService);
    }

    @Tool(name = "list_methods",
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
                    "For 'which methods call this one?' or dependency-driven views, use detail_dependencies.")
    public Map<String, Object> listMethods(
            @ToolParam(description = "The node ID of the type whose methods should be enumerated. " +
                    "Must be a type-kind node (Class, Interface, Enum, Annotation, Record).") long typeId,
            @ToolParam(description = "Optional case-insensitive substring match against the method name.",
                    required = false) String namePattern,
            @ToolParam(description = "Optional list of Java modifiers, ANDed together. " +
                    "Allowed values: public, protected, private, package-private, static, final, abstract, synchronized, native, default.",
                    required = false) List<String> modifierFilter,
            @ToolParam(description = "Whether to include inherited methods from superclasses and interfaces. Default false.",
                    required = false) Boolean includeInherited,
            @ToolParam(description = "Max methods to return (1-500, default 50).", required = false) Integer limit) {

        Set<String> allowedModifiers = Set.of("public", "protected", "private", "package-private",
                "static", "final", "abstract", "synchronized", "native", "default");
        if (modifierFilter != null) {
            for (String mod : modifierFilter) {
                if (!allowedModifiers.contains(mod)) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", "INVALID_MODIFIER");
                    error.put("message", "Invalid modifier: '" + mod + "'. Allowed values: " + allowedModifiers);
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
                    "list_methods requires a Class, Interface, Enum, Annotation, or Record.");
            error.put("actual_kind", kind);
            return error;
        }

        boolean inherited = includeInherited != null && includeInherited;
        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 500) : 50;

        String cypher = buildListMethodsCypher(inherited);

        var queryResult = graphService.getBoltClient().syncExecCypherQuery(
                cypher, Map.of("typeId", typeId));

        List<Map<String, Object>> allMethods = new ArrayList<>();
        Map<Long, String[]> nodeDisplay = new LinkedHashMap<>();
        int totalPublic = 0, totalProtected = 0, totalPrivate = 0, totalPackagePrivate = 0;
        int totalConstructors = 0, totalAbstract = 0;
        int totalDeclared = 0, totalInherited = 0;

        for (Record record : queryResult.records()) {
            long methodId = record.get("methodId").asLong();
            String methodName = record.get("methodName").asString("");
            String methodFqn = record.get("methodFqn").asString("");
            boolean isConstructor = record.get("isConstructor").asBoolean(false);
            long declaringTypeId = record.get("declaringTypeId").asLong();
            String declaringTypeName = record.get("declaringTypeName").asString("");
            String declaringTypeFqn = record.get("declaringTypeFqn").asString("");
            List<String> declaringTypeLabels = record.get("declaringTypeLabels").asList(org.neo4j.driver.Value::asString);
            String returnTypeName = record.get("returnTypeName").isNull() ? "void" : record.get("returnTypeName").asString("void");
            long paramCount = record.get("paramCount").asLong(0);
            long throwsCount = record.get("throwsCount").asLong(0);
            long annotationCount = record.get("annotationCount").asLong(0);

            List<String> modifiers = extractModifiers(record);
            String visibility = getVisibility(modifiers);

            if (namePattern != null && !namePattern.isBlank()) {
                if (!methodName.toLowerCase().contains(namePattern.toLowerCase())) continue;
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

            boolean isInherited = declaringTypeId != typeId;
            if (isInherited) totalInherited++; else totalDeclared++;
            switch (visibility) {
                case "public" -> totalPublic++;
                case "protected" -> totalProtected++;
                case "private" -> totalPrivate++;
                case "package-private" -> totalPackagePrivate++;
            }
            if (isConstructor) totalConstructors++;
            if (modifiers.contains("abstract")) totalAbstract++;

            Map<String, Object> methodEntry = new LinkedHashMap<>();
            methodEntry.put("node", methodId);
            methodEntry.put("parent", declaringTypeId);
            methodEntry.put("modifiers", modifiers);
            methodEntry.put("return_type_name", returnTypeName);
            methodEntry.put("parameter_count", paramCount);
            methodEntry.put("throws_count", throwsCount);
            methodEntry.put("annotation_count", annotationCount);
            methodEntry.put("is_constructor", isConstructor);
            methodEntry.put("is_inherited", isInherited);
            allMethods.add(methodEntry);

            nodeDisplay.putIfAbsent(methodId, new String[]{
                    methodName, methodFqn, isConstructor ? "java.constructor" : "java.method"});
            nodeDisplay.putIfAbsent(declaringTypeId, new String[]{
                    declaringTypeName, declaringTypeFqn, mp.getKindFromLabels(declaringTypeLabels)});
        }

        int totalMatching = allMethods.size();
        boolean truncated = totalMatching > effectiveLimit;
        List<Map<String, Object>> returnedMethods = allMethods.stream()
                .limit(effectiveLimit)
                .toList();

        Map<String, Object> nodes = new LinkedHashMap<>();
        putSlimNode(nodes, typeId, mp.getName(typeNode), mp.getQualifiedName(typeNode), kind);
        for (Map<String, Object> entry : returnedMethods) {
            long methodId = (long) entry.get("node");
            long parentTypeId = (long) entry.get("parent");
            String[] tDisp = nodeDisplay.get(parentTypeId);
            String[] mDisp = nodeDisplay.get(methodId);
            if (tDisp != null) putSlimNode(nodes, parentTypeId, tDisp[0], tDisp[1], tDisp[2]);
            if (mDisp != null) putSlimNode(nodes, methodId, mDisp[0], mDisp[1], mDisp[2]);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_matching", totalMatching);
        summary.put("returned", returnedMethods.size());
        summary.put("truncated", truncated);
        summary.put("declared_count", totalDeclared);
        summary.put("inherited_count", totalInherited);
        Map<String, Object> byVisibility = new LinkedHashMap<>();
        byVisibility.put("public", totalPublic);
        byVisibility.put("protected", totalProtected);
        byVisibility.put("private", totalPrivate);
        byVisibility.put("package-private", totalPackagePrivate);
        summary.put("by_visibility", byVisibility);
        summary.put("constructors", totalConstructors);
        summary.put("abstract_methods", totalAbstract);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("type", typeId);
        result.put("methods", returnedMethods);
        result.put("summary", summary);
        return result;
    }

    private String buildListMethodsCypher(boolean includeInherited) {
        if (includeInherited) {
            return """
                MATCH (t:Type) WHERE id(t) = $typeId
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
                """;
        } else {
            return """
                MATCH (t:Type)-[:DECLARES]->(m:Method) WHERE id(t) = $typeId
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
                """;
        }
    }
}
