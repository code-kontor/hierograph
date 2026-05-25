package io.hierograph.mcp.jqa.hierarchicalgraph

import org.slizaa.core.boltclient.IBoltClient
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ISearchProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.SearchResult
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.javaspec.JavaNodeKind

/**
 * jQAssistant-specific [ISearchProvider] implementation.
 *
 * Translates Hierograph's namespaced kind vocabulary to jQAssistant Neo4j labels,
 * builds a Cypher query with match-quality ordering, and maps results back to
 * Hierograph kinds. All Cypher and label knowledge is confined to this class.
 */
class JQAssistantSearchProvider(
    private val boltClient: IBoltClient,
    private val metadataProvider: INodeMetadataProvider
) : ISearchProvider {

    /** Hierograph kind -> jQAssistant Neo4j label(s). */
    private val kindToLabels = mapOf(
        JavaKinds.MODULE to listOf("Artifact"),
        JavaKinds.PACKAGE to listOf("Package"),
        JavaKinds.CLASS to listOf("Class"),
        JavaKinds.INTERFACE to listOf("Interface"),
        JavaKinds.ENUM to listOf("Enum"),
        JavaKinds.RECORD to listOf("Record"),
        JavaKinds.ANNOTATION to listOf("Annotation"),
        JavaKinds.METHOD to listOf("Method"),
        JavaKinds.FIELD to listOf("Field")
    )

    override fun search(name: String, kindFilter: List<String>?, limit: Int): List<SearchResult> {
        val resolvedLabels = resolveKindFilter(kindFilter)
        val cypher = buildCypher(resolvedLabels, limit)

        val result = boltClient.syncExecCypherQuery(cypher, mapOf<String, Any>("query" to name))

        return result.records().map { record ->
            val labels = record.get("labels").asList { it.asString() }
            SearchResult(
                record.get("nodeId").asLong(),
                record.get("name").asString(""),
                record.get("fqn").asString(""),
                metadataProvider.getKindFromLabels(labels)
            )
        }
    }

    private fun buildCypher(labelFilter: Set<String>?, limit: Int): String = buildString {
        if (labelFilter != null && labelFilter.isNotEmpty()) {
            val conditions = labelFilter.joinToString(" OR ") { "n:$it" }
            append("MATCH (n) WHERE ($conditions) ")
        } else {
            append("MATCH (n) WHERE (n:Type OR n:Package OR n:Artifact) ")
        }

        append("AND (toLower(n.name) CONTAINS toLower(\$query) OR toLower(n.fqn) CONTAINS toLower(\$query)) ")
        append("RETURN id(n) AS nodeId, n.name AS name, n.fqn AS fqn, labels(n) AS labels ")
        append("ORDER BY ")
        append("CASE ")
        append("WHEN toLower(n.name) = toLower(\$query) THEN 0 ")
        append("WHEN toLower(n.fqn) = toLower(\$query) THEN 1 ")
        append("WHEN toLower(n.name) STARTS WITH toLower(\$query) THEN 2 ")
        append("ELSE 3 END, ")
        append("size(n.fqn) ")
        append("LIMIT ").append(limit)
    }

    private fun resolveKindFilter(kindFilter: List<String>?): Set<String>? {
        if (kindFilter.isNullOrEmpty()) return null

        val labels = mutableSetOf<String>()
        for (kind in kindFilter) {
            val expanded = JavaKinds.expandAlias(kind)
            if (expanded != null) {
                for (k in expanded) {
                    kindToLabels[k]?.let { labels.addAll(it) }
                }
            } else {
                val nodeKind = JavaNodeKind.fromValue(kind)
                if (nodeKind != null) {
                    kindToLabels[nodeKind]?.let { labels.addAll(it) }
                }
            }
        }
        return labels.ifEmpty { null }
    }
}
