package org.slizaa.mcp.core.mcp.detail

import org.neo4j.driver.Record
import org.neo4j.driver.Value
import org.slizaa.hierarchicalgraph.core.model.HGNode
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import org.slizaa.mcp.javaspec.JavaKinds
import org.slizaa.mcp.core.HierarchicalGraphService

/**
 * Shared base for the detail-level MCP tools (list_methods, list_fields, detail_dependencies,
 * method_details, field_details). Holds Java-specific helpers used by 2+ of these tools so
 * each concrete tool class can focus on its own logic.
 *
 * This class is intentionally not a Spring `@Component`; only the concrete subclasses
 * are. Spring sees one bean per tool.
 */
abstract class AbstractDetailTool(
    protected val graphService: HierarchicalGraphService
) {

    protected fun getMetadataProvider(): INodeMetadataProvider =
        graphService.rootNode.getExtension(INodeMetadataProvider::class.java)

    protected fun putSlimNode(nodes: MutableMap<String, Any>, id: Long, name: String?, fqn: String?, kind: String?) {
        val key = id.toString()
        if (nodes.containsKey(key)) return
        nodes[key] = linkedMapOf(
            "name" to (name ?: ""),
            "qualified_name" to (fqn ?: ""),
            "kind" to (kind ?: "unknown")
        )
    }

    protected fun putSlimNode(nodes: MutableMap<String, Any>, node: HGNode) {
        val mp = getMetadataProvider()
        val id = (node.identifier as? Number)?.toLong() ?: 0L
        putSlimNode(nodes, id, mp.getName(node), mp.getQualifiedName(node), mp.getKind(node))
    }

    companion object {
        @JvmStatic
        protected val JAVA_PRIMITIVES: Set<String> = JavaKinds.JAVA_PRIMITIVES

        @JvmStatic
        protected fun asLong(o: Any?): Long? = (o as? Number)?.toLong()

        @JvmStatic
        protected fun asString(o: Any?): String = o?.toString() ?: ""
    }

    /** Inline NodeRef used by single-entity tools (e.g. method_details) for primitive type slots. */
    protected fun primitiveRef(name: String): Map<String, Any?> = linkedMapOf(
        "id" to null,
        "name" to name,
        "qualified_name" to name,
        "kind" to JavaKinds.PRIMITIVE.value
    )

    /** Derives a Hierograph-normalized kind string from Neo4j node labels for detail-level entities. */
    protected fun deriveDetailKind(labels: List<String>): String = when {
        "Constructor" in labels -> JavaKinds.CONSTRUCTOR.value
        "Method" in labels -> JavaKinds.METHOD.value
        "Field" in labels -> JavaKinds.FIELD.value
        "Interface" in labels -> JavaKinds.INTERFACE.value
        "Enum" in labels -> JavaKinds.ENUM.value
        "Annotation" in labels -> JavaKinds.ANNOTATION.value
        "Class" in labels -> JavaKinds.CLASS.value
        "Type" in labels -> JavaKinds.CLASS.value
        else -> "unknown"
    }

    /**
     * Extracts a method's modifiers in canonical order (visibility first, then storage modifiers).
     * Expects the Record to expose `visibility, isStatic, isFinal, isAbstract, isSynchronized,
     * isNative, isDefault`.
     */
    protected fun extractModifiers(record: Record): List<String> = buildList {
        val visibility = record.get("visibility").asString(null)
        add(visibility?.lowercase() ?: "package-private")
        if (record.get("isStatic").asBoolean(false)) add("static")
        if (record.get("isFinal").asBoolean(false)) add("final")
        if (record.get("isAbstract").asBoolean(false)) add("abstract")
        if (record.get("isSynchronized").asBoolean(false)) add("synchronized")
        if (record.get("isNative").asBoolean(false)) add("native")
        if (record.get("isDefault").asBoolean(false)) add("default")
    }

    /**
     * Extracts a field's modifiers in canonical order (visibility first, then storage modifiers).
     * Expects the Record to expose `visibility, isStatic, isFinal, isTransient, isVolatile`.
     */
    protected fun extractFieldModifiers(record: Record): List<String> = buildList {
        val visibility = record.get("visibility").asString(null)
        add(visibility?.lowercase() ?: "package-private")
        if (record.get("isStatic").asBoolean(false)) add("static")
        if (record.get("isFinal").asBoolean(false)) add("final")
        if (record.get("isTransient").asBoolean(false)) add("transient")
        if (record.get("isVolatile").asBoolean(false)) add("volatile")
    }

    protected fun getVisibility(modifiers: List<String>): String = when {
        modifiers.contains("public") -> "public"
        modifiers.contains("protected") -> "protected"
        modifiers.contains("private") -> "private"
        else -> "package-private"
    }

    /** Extracts a list of map values from a Neo4j [Value]; returns empty for null. */
    protected fun collectMaps(value: Value?): List<Map<String, Any>> {
        if (value == null || value.isNull) return emptyList()
        return value.values().map { it.asMap() }
    }
}
