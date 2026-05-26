/*
 * Copyright 2024 Gerd Wuetherich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hierograph.mcp.jqa.hierarchicalgraph

import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbNodeSource

class JQAssistantNodeMetadataProvider : INodeMetadataProvider {

    override fun getName(node: HGNode): String {
        val src = node.nodeSource as? GraphDbNodeSource ?: return ""
        return if (src.labels.containsAll(listOf("Artifact", "File"))) {
            src.properties["fileName"]?.removePrefix("/") ?: ""
        } else {
            src.properties["name"] ?: ""
        }
    }

    override fun getQualifiedName(node: HGNode): String {
        val src = node.nodeSource as? GraphDbNodeSource ?: return ""
        return if (src.labels.containsAll(listOf("Artifact", "File"))) {
            src.properties["fileName"]?.removePrefix("/") ?: ""
        } else {
            src.properties["fqn"] ?: ""
        }
    }

    override fun getKind(node: HGNode): String {
        val src = node.nodeSource as? GraphDbNodeSource ?: return "Unknown"
        return getKindFromLabels(src.labels.toList())
    }

    override fun getKindFromLabels(labels: List<String>): String {
        for (candidate in DEFAULT_KNOWN_KINDS) {
            if (candidate in labels) return candidate
        }
        return if (labels.isEmpty()) "Unknown" else labels[0]
    }

    override fun getKnownKinds(): List<String> = DEFAULT_KNOWN_KINDS

    override fun getFindNodeCypherQuery(kind: String?, limit: Int): String = buildString {
        append("MATCH (n) WHERE (n:Type OR n:Package OR n:Artifact) ")
        if (!kind.isNullOrBlank()) {
            append("AND n:").append(kind.replace(Regex("[^a-zA-Z0-9]"), "")).append(" ")
        }
        append("AND (toLower(n.name) CONTAINS toLower(\$query) OR toLower(n.fqn) CONTAINS toLower(\$query)) ")
        append("RETURN id(n) AS nodeId, n.name AS name, n.fqn AS fqn, labels(n) AS labels ")
        append("ORDER BY CASE WHEN toLower(n.name) = toLower(\$query) THEN 0 ")
        append("WHEN toLower(n.name) STARTS WITH toLower(\$query) THEN 1 ")
        append("ELSE 2 END, size(n.name) ")
        append("LIMIT ").append(limit)
    }

    override fun getNodeCountCypherQuery(scopeId: Long?): String = if (scopeId == null) {
        "MATCH (n) WHERE (n:Type OR n:Package OR n:Artifact) " +
                "UNWIND labels(n) AS label " +
                "WITH n, label WHERE label IN ['Class','Interface','Enum','Annotation','Record','Package','Artifact'] " +
                "RETURN label, count(DISTINCT n) AS cnt ORDER BY cnt DESC"
    } else {
        "MATCH (scope)-[:CONTAINS*]->(n) WHERE id(scope) = \$scopeId AND (n:Type OR n:Package) " +
                "UNWIND labels(n) AS label " +
                "WITH n, label WHERE label IN ['Class','Interface','Enum','Annotation','Record','Package'] " +
                "RETURN label, count(DISTINCT n) AS cnt ORDER BY cnt DESC"
    }

    override fun getDepthStatsCypherQuery(scopeId: Long?): String = if (scopeId == null) {
        "MATCH path = (a:Artifact:Main)-[:CONTAINS*]->(leaf) " +
                "WHERE NOT (leaf)-[:CONTAINS]->() AND (leaf:Type OR leaf:Package) " +
                "RETURN max(length(path)) AS maxDepth, avg(length(path)) AS avgDepth"
    } else {
        "MATCH path = (scope)-[:CONTAINS*]->(leaf) " +
                "WHERE id(scope) = \$scopeId AND NOT (leaf)-[:CONTAINS]->() " +
                "RETURN max(length(path)) AS maxDepth, avg(length(path)) AS avgDepth"
    }

    override fun getDependencyKindDistributionCypherQuery(scopeId: Long?): String = if (scopeId == null) {
        "MATCH (t1:Type)-[r:DEPENDS_ON|EXTENDS|IMPLEMENTS|ANNOTATED_BY]->(t2:Type) " +
                "RETURN type(r) AS kind, count(*) AS cnt ORDER BY cnt DESC"
    } else {
        "MATCH (scope)-[:CONTAINS*]->(t1:Type)-[r:DEPENDS_ON|EXTENDS|IMPLEMENTS|ANNOTATED_BY]->(t2:Type) " +
                "WHERE id(scope) = \$scopeId " +
                "RETURN type(r) AS kind, count(*) AS cnt ORDER BY cnt DESC"
    }

    override fun getScanMetadataCypherQuery(): String =
        "MATCH (n:jQAssistant:Task:Analyze) RETURN n.endTime AS scannedAt LIMIT 1"

    override fun getScannerName(): String = "jqassistant"

    companion object {
        private val DEFAULT_KNOWN_KINDS = listOf(
            "Class", "Interface", "Enum", "Annotation", "Record", "Package", "Artifact"
        )
    }
}
