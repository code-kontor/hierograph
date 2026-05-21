package org.slizaa.mcp.core.mcp.detail;

import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider;
import org.slizaa.mcp.core.HierarchicalGraphService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MethodDetailsMcpTool extends AbstractDetailMcpTool {

    public MethodDetailsMcpTool(HierarchicalGraphService graphService) {
        super(graphService);
    }

    @Tool(name = "method_details",
            description = "[Detail-level] Return the full structural details of a single method, in one call. " +
                    "Use this when you've identified a method of interest (via list_methods, detail_dependencies, " +
                    "or another tool that surfaces method IDs) and need the complete picture: modifiers, return type, " +
                    "parameters with names and types, declared exceptions, annotations, the method it overrides " +
                    "(if any), and source location. " +
                    "Response shape: single-entity inline form (no slim 'nodes' wrapper map). Per ADR-0001, " +
                    "single-entity responses with one declaring type don't benefit from slim encoding; inline " +
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
                    "For fields rather than methods, use field_details (uses slim encoding because of read/write digest).")
    public Map<String, Object> methodDetails(
            @ToolParam(description = "The node ID of the method to inspect. Must be a method-kind node " +
                    "(java.method or java.constructor). Typically obtained from list_methods or detail_dependencies.")
            long methodId) {

        INodeMetadataProvider mp = getMetadataProvider();

        String cypher = buildMethodDetailsCypher();
        var queryResult = graphService.getBoltClient().syncExecCypherQuery(
                cypher, Map.of("methodId", methodId));

        var records = queryResult.records();
        if (records.isEmpty()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "NODE_NOT_FOUND");
            error.put("code", "NODE_NOT_FOUND");
            error.put("message", "Node not found: " + methodId + ". Re-resolve via find_node or list_methods.");
            return error;
        }

        Record record = records.get(0);
        List<String> methodLabels = record.get("methodLabels").asList(Value::asString);
        if (!methodLabels.contains("Method")) {
            String actualKind = mp.getKindFromLabels(methodLabels);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "WRONG_NODE_KIND");
            error.put("code", "WRONG_NODE_KIND");
            error.put("message", "Node " + methodId + " is a '" + actualKind + "', not a method. " +
                    "method_details requires a method-kind node.");
            error.put("actual_kind", actualKind);
            return error;
        }

        boolean isConstructor = methodLabels.contains("Constructor");
        String methodName = record.get("methodName").asString("");
        String methodFqn = record.get("methodFqn").asString("");
        long lineNumber = record.get("lineNumber").asLong(-1);

        long declaringTypeId = record.get("declaringTypeId").asLong(-1);
        String declaringTypeName = record.get("declaringTypeName").asString("");
        String declaringTypeFqn = record.get("declaringTypeFqn").asString("");
        List<String> declaringTypeLabels = record.get("declaringTypeLabels").isNull()
                ? List.of()
                : record.get("declaringTypeLabels").asList(Value::asString);
        String declaringTypeKind = mp.getKindFromLabels(declaringTypeLabels);

        List<String> modifiers = extractModifiers(record);

        Map<String, Object> methodRef = new LinkedHashMap<>();
        methodRef.put("id", methodId);
        methodRef.put("name", methodName);
        methodRef.put("qualified_name", methodFqn);
        methodRef.put("kind", isConstructor ? "java.constructor" : "java.method");
        methodRef.put("parent_id", declaringTypeId);
        methodRef.put("parent_kind", declaringTypeKind);

        Map<String, Object> declaringTypeRef = new LinkedHashMap<>();
        declaringTypeRef.put("id", declaringTypeId);
        declaringTypeRef.put("name", declaringTypeName);
        declaringTypeRef.put("qualified_name", declaringTypeFqn);
        declaringTypeRef.put("kind", declaringTypeKind);

        Map<String, Object> returnTypeRef;
        if (record.get("returnTypeId").isNull()) {
            if (isConstructor) {
                returnTypeRef = declaringTypeRef;
            } else {
                returnTypeRef = primitiveRef("void");
            }
        } else {
            long rtId = record.get("returnTypeId").asLong();
            String rtName = record.get("returnTypeName").asString("");
            String rtFqn = record.get("returnTypeFqn").asString("");
            List<String> rtLabels = record.get("returnTypeLabels").asList(Value::asString);
            returnTypeRef = toTypeRef(rtId, rtName, rtFqn, rtLabels, mp);
        }

        List<Map<String, Object>> parameters = buildParameters(record, mp);
        List<Map<String, Object>> throwsList = buildTypeRefList(record.get("throwsList"), mp);
        List<Map<String, Object>> methodAnnotations = buildAnnotationList(record.get("methodAnnotations"), mp);

        Map<String, Object> overridesRef = null;
        if (!record.get("overrideId").isNull()) {
            long ovId = record.get("overrideId").asLong();
            String ovName = record.get("overrideName").asString("");
            String ovFqn = record.get("overrideFqn").asString("");
            List<String> ovLabels = record.get("overrideLabels").asList(Value::asString);
            boolean ovIsCtor = ovLabels.contains("Constructor");
            long ovDtId = record.get("overrideDeclTypeId").asLong(-1);
            List<String> ovDtLabels = record.get("overrideDeclTypeLabels").isNull()
                    ? List.of()
                    : record.get("overrideDeclTypeLabels").asList(Value::asString);

            overridesRef = new LinkedHashMap<>();
            overridesRef.put("id", ovId);
            overridesRef.put("name", ovName);
            overridesRef.put("qualified_name", ovFqn);
            overridesRef.put("kind", ovIsCtor ? "java.constructor" : "java.method");
            overridesRef.put("parent_id", ovDtId);
            overridesRef.put("parent_kind", mp.getKindFromLabels(ovDtLabels));
        }

        Map<String, Object> location = null;
        if (lineNumber > 0) {
            location = new LinkedHashMap<>();
            location.put("line_number", lineNumber);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", methodRef);
        result.put("declaring_type", declaringTypeRef);
        result.put("modifiers", modifiers);
        result.put("is_constructor", isConstructor);
        result.put("return_type", returnTypeRef);
        result.put("parameters", parameters);
        result.put("throws", throwsList);
        result.put("annotations", methodAnnotations);
        result.put("overrides", overridesRef);
        result.put("location", location);
        return result;
    }

    private String buildMethodDetailsCypher() {
        return """
                MATCH (m) WHERE id(m) = $methodId
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
                """;
    }

    private List<Map<String, Object>> buildParameters(Record record, INodeMetadataProvider mp) {
        var paramsValue = record.get("parameters");
        if (paramsValue.isNull()) return List.of();

        List<Map<String, Object>> raw = new ArrayList<>();
        for (var v : paramsValue.values()) {
            raw.add(v.asMap());
        }

        raw.sort(Comparator.comparingLong(m -> {
            Object idx = m.get("index");
            return idx instanceof Number n ? n.longValue() : 0L;
        }));

        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> p = raw.get(i);
            Map<String, Object> paramEntry = new LinkedHashMap<>();
            Object idx = p.get("index");
            long position = idx instanceof Number n ? n.longValue() : i;
            paramEntry.put("position", position);
            paramEntry.put("name", p.get("name"));

            Long ptId = asLong(p.get("paramTypeId"));
            String ptName = (String) p.get("paramTypeName");
            String ptFqn = (String) p.get("paramTypeFqn");
            @SuppressWarnings("unchecked")
            List<String> ptLabels = (List<String>) p.get("paramTypeLabels");
            paramEntry.put("type", toTypeRef(ptId, ptName, ptFqn, ptLabels, mp));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> annsRaw = (List<Map<String, Object>>) p.get("annotations");
            paramEntry.put("annotations", buildAnnotationListFromMaps(annsRaw, mp));

            out.add(paramEntry);
        }
        return out;
    }

    private List<Map<String, Object>> buildTypeRefList(Value value, INodeMetadataProvider mp) {
        if (value.isNull()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var v : value.values()) {
            Map<String, Object> m = v.asMap();
            Long id = asLong(m.get("id"));
            if (id == null) continue;
            String name = (String) m.get("name");
            String fqn = (String) m.get("fqn");
            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) m.get("labels");
            out.add(toTypeRef(id, name, fqn, labels, mp));
        }
        return out;
    }

    private List<Map<String, Object>> buildAnnotationList(Value value, INodeMetadataProvider mp) {
        if (value.isNull()) return List.of();
        List<Map<String, Object>> raw = new ArrayList<>();
        for (var v : value.values()) {
            raw.add(v.asMap());
        }
        return buildAnnotationListFromMaps(raw, mp);
    }

    private List<Map<String, Object>> buildAnnotationListFromMaps(List<Map<String, Object>> raw, INodeMetadataProvider mp) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            Long id = asLong(m.get("id"));
            if (id == null) continue;
            String name = (String) m.get("name");
            String fqn = (String) m.get("fqn");
            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) m.get("labels");
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", toTypeRef(id, name, fqn, labels, mp));
            out.add(wrapper);
        }
        return out;
    }

    private Map<String, Object> toTypeRef(Long id, String name, String fqn, List<String> labels, INodeMetadataProvider mp) {
        if (fqn != null && JAVA_PRIMITIVES.contains(fqn)) {
            return primitiveRef(fqn);
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", id);
        ref.put("name", name != null ? name : "");
        ref.put("qualified_name", fqn != null ? fqn : "");
        ref.put("kind", mp.getKindFromLabels(labels != null ? labels : List.of()));
        return ref;
    }
}
