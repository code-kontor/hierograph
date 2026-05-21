package org.slizaa.mcp.core;

import org.neo4j.driver.Record;
import org.neo4j.driver.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared base for the detail-level MCP tools (list_methods, list_fields, detail_dependencies,
 * method_details, field_details). Holds Java-specific helpers used by 2+ of these tools so
 * each concrete tool class can focus on its own logic.
 *
 * <p>This class is intentionally not a Spring {@code @Component}; only the concrete subclasses
 * are. Spring sees one bean per tool.</p>
 */
public abstract class AbstractDetailMcpTool extends AbstractGraphMcpTools {

    protected static final Set<String> JAVA_PRIMITIVES = Set.of(
            "void", "boolean", "byte", "char", "short", "int", "long", "float", "double");

    protected AbstractDetailMcpTool(HierarchicalGraphService graphService) {
        super(graphService);
    }

    /** Inline NodeRef used by single-entity tools (e.g. method_details) for primitive type slots. */
    protected Map<String, Object> primitiveRef(String name) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", null);
        ref.put("name", name);
        ref.put("qualified_name", name);
        ref.put("kind", "java.primitive");
        return ref;
    }

    /** Derives a Cartograph-normalized kind string from Neo4j node labels for detail-level entities. */
    protected String deriveDetailKind(List<String> labels) {
        if (labels.contains("Constructor")) return "java.constructor";
        if (labels.contains("Method")) return "java.method";
        if (labels.contains("Field")) return "java.field";
        if (labels.contains("Interface")) return "java.interface";
        if (labels.contains("Enum")) return "java.enum";
        if (labels.contains("Annotation")) return "java.annotation";
        if (labels.contains("Class")) return "java.class";
        if (labels.contains("Type")) return "java.class";
        return "unknown";
    }

    /**
     * Extracts a method's modifiers in canonical order (visibility first, then storage modifiers).
     * Expects the Record to expose {@code visibility, isStatic, isFinal, isAbstract, isSynchronized,
     * isNative, isDefault}.
     */
    protected List<String> extractModifiers(Record record) {
        List<String> modifiers = new ArrayList<>();
        String visibility = record.get("visibility").asString(null);
        modifiers.add(visibility != null ? visibility.toLowerCase() : "package-private");
        if (record.get("isStatic").asBoolean(false)) modifiers.add("static");
        if (record.get("isFinal").asBoolean(false)) modifiers.add("final");
        if (record.get("isAbstract").asBoolean(false)) modifiers.add("abstract");
        if (record.get("isSynchronized").asBoolean(false)) modifiers.add("synchronized");
        if (record.get("isNative").asBoolean(false)) modifiers.add("native");
        if (record.get("isDefault").asBoolean(false)) modifiers.add("default");
        return modifiers;
    }

    /**
     * Extracts a field's modifiers in canonical order (visibility first, then storage modifiers).
     * Expects the Record to expose {@code visibility, isStatic, isFinal, isTransient, isVolatile}.
     */
    protected List<String> extractFieldModifiers(Record record) {
        List<String> modifiers = new ArrayList<>();
        String visibility = record.get("visibility").asString(null);
        modifiers.add(visibility != null ? visibility.toLowerCase() : "package-private");
        if (record.get("isStatic").asBoolean(false)) modifiers.add("static");
        if (record.get("isFinal").asBoolean(false)) modifiers.add("final");
        if (record.get("isTransient").asBoolean(false)) modifiers.add("transient");
        if (record.get("isVolatile").asBoolean(false)) modifiers.add("volatile");
        return modifiers;
    }

    protected String getVisibility(List<String> modifiers) {
        if (modifiers.contains("public")) return "public";
        if (modifiers.contains("protected")) return "protected";
        if (modifiers.contains("private")) return "private";
        return "package-private";
    }

    /** Extracts a list of map values from a Neo4j {@link Value}; returns empty for null. */
    protected List<Map<String, Object>> collectMaps(Value value) {
        if (value == null || value.isNull()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var v : value.values()) {
            out.add(v.asMap());
        }
        return out;
    }

    protected static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return null;
    }

    protected static String asString(Object o) {
        return o == null ? "" : o.toString();
    }
}
