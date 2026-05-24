package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import java.util.List;

/**
 * Searches for nodes by name. This is the abstraction that keeps the tool layer
 * scanner-agnostic: the tool calls {@link #search} with Hierograph-namespaced kinds,
 * and the implementation translates to whatever the backing store needs.
 */
public interface ISearchProvider {

    /**
     * Searches for nodes whose name or qualified name contains {@code name} (case-insensitive).
     *
     * @param name       the search term (substring match)
     * @param kindFilter optional list of Hierograph kinds (e.g. {@code "java.class"}) or
     *                   group aliases ({@code "types"}, {@code "members"}, {@code "packages"})
     *                   to restrict results; may be {@code null} for no filtering
     * @param limit      maximum number of results to return
     * @return candidates ordered by match quality (exact name &gt; prefix &gt; substring,
     *         shorter qualified names first within the same match tier)
     */
    List<SearchResult> search(String name, List<String> kindFilter, int limit);

    /**
     * A single search hit, expressed in Hierograph's domain vocabulary.
     *
     * @param nodeId        the Neo4j node ID
     * @param name          the simple (unqualified) name
     * @param qualifiedName the fully qualified name
     * @param kind          Hierograph kind (e.g. {@code "java.class"}, {@code "java.package"}) — not scanner labels
     */
    record SearchResult(long nodeId, String name, String qualifiedName, String kind) {}
}
