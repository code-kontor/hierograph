# `outgoing_dependencies` at detail level

> **Implementation note:** This spec and `outgoing_dependencies_type.md` describe two levels of the same tool. They are split into separate spec files for clarity, but must be implemented as a **single `outgoing_dependencies` MCP tool** with a `detail_level` parameter that switches between them.

**Category:** Detail-level dependency evidence
**Result-size class:** Data-bounded (single-page truncation; cursor pagination specified but not yet implemented — see *Pagination*)

## Purpose

Returns method/field-level edges from a source subtree to a target subtree, with source locations. This is the detail-level evidence tool: given that a type-level query revealed coupling between specific types, `outgoing_dependencies` at detail level shows the concrete method calls, field reads, annotations, etc. that constitute it.

## Signature

```
outgoing_dependencies(
    from_id: long,                              // required
    to_id: long,                                // required (cannot be omitted at detail level)
    detail_level: "detail",                     // required for this spec
    relationship: string?,                      // optional filter
    limit: int = 80,                            // optional
    cursor: string?                             // for pagination (specified, not yet implemented)
)
```

### Parameters

**`from_id`** (long, required)
Source subtree root. Accepts module, package, type, method, or field IDs.

**`to_id`** (long, required)
Target subtree root. Same kind constraints as `from_id`. **Required at detail level** — unlike the type level, `to_id` may *not* be omitted here. The open-target form (omit `to_id` to return *all* dependencies of `from_id`) is supported only at `detail_level="type"`, because an unconstrained target at member granularity is unbounded.

**`detail_level`** (string, required)
Must be `"detail"` for this spec.

**`relationship`** (string, optional)
Filters to a specific detail-level relationship kind.

Valid values (surfaced via `graph_overview`):
- Method-originated: `throws`, `calls`, `returns`, `parameter_type`, `reads_field`, `writes_field`, `overrides`, `annotated_by`, `parameter_annotated_by`
- Field-originated: `has_type`, `annotated_by`, `read_by`, `written_by`

Unknown values return a structured error listing the valid kinds.

**`limit`** (int, optional, default 80)
Maximum items per page. Default 80 (~550 bytes/edge, ~44 KB). Server-side cap: **150**. (The tool layer accepts values up to 250, but the detail component re-clamps to 150, so 150 is the effective ceiling.)

**`cursor`** (string, optional)
Opaque cursor from a previous response's `next_cursor`. **Not yet implemented — currently ignored** (see *Pagination*).

## Response shape

Uses **slim payload encoding** — source entities, target entities, and their declaring types all appear across multiple edges.

```json
{
  "nodes": {
    "47291": { "name": "ClusterService", "qualified_name": "org.elasticsearch.cluster.ClusterService", "kind": "java.class" },
    "47305": { "name": "applyState", "qualified_name": "org.elasticsearch.cluster.ClusterService.applyState", "kind": "java.method" },
    "52103": { "name": "TransportService", "qualified_name": "org.elasticsearch.transport.TransportService", "kind": "java.class" },
    "52120": { "name": "sendRequest", "qualified_name": "org.elasticsearch.transport.TransportService.sendRequest", "kind": "java.method" }
  },
  "from_scope": 47291,
  "to_scope": 52103,
  "edges": [
    {
      "from": 47305,
      "from_parent": 47291,
      "to": 52120,
      "to_parent": 52103,
      "relationship": "calls",
      "location": { "line_number": 247 }
    }
  ],
  "summary": {
    "total_edges": 15,
    "returned": 15,
    "truncated": false,
    "by_relationship": {
      "calls": 8,
      "reads_field": 3,
      "parameter_type": 2,
      "returns": 2
    },
    "by_source_type": [
      { "type": 47291, "edge_count": 12 },
      { "type": 47310, "edge_count": 3 }
    ],
    "by_source_nodes": [
      { "node": 47291, "aggregated_weight": 12 }
    ],
    "by_target_nodes": [
      { "node": 52103, "aggregated_weight": 15 }
    ]
  }
}
```

### Top-level fields

**`nodes`** — slim node map: `id → { name, qualified_name, kind }` for every id referenced by the edges, the scope endpoints, and the summary rollups.

**`from_scope`** / **`to_scope`** — the resolved `from_id` / `to_id` node ids (echoed back as longs). These confirm which nodes the query actually scoped to after resolution and anchor the `by_source_nodes` / `by_target_nodes` drill-downs.

**`edges`** — the returned page of detail-level edges (see *Edge fields*).

**`summary`** — aggregate rollups over the full result set (see *Summary fields*).

### Edge fields

**`from`** / **`to`** — source and target entity IDs (method, field, or type; reference the `nodes` map).

**`from_parent`** — declaring type ID of the source entity, for navigation back to the type level. **Always present**; equals `from` when the source is itself a type.

**`to_parent`** — declaring type ID of the target entity. Present **only when the target is a method or field**; **omitted** when the target is itself a type (in which case the type ID is already `to`).

**`relationship`** — the detail-level relationship kind (e.g., `calls`, `reads_field`, `annotated_by`).

**`location`** — source location of the edge, as `{ "line_number": N }`. **`null`** when no line number is available. No file path is currently emitted — only the line number within the source entity's declaring type.

### Summary fields

**`total_edges`** — true count of all matching edges (across all pages).

**`returned`** — number of edges in this page.

**`truncated`** — `true` if more pages exist.

**`by_relationship`** — distribution of edges by relationship kind, computed over the full result set. Each edge has exactly one relationship kind.

**`by_source_type`** — top 10 source types by edge count, computed over the full result set. Each entry is `{ type, edge_count }`, where `type` is the source type's node id (reference the `nodes` map) and `edge_count` is its number of edges; entries are sorted by descending `edge_count`.

**`others_count`** — present only when more than 10 distinct source types matched: the number of source types beyond the top 10 that are not listed in `by_source_type`.

**`by_source_nodes`** — hierarchical drill-down of the **source** scope (`from_id`). The tool descends from `from_id` through single-child levels until it reaches the first level that branches (more than one child), then reports, for each child at that level, the aggregated **type-level** dependency weight from that child into the `to_id` subtree. Each entry is `{ node, aggregated_weight }`, where `node` references the `nodes` map; children with zero weight are omitted and the list is sorted by descending weight. This shows *which sub-parts of the source* carry the coupling — structural context the flat per-edge list does not give.

**`by_target_nodes`** — the mirror for the **target** scope (`to_id`): descends from `to_id` to its first branching level and reports, for each child, the aggregated **type-level** dependency weight received from the `from_id` subtree. Same `{ node, aggregated_weight }` shape, zero-weight children omitted, sorted by descending weight. This shows *which sub-parts of the target* absorb the coupling.

> Both `by_source_nodes` and `by_target_nodes` are computed from **type-level aggregated weights**, not from the count of detail edges on the current page, so they remain accurate even when the detail edge list is paginated.

## Pagination

### Iteration order

Edges are sorted by `(relationship, source_type_qualified_name, source_entity_name, location.line_number)`: relationship kind first (alphabetical), then the source type's qualified name, then the source entity name, then the source line number. This order also determines which edges survive truncation.

### Truncation (current behavior)

The implementation runs a single query, sorts the full result set by the iteration order above, and returns the first `limit` edges. `summary.truncated` is `true` when more edges matched than were returned, and `summary.total_edges` reports the full match count — but **edges beyond `limit` are dropped and are not currently retrievable**, because the response carries no continuation token.

> **Current gap:** Cursor-based pagination is specified for this tool (the `cursor` request parameter and a `next_cursor` response field) but is **not yet implemented**. The response carries neither a `cursor` echo nor a `next_cursor`, and the `cursor` parameter is ignored. To retrieve more than `limit` edges today, raise `limit` (up to its server-side cap) or narrow the query with a `relationship` filter or a more specific `from_id` / `to_id`.

## Input validation

**Unknown relationship kind.** Returns a structured error listing the valid kinds from the provider's vocabulary.

**Unknown node ID.** Returns `NODE_NOT_FOUND` error.

**Missing `from_id` or `to_id`.** Both are required at detail level. Omitting `to_id` returns `INVALID_PARAMETER` — the open-target form is valid only at `detail_level="type"`. Omitting `from_id` is likewise rejected.

**Invalid cursor.** Specified to return standard cursor error responses per the pagination protocol, but **not yet implemented** — the `cursor` parameter is currently ignored rather than validated (see *Pagination*).

## Architecture

Uses the `DetailDependencyProvider` from the provider layer. The expansion strategy depends on whether the input ID refers to a **container node** (module, package, type) or a **member node** (method, field):

### Case 1: `from_id` / `to_id` is a module, package, or type

1. Expand `from_id` and `to_id` to their contained types
2. Call `provider.detailDependency.detailEdgeQuery(relationship, fromTypeIds, toTypeIds)`
3. The provider translates to scanner-specific Cypher that starts from **type nodes** and traverses down to their methods/fields to find detail-level edges
4. Sort by iteration order, compute summary, slice for page

### Case 2: `from_id` / `to_id` is a method or field

When a method or field ID is passed, the node does not *contain* types — it **is** a member of a type. The Cypher query must start from the **method/field node directly** rather than expanding to contained types:

1. Resolve the member node (method or field) from `from_id` / `to_id`
2. Call `provider.detailDependency.detailEdgeQueryForMember(relationship, memberNodeId, oppositeTypeIds)` (or equivalent)
3. The provider translates to scanner-specific Cypher that starts from the **member node** and matches its outgoing/incoming detail-level edges against the opposite subtree

> **Current gap:** This member-level Cypher path is not yet implemented. When a method or field ID is passed as `from_id` or `to_id`, the current implementation falls through to the type-expansion path, which yields zero contained types for a member node, resulting in an empty result set with no error. The fix requires a separate Cypher query (or query branch) that anchors on the member node directly.

### Mixed case

When one parameter is a container (module/package/type) and the other is a member (method/field), each side uses its own expansion strategy: the container side expands to types, the member side anchors on the member node.

The tool layer never sees Cypher or scanner-specific labels. The `DetailDependencyProvider` encapsulates the query and maps results to Hierograph's relationship vocabulary.

## Use cases

- **"What methods in class X call methods in class Y?"** — `outgoing_dependencies(from_id: X, to_id: Y, detail_level: "detail", relationship: "calls")`
- **"Show me all detail-level evidence between these two packages"** — `outgoing_dependencies(from_id: pkg_A, to_id: pkg_B, detail_level: "detail")`
- **"Which methods in this module throw exceptions from that module?"** — `outgoing_dependencies(from_id: mod_A, to_id: mod_B, detail_level: "detail", relationship: "throws")`
