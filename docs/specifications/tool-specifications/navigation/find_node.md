# `find_node`

**Category:** Discovery and navigation
**Result-size class:** Input-bounded (no pagination needed)

## Purpose

Resolves a name into one or more node IDs by searching the whole graph. This is the primary way to obtain node IDs and should be the first tool called when the user mentions a specific class, package, or module by name.

Matches against simple names, qualified names, and substrings using case-insensitive matching. Returns enriched NodeRefs with enough context for the LLM to disambiguate when multiple matches exist.

Works at any level of the hierarchy — finds modules, packages, types, methods, or fields.

## Signature

```
find_node(
    name: string,           // required
    kind_filter: string[]?  // optional, restricts results to specific kinds
)
```

### Parameters

**`name`** (string, required)
Name or fragment to search for. Case-insensitive substring match against both simple names and fully qualified names.

Examples: `"ClusterService"`, `"payment.api"`, `"toString"`.

**`kind_filter`** (string[], optional)
Restricts results to specific node kinds. Accepts specific kind values (`"java.class"`, `"java.method"`) and group aliases (`"types"`, `"members"`, `"packages"`).

When omitted, all kinds are searched.

Examples: `["java.class"]`, `["types"]`, `["java.package", "java.class"]`.

## Response shape

Returns a list of enriched NodeRefs, ordered by match quality (exact name match before substring match, shorter qualified names before longer ones).

```json
{
  "results": [
    {
      "id": 47291,
      "name": "ClusterService",
      "qualified_name": "org.elasticsearch.cluster.ClusterService",
      "kind": "java.class",
      "parent_id": 12503,
      "parent_kind": "java.package",
      "modifiers": ["public"],
      "member_count": 32,
      "method_count": 28,
      "field_count": 4,
      "annotation_count": 2,
      "interface_count": 1,
      "is_abstract": false,
      "is_generic": false,
      "parent_type": { "id": 9981, "name": "AbstractLifecycleComponent", ... }
    }
  ],
  "summary": {
    "total": 1,
    "returned": 1
  }
}
```

Each result is an **enriched NodeRef** — the full identity fields plus kind-appropriate metadata as defined in the tool surface proposal. The enriched metadata varies by kind:

- **Module:** `child_count`, `descendant_type_count`, `descendant_method_count`
- **Package:** `child_count`, `descendant_type_count`, `direct_type_count`
- **Type:** `modifiers`, `member_count`, `method_count`, `field_count`, `annotation_count`, `interface_count`, `is_abstract`, `is_generic`, `parent_type`
- **Method:** `modifiers`, `parameter_count`, `throws_count`, `annotation_count`, `is_constructor`
- **Field:** `modifiers`, `field_type_name`, `annotation_count`, `is_constant`

## Input validation

No pagination or cursor support — result size is bounded by the input. The tool applies a server-side cap on results (default 50) to prevent excessively large responses from broad substring matches.

If `kind_filter` contains an unrecognized kind value, the tool returns a structured error listing the valid kinds:

```json
{
  "error": {
    "code": "INVALID_KIND",
    "message": "Unknown kind 'Klass'. Valid kinds: java.class, java.interface, java.enum, java.record, java.annotation, java.method, java.field, java.package, java.module. Group aliases: types, members, packages.",
    "invalid_values": ["Klass"],
    "valid_kinds": ["java.class", "java.interface", ...],
    "valid_aliases": ["types", "members", "packages"]
  }
}
```

## Match semantics

The search matches against two fields per node:

1. **Simple name** — the unqualified name (e.g., `"ClusterService"`)
2. **Qualified name** — the fully qualified name (e.g., `"org.elasticsearch.cluster.ClusterService"`)

Matching is **case-insensitive substring**. The query `"cluster"` matches `"ClusterService"`, `"org.elasticsearch.cluster"`, and `"AbstractClusterState"`.

### Result ordering

Results are ordered to put the most likely intended match first:

1. **Exact name match** — query equals the simple name (case-insensitive)
2. **Exact qualified name match** — query equals the qualified name (case-insensitive)
3. **Prefix match on name** — simple name starts with the query
4. **Substring match** — ordered by qualified name length (shorter = more specific = higher priority)

This ordering means that searching for `"ClusterService"` returns the class `ClusterService` before methods or fields that happen to contain that string in their qualified name.

## Architecture

`find_node` uses **Neo4j as the search backend** — Neo4j's indexing and query capabilities are better suited for substring search and ranking than iterating the in-memory hierarchy. However, the tool layer must remain scanner-agnostic: no Cypher, no Neo4j types, no scanner-specific labels.

The architecture has two stages:

### Stage 1: Search (provider layer — Neo4j)

The provider layer exposes a search operation through the `SearchProvider` interface. This is a new sub-provider alongside the existing three (hierarchy, core dependency, detail dependency). It encapsulates the scanner-specific search query and returns results in Hierograph's domain terms.

```kotlin
interface SearchProvider {
    /**
     * Searches for nodes by name substring. Returns candidate matches
     * as (nodeId, name, qualifiedName, kind) tuples, ordered by match quality.
     *
     * The provider is responsible for:
     * - Case-insensitive substring matching against names and qualified names
     * - Result ordering (exact > prefix > substring, shorter FQN first)
     * - Applying kind filters (translated to scanner-specific labels)
     * - Enforcing the result cap
     *
     * Kind values use Hierograph's namespaced vocabulary (java.class, etc.),
     * not scanner-specific labels. The provider translates internally.
     */
    fun search(
        name: String,
        kindFilter: List<String>?,   // Hierograph kinds, not DB labels
        limit: Int
    ): List<SearchResult>
}

data class SearchResult(
    val nodeId: Long,
    val name: String,
    val qualifiedName: String,
    val kind: String              // Hierograph kind (java.class, etc.)
)
```

The jQAssistant implementation of `SearchProvider` translates Hierograph kinds to jQAssistant labels, builds the appropriate Cypher query, and maps the results back to Hierograph kinds. The Cypher, the label vocabulary, and the Neo4j driver types stay inside the provider — nothing leaks upward.

### Stage 2: Filter and enrich (tool/model layer — in-memory)

The tool layer receives `SearchResult` candidates from the provider and:

1. **Filters to mapped nodes.** Each candidate is looked up in the in-memory hierarchical graph via `lookupNode(nodeId)`. Candidates that aren't part of the hierarchy are silently dropped. This guarantees that every returned node is navigable — the LLM can pass any returned ID to any other tool.

2. **Enriches to full NodeRefs.** Matched in-memory nodes are converted to enriched NodeRefs with kind-appropriate metadata. This uses the same in-memory enrichment path as `list_children` and `list_descendants` — no additional Neo4j query needed.

This two-stage design gives us:

- **Good search quality** — Neo4j handles indexing, substring matching, and ranking efficiently
- **Scanner isolation** — the `SearchProvider` interface is the only place that knows about Cypher and scanner-specific labels
- **Consistency guarantee** — only nodes present in the hierarchical graph are returned, so results are always navigable
- **Rich responses** — enriched NodeRefs come from the in-memory model at no additional cost

### Provider integration

The `SearchProvider` becomes part of the `MappingProvider` bundle:

```kotlin
interface MappingProvider {
    val hierarchy: HierarchyProvider
    val coreDependency: CoreDependencyProvider
    val detailDependency: DetailDependencyProvider
    val search: SearchProvider                      // new

    val scannerId: String
}
```

### Why not search in-memory?

Searching the in-memory hierarchy directly is possible (all names and qualified names are available), but Neo4j is the better backend for search because:

- **Indexing.** Neo4j can use full-text or `CONTAINS` indexes for substring search. In-memory would require building and maintaining a separate search index or doing O(n) scans.
- **Ranking.** The Cypher query can express ordering logic (exact match first, then prefix, then substring) declaratively. In-memory would need a custom comparator over the full node set.
- **Scalability.** On large codebases (100K+ nodes), a purpose-built query engine handles search workload better than application-level iteration.

The cost is a Neo4j round-trip per search, but `find_node` is called infrequently (typically once per entity the user mentions) and the latency is negligible.

## Use cases

- **"What's the ID for ClusterService?"** — `find_node(name: "ClusterService")`
- **"Find all packages related to cluster"** — `find_node(name: "cluster", kind_filter: ["java.package"])`
- **"Is there a toString method on this class?"** — `find_node(name: "toString", kind_filter: ["java.method"])` (though `list_children` on the class is usually better for this)
- **"Find the transport module"** — `find_node(name: "transport", kind_filter: ["java.module"])`

## LLM tool description

The `@Tool` description should communicate:

1. This is the primary name-to-ID resolution tool
2. It searches by substring across simple and qualified names
3. `kind_filter` narrows results when names are ambiguous across node types
4. Results include metadata beyond just the ID — the LLM can inspect the match before proceeding
