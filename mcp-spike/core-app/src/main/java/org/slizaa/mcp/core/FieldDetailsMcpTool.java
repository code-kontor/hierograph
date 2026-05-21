package org.slizaa.mcp.core;

import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class FieldDetailsMcpTool extends AbstractDetailMcpTool {

    public FieldDetailsMcpTool(HierarchicalGraphService graphService) {
        super(graphService);
    }

    @Tool(name = "field_details",
            description = "[Detail-level] Return the full structural details of a single field, in one call. " +
                    "Use this when you've identified a field of interest (via list_fields, detail_dependencies, " +
                    "or another tool that surfaces field IDs) and need the complete picture: type, annotations, and " +
                    "information about which methods read or write it. " +
                    "Response shape (slim encoding, ADR-0001): top-level 'nodes' map (each referenced node listed once " +
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
                    "For methods rather than fields, use method_details (parallel tool, but inline NodeRefs since " +
                    "method_details is a single-entity response per ADR-0001).")
    public Map<String, Object> fieldDetails(
            @ToolParam(description = "The node ID of the field to inspect. Must be a field-kind node (java.field). " +
                    "Typically obtained from list_fields or detail_dependencies.")
            long fieldId) {

        INodeMetadataProvider mp = getMetadataProvider();

        String cypher = buildFieldDetailsCypher();
        var queryResult = graphService.getBoltClient().syncExecCypherQuery(
                cypher, Map.of("fieldId", fieldId));

        var records = queryResult.records();
        if (records.isEmpty()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "NODE_NOT_FOUND");
            error.put("code", "NODE_NOT_FOUND");
            error.put("message", "Node not found: " + fieldId + ". Re-resolve via find_node or list_fields.");
            return error;
        }

        Record record = records.get(0);
        List<String> fieldLabels = record.get("fieldLabels").asList(Value::asString);
        if (!fieldLabels.contains("Field")) {
            String actualKind = mp.getKindFromLabels(fieldLabels);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "WRONG_NODE_KIND");
            error.put("code", "WRONG_NODE_KIND");
            error.put("message", "Node " + fieldId + " is a '" + actualKind + "', not a field. " +
                    "field_details requires a field-kind node.");
            error.put("actual_kind", actualKind);
            return error;
        }

        String fieldName = record.get("fieldName").asString("");
        String fieldFqn = record.get("fieldFqn").asString("");
        long lineNumber = record.get("lineNumber").asLong(-1);

        long declaringTypeId = record.get("declaringTypeId").asLong(-1);
        String declaringTypeName = record.get("declaringTypeName").asString("");
        String declaringTypeFqn = record.get("declaringTypeFqn").asString("");
        List<String> declaringTypeLabels = record.get("declaringTypeLabels").isNull()
                ? List.of()
                : record.get("declaringTypeLabels").asList(Value::asString);
        String declaringTypeKind = mp.getKindFromLabels(declaringTypeLabels);

        List<String> modifiers = extractFieldModifiers(record);
        boolean isConstant = modifiers.contains("static") && modifiers.contains("final");

        Long fieldTypeId = record.get("fieldTypeId").isNull() ? null : record.get("fieldTypeId").asLong();
        String fieldTypeFqn = record.get("fieldTypeFqn").asString(null);
        String fieldTypeName = record.get("fieldTypeName").asString(null);
        List<String> fieldTypeLabels = record.get("fieldTypeLabels").isNull()
                ? List.of()
                : record.get("fieldTypeLabels").asList(Value::asString);

        Long typeIdForResponse;
        String typeNameForResponse;
        if (fieldTypeFqn != null && JAVA_PRIMITIVES.contains(fieldTypeFqn)) {
            typeIdForResponse = null;
            typeNameForResponse = fieldTypeFqn;
        } else if (fieldTypeId != null) {
            typeIdForResponse = fieldTypeId;
            typeNameForResponse = fieldTypeFqn != null ? fieldTypeFqn : (fieldTypeName != null ? fieldTypeName : "unknown");
        } else {
            typeIdForResponse = null;
            typeNameForResponse = "unknown";
        }

        List<Map<String, Object>> rawAnnotations = collectMaps(record.get("annotations"));
        List<Map<String, Object>> annotations = new ArrayList<>();
        Map<Long, String[]> annotationDisplay = new LinkedHashMap<>();
        for (Map<String, Object> a : rawAnnotations) {
            Long aid = asLong(a.get("id"));
            if (aid == null) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", aid);
            annotations.add(entry);
            @SuppressWarnings("unchecked")
            List<String> aLabels = (List<String>) a.get("labels");
            annotationDisplay.put(aid, new String[]{
                    asString(a.get("name")), asString(a.get("fqn")), mp.getKindFromLabels(aLabels != null ? aLabels : List.of())
            });
        }

        Map<String, Object> nodes = new LinkedHashMap<>();
        putSlimNode(nodes, fieldId, fieldName, fieldFqn, "java.field");
        putSlimNode(nodes, declaringTypeId, declaringTypeName, declaringTypeFqn, declaringTypeKind);
        if (typeIdForResponse != null) {
            putSlimNode(nodes, typeIdForResponse,
                    fieldTypeName != null ? fieldTypeName : "",
                    fieldTypeFqn != null ? fieldTypeFqn : "",
                    mp.getKindFromLabels(fieldTypeLabels));
        }
        for (Map.Entry<Long, String[]> e : annotationDisplay.entrySet()) {
            String[] d = e.getValue();
            putSlimNode(nodes, e.getKey(), d[0], d[1], d[2]);
        }

        long readCount = record.get("readCount").asLong(0);
        List<Map<String, Object>> rawReaders = collectMaps(record.get("readers"));
        Map<String, Object> readAccess = buildAccessDigest(rawReaders, mp, nodes);
        readAccess.put("method_count", readCount);

        long writeCount = record.get("writeCount").asLong(0);
        List<Map<String, Object>> rawWriters = collectMaps(record.get("writers"));
        Map<String, Object> writeAccess = buildAccessDigest(rawWriters, mp, nodes);
        writeAccess.put("method_count", writeCount);

        Map<String, Object> location = null;
        if (lineNumber > 0) {
            location = new LinkedHashMap<>();
            location.put("line_number", lineNumber);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("field", fieldId);
        result.put("declaring_type", declaringTypeId);
        result.put("modifiers", modifiers);
        result.put("is_constant", isConstant);
        result.put("type", typeIdForResponse);
        result.put("type_name", typeNameForResponse);
        result.put("annotations", annotations);
        result.put("read_access", readAccess);
        result.put("write_access", writeAccess);
        result.put("location", location);
        return result;
    }

    private String buildFieldDetailsCypher() {
        return """
                MATCH (f) WHERE id(f) = $fieldId
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
                """;
    }

    /**
     * Builds an access digest (read_access or write_access) from the raw list of method maps
     * collected from Cypher and registers the referenced method/declaring-type nodes directly
     * into the supplied slim {@code nodes} map.
     *
     * <p>Sample is sorted by method qualified name for deterministic output across runs
     * (Cypher's COLLECT order is unspecified). The top-10 cap on {@code by_declaring_type}
     * surfaces {@code others_count} when more types contributed than fit.</p>
     */
    private Map<String, Object> buildAccessDigest(
            List<Map<String, Object>> rawMethods,
            INodeMetadataProvider mp,
            Map<String, Object> nodes) {

        // Drop the all-null entry that OPTIONAL MATCH + collect produces when nothing matched.
        List<Map<String, Object>> methods = new ArrayList<>();
        for (Map<String, Object> m : rawMethods) {
            if (asLong(m.get("id")) != null) methods.add(m);
        }

        // Stable order: by method qualified name (Cypher COLLECT order isn't guaranteed).
        methods.sort(Comparator.comparing(m -> asString(m.get("fqn"))));

        int sampleSize = Math.min(10, methods.size());
        List<Long> sampleIds = new ArrayList<>(sampleSize);
        Set<Long> sampleSet = new HashSet<>();
        for (int i = 0; i < sampleSize; i++) {
            Long id = asLong(methods.get(i).get("id"));
            sampleIds.add(id);
            sampleSet.add(id);
        }
        boolean sampleTruncated = methods.size() > sampleIds.size();

        Map<Long, Integer> declarerCounts = new LinkedHashMap<>();
        for (Map<String, Object> m : methods) {
            Long declarerId = asLong(m.get("declarerId"));
            if (declarerId == null) continue;
            declarerCounts.merge(declarerId, 1, Integer::sum);
        }
        int totalDeclarers = declarerCounts.size();
        List<Map<String, Object>> byDeclaringType = declarerCounts.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("type", e.getKey());
                    entry.put("count", e.getValue());
                    return entry;
                })
                .toList();

        // Register node display fields directly into the slim nodes map:
        // methods in the sample plus ALL declaring types contributing to by_declaring_type
        // (not just the sample's declarers).
        for (Map<String, Object> m : methods) {
            Long mid = asLong(m.get("id"));
            if (mid != null && sampleSet.contains(mid)) {
                @SuppressWarnings("unchecked")
                List<String> labels = (List<String>) m.get("labels");
                putSlimNode(nodes, mid,
                        asString(m.get("name")), asString(m.get("fqn")),
                        deriveDetailKind(labels != null ? labels : List.of()));
            }
            Long declarerId = asLong(m.get("declarerId"));
            if (declarerId != null) {
                @SuppressWarnings("unchecked")
                List<String> declarerLabels = (List<String>) m.get("declarerLabels");
                putSlimNode(nodes, declarerId,
                        asString(m.get("declarerName")), asString(m.get("declarerFqn")),
                        mp.getKindFromLabels(declarerLabels != null ? declarerLabels : List.of()));
            }
        }

        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("method_count", methods.size());  // overwritten by caller with Cypher count
        digest.put("methods_sample", sampleIds);
        digest.put("sample_truncated", sampleTruncated);
        digest.put("by_declaring_type", byDeclaringType);
        if (totalDeclarers > byDeclaringType.size()) {
            digest.put("others_count", totalDeclarers - byDeclaringType.size());
        }
        return digest;
    }
}
