package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import org.slizaa.hierarchicalgraph.core.model.HGNode;

import java.util.List;

/**
 * Provides structured metadata for nodes in the hierarchical graph. This is the single point
 * that encapsulates how nodes are named and categorized in a particular graph schema.
 *
 * <p>Implementations are schema-specific (e.g., jQAssistant, custom scanners) and are
 * registered as extensions on the root node via
 * {@code rootNode.registerExtension(INodeMetadataProvider.class, provider)}.
 */
public interface INodeMetadataProvider {

    // ---- Per-node metadata ----

    /**
     * Returns the display name for a node (e.g., "ClusterService", "org.example").
     */
    String getName(HGNode node);

    /**
     * Returns the fully qualified name (e.g., "org.example.ClusterService").
     */
    String getQualifiedName(HGNode node);

    /**
     * Returns the primary kind/category (e.g., "Class", "Package", "Artifact").
     */
    String getKind(HGNode node);

    /**
     * Determines the primary kind from a raw label list. This is used as a fallback
     * when a node exists in the database but is not present in the HG model.
     */
    String getKindFromLabels(List<String> labels);

    /**
     * Returns the list of valid kind values that can appear in a kind filter
     * (e.g., ["Class", "Interface", "Enum", "Annotation", "Package", "Artifact"]).
     */
    List<String> getKnownKinds();

    // ---- Cypher query delegation ----

    /**
     * Returns a Cypher query for finding nodes by name.
     * The query must use parameter {@code $query} (String) for the search term.
     * It must return columns: {@code nodeId}, {@code name}, {@code fqn}, {@code labels}.
     *
     * @param kind  optional kind filter (may be null)
     * @param limit maximum number of results
     */
    String getFindNodeCypherQuery(String kind, int limit);

    /**
     * Returns a Cypher query for counting nodes by kind within a scope.
     * Must return columns: {@code label}, {@code cnt}.
     *
     * @param scopeId scope node ID, or null for the full graph
     */
    String getNodeCountCypherQuery(Long scopeId);

    /**
     * Returns a Cypher query for depth statistics within a scope.
     * Must return columns: {@code maxDepth}, {@code avgDepth}.
     *
     * @param scopeId scope node ID, or null for the full graph
     */
    String getDepthStatsCypherQuery(Long scopeId);

    /**
     * Returns a Cypher query for dependency kind distribution within a scope.
     * Must return columns: {@code kind}, {@code cnt}.
     *
     * @param scopeId scope node ID, or null for the full graph
     */
    String getDependencyKindDistributionCypherQuery(Long scopeId);

    /**
     * Returns a Cypher query for retrieving scan metadata.
     * Must return column: {@code scannedAt}.
     */
    String getScanMetadataCypherQuery();

    /**
     * Returns the scanner name (e.g., "jqassistant").
     */
    String getScannerName();
}
