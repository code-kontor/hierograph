package org.slizaa.jqassistant.hierarchicalgraph;

import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbNodeSource;

import java.util.Collections;
import java.util.List;

public class JQAssistant_NodeMetadataProvider implements INodeMetadataProvider {


    private static final List<String> DEFAULT_KNOWN_KINDS =
            List.of("Class", "Interface", "Enum", "Annotation", "Package", "Artifact");

    @Override
    public String getName(HGNode node) {
        if (node.getNodeSource() instanceof GraphDbNodeSource src) {
            if (src.getLabels().containsAll(List.of("Artifact", "File"))) {
                String name = src.getProperties().get("fileName");
                if (name != null && name.startsWith("/")) {
                    name = name.substring(1);
                }
                return name;
            } else {
                String name = src.getProperties().get("name");
                return name != null ? name : "";
            }
        }
        return "";
    }

    @Override
    public String getQualifiedName(HGNode node) {
        if (node.getNodeSource() instanceof GraphDbNodeSource src) {
            if (src.getLabels().containsAll(List.of("Artifact", "File"))) {
                String name = src.getProperties().get("fileName");
                if (name != null && name.startsWith("/")) {
                    name = name.substring(1);
                }
                return name;
            } else {
                String fqn = src.getProperties().get("fqn");
                return fqn != null ? fqn : "";
            }
        }
        return "";
    }

    @Override
    public String getKind(HGNode node) {
        if (node.getNodeSource() instanceof GraphDbNodeSource src) {
            return getKindFromLabels(List.copyOf(src.getLabels()));
        }
        return "Unknown";
    }

    @Override
    public String getKindFromLabels(List<String> labels) {
        for (String candidate : DEFAULT_KNOWN_KINDS) {
            if (labels.contains(candidate)) {
                return candidate;
            }
        }
        return labels.isEmpty() ? "Unknown" : labels.get(0);
    }

    @Override
    public List<String> getKnownKinds() {
        return DEFAULT_KNOWN_KINDS;
    }

    @Override
    public String getFindNodeCypherQuery(String kind, int limit) {
        StringBuilder cypher = new StringBuilder();
        cypher.append("MATCH (n) WHERE (n:Type OR n:Package OR n:Artifact) ");
        if (kind != null && !kind.isBlank()) {
            cypher.append("AND n:").append(kind.replaceAll("[^a-zA-Z0-9]", "")).append(" ");
        }
        cypher.append("AND (toLower(n.name) CONTAINS toLower($query) OR toLower(n.fqn) CONTAINS toLower($query)) ");
        cypher.append("RETURN id(n) AS nodeId, n.name AS name, n.fqn AS fqn, labels(n) AS labels ");
        cypher.append("ORDER BY CASE WHEN toLower(n.name) = toLower($query) THEN 0 ");
        cypher.append("WHEN toLower(n.name) STARTS WITH toLower($query) THEN 1 ");
        cypher.append("ELSE 2 END, size(n.name) ");
        cypher.append("LIMIT ").append(limit);
        return cypher.toString();
    }

    @Override
    public String getNodeCountCypherQuery(Long scopeId) {
        if (scopeId == null) {
            return "MATCH (n) WHERE (n:Type OR n:Package OR n:Artifact) "
                    + "UNWIND labels(n) AS label "
                    + "WITH label WHERE label IN ['Class','Interface','Enum','Annotation','Package','Artifact'] "
                    + "RETURN label, count(*) AS cnt ORDER BY cnt DESC";
        }
        return "MATCH (scope)-[:CONTAINS*]->(n) WHERE id(scope) = $scopeId AND (n:Type OR n:Package) "
                + "UNWIND labels(n) AS label "
                + "WITH label WHERE label IN ['Class','Interface','Enum','Annotation','Package'] "
                + "RETURN label, count(*) AS cnt ORDER BY cnt DESC";
    }

    @Override
    public String getDepthStatsCypherQuery(Long scopeId) {
        if (scopeId == null) {
            return "MATCH path = (a:Artifact:Main)-[:CONTAINS*]->(leaf) "
                    + "WHERE NOT (leaf)-[:CONTAINS]->() AND (leaf:Type OR leaf:Package) "
                    + "RETURN max(length(path)) AS maxDepth, avg(length(path)) AS avgDepth";
        }
        return "MATCH path = (scope)-[:CONTAINS*]->(leaf) "
                + "WHERE id(scope) = $scopeId AND NOT (leaf)-[:CONTAINS]->() "
                + "RETURN max(length(path)) AS maxDepth, avg(length(path)) AS avgDepth";
    }

    @Override
    public String getDependencyKindDistributionCypherQuery(Long scopeId) {
        if (scopeId == null) {
            return "MATCH (t1:Type)-[r:DEPENDS_ON|EXTENDS|IMPLEMENTS|ANNOTATED_BY]->(t2:Type) "
                    + "RETURN type(r) AS kind, count(*) AS cnt ORDER BY cnt DESC";
        }
        return "MATCH (scope)-[:CONTAINS*]->(t1:Type)-[r:DEPENDS_ON|EXTENDS|IMPLEMENTS|ANNOTATED_BY]->(t2:Type) "
                + "WHERE id(scope) = $scopeId "
                + "RETURN type(r) AS kind, count(*) AS cnt ORDER BY cnt DESC";
    }

    @Override
    public String getScanMetadataCypherQuery() {
        return "MATCH (n:jQAssistant:Task:Analyze) RETURN n.endTime AS scannedAt LIMIT 1";
    }

    @Override
    public String getScannerName() {
        return "jqassistant";
    }
}
