package org.slizaa.jqassistant.hierarchicalgraph;

import org.neo4j.driver.Record;
import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ISearchProvider;

import java.util.*;
import java.util.stream.Collectors;

/**
 * jQAssistant-specific {@link ISearchProvider} implementation.
 * <p>
 * Translates Hierograph's namespaced kind vocabulary to jQAssistant Neo4j labels,
 * builds a Cypher query with match-quality ordering, and maps results back to
 * Hierograph kinds. All Cypher and label knowledge is confined to this class.
 */
public class JQAssistantSearchProvider implements ISearchProvider {

    private final IBoltClient boltClient;
    private final INodeMetadataProvider metadataProvider;

    public JQAssistantSearchProvider(IBoltClient boltClient, INodeMetadataProvider metadataProvider) {
        this.boltClient = boltClient;
        this.metadataProvider = metadataProvider;
    }

    // ── kind vocabulary mapping ────────────────────────────────────────

    /** Hierograph kind → jQAssistant Neo4j label(s). */
    private static final Map<String, List<String>> KIND_TO_LABELS = Map.ofEntries(
            Map.entry("java.module", List.of("Artifact")),
            Map.entry("java.package", List.of("Package")),
            Map.entry("java.class", List.of("Class")),
            Map.entry("java.interface", List.of("Interface")),
            Map.entry("java.enum", List.of("Enum")),
            Map.entry("java.record", List.of("Record")),
            Map.entry("java.annotation", List.of("Annotation")),
            Map.entry("java.method", List.of("Method")),
            Map.entry("java.field", List.of("Field"))
    );

    /** Group aliases → Hierograph kinds. */
    private static final Map<String, List<String>> GROUP_ALIASES = Map.of(
            "types", List.of("java.class", "java.interface", "java.enum", "java.record", "java.annotation"),
            "members", List.of("java.method", "java.field"),
            "packages", List.of("java.package")
    );

    // ── search ─────────────────────────────────────────────────────────

    @Override
    public List<SearchResult> search(String name, List<String> kindFilter, int limit) {
        Set<String> resolvedLabels = resolveKindFilter(kindFilter);
        String cypher = buildCypher(resolvedLabels, limit);

        var result = boltClient.syncExecCypherQuery(cypher, Map.of("query", name));

        List<SearchResult> hits = new ArrayList<>();
        for (Record record : result.records()) {
            List<String> labels = record.get("labels").asList(v -> v.asString());
            hits.add(new SearchResult(
                    record.get("nodeId").asLong(),
                    record.get("name").asString(""),
                    record.get("fqn").asString(""),
                    metadataProvider.getKindFromLabels(labels)
            ));
        }
        return hits;
    }

    // ── Cypher construction ────────────────────────────────────────────

    private String buildCypher(Set<String> labelFilter, int limit) {
        var sb = new StringBuilder();

        // MATCH clause
        if (labelFilter != null && !labelFilter.isEmpty()) {
            String conditions = labelFilter.stream()
                    .map(l -> "n:" + l)
                    .collect(Collectors.joining(" OR "));
            sb.append("MATCH (n) WHERE (").append(conditions).append(") ");
        } else {
            sb.append("MATCH (n) WHERE (n:Type OR n:Package OR n:Artifact) ");
        }

        // Substring match
        sb.append("AND (toLower(n.name) CONTAINS toLower($query) OR toLower(n.fqn) CONTAINS toLower($query)) ");

        // Return columns
        sb.append("RETURN id(n) AS nodeId, n.name AS name, n.fqn AS fqn, labels(n) AS labels ");

        // Ordering: exact name > exact fqn > prefix > substring, then by fqn length
        sb.append("ORDER BY ");
        sb.append("CASE ");
        sb.append("WHEN toLower(n.name) = toLower($query) THEN 0 ");
        sb.append("WHEN toLower(n.fqn) = toLower($query) THEN 1 ");
        sb.append("WHEN toLower(n.name) STARTS WITH toLower($query) THEN 2 ");
        sb.append("ELSE 3 END, ");
        sb.append("size(n.fqn) ");

        sb.append("LIMIT ").append(limit);

        return sb.toString();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Resolves a kind filter (Hierograph kinds and/or group aliases) into
     * the set of Neo4j labels. Returns null if no filter is active.
     */
    private Set<String> resolveKindFilter(List<String> kindFilter) {
        if (kindFilter == null || kindFilter.isEmpty()) return null;

        Set<String> labels = new LinkedHashSet<>();
        for (String kind : kindFilter) {
            List<String> expanded = GROUP_ALIASES.get(kind);
            if (expanded != null) {
                for (String k : expanded) {
                    List<String> mapped = KIND_TO_LABELS.get(k);
                    if (mapped != null) labels.addAll(mapped);
                }
            } else {
                List<String> mapped = KIND_TO_LABELS.get(kind);
                if (mapped != null) labels.addAll(mapped);
            }
        }

        return labels.isEmpty() ? null : labels;
    }
}
