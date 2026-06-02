# `incoming_dependencies` at detail level

> **Implementation note:** This spec and `incoming_dependencies_type.md` describe two levels of the same tool. They are split into separate spec files for clarity, but must be implemented as a **single `incoming_dependencies` MCP tool** with a `detail_level` parameter that switches between them.

**Category:** Detail-level dependency evidence
**Result-size class:** Data-bounded (single-page truncation; cursor pagination specified but not yet implemented — see *Pagination*)

## Purpose

Returns method/field-level edges from the `to_id` subtree into the `from_id` subtree, with source locations. This is the reverse-direction detail-level evidence tool.

`outgoing_dependencies(from_id: A, to_id: B, detail_level: "detail")` shows detail-level edges from A to B.
`incoming_dependencies(from_id: A, to_id: B, detail_level: "detail")` shows detail-level edges from B to A.

## Signature

```
incoming_dependencies(
    from_id: long,                              // required: the subtree that is depended upon
    to_id: long,                                // required: the subtree that does the depending (cannot be omitted at detail level)
    detail_level: "detail",                     // required for this spec
    relationship: string?,                      // optional filter
    limit: int = 80,                            // optional
    cursor: string?                             // for pagination (specified, not yet implemented)
)
```

### Parameters

**`from_id`** (long, required)
The subtree that is *depended upon*. Accepts module, package, type, method, or field IDs.

**`to_id`** (long, required)
The subtree that *does the depending*. Same kind constraints as `from_id`. **Required at detail level** — unlike the type level, `to_id` may *not* be omitted here. The open-depender form (omit `to_id` to return everything that depends on `from_id`) is supported only at `detail_level="type"`, because an unconstrained depender at member granularity is unbounded.

**`detail_level`** (string, required)
Must be `"detail"` for this spec.

**`relationship`** (string, optional)
Filters to a specific detail-level relationship kind. Same valid values as `outgoing_dependencies` at detail level.

**`limit`** (int, optional, default 80)
Maximum items per page. Default 80. Server-side cap: **150** (the tool layer accepts up to 250, but the detail component re-clamps to 150).

**`cursor`** (string, optional)
Opaque cursor from a previous response's `next_cursor`. **Not yet implemented — currently ignored** (see *Pagination*).

## Response shape

Identical structure to `outgoing_dependencies` at detail level, but edges flow from `to_id` entities to `from_id` entities.

Uses **slim payload encoding**.

```json
{
  "nodes": {
    "52103": { "name": "TransportService", "qualified_name": "org.elasticsearch.transport.TransportService", "kind": "java.class" },
    "52120": { "name": "sendRequest", "qualified_name": "org.elasticsearch.transport.TransportService.sendRequest", "kind": "java.method" },
    "47291": { "name": "ClusterService", "qualified_name": "org.elasticsearch.cluster.ClusterService", "kind": "java.class" },
    "47305": { "name": "applyState", "qualified_name": "org.elasticsearch.cluster.ClusterService.applyState", "kind": "java.method" }
  },
  "from_scope": 47291,
  "to_scope": 52103,
  "edges": [
    {
      "from": 52120,
      "from_parent": 52103,
      "to": 47305,
      "to_parent": 47291,
      "relationship": "calls",
      "location": { "line_number": 312 }
    }
  ],
  "summary": {
    "total_edges": 8,
    "returned": 8,
    "truncated": false,
    "by_relationship": {
      "calls": 5,
      "reads_field": 2,
      "parameter_type": 1
    },
    "by_source_type": [
      { "type": 52103, "edge_count": 6 },
      { "type": 52110, "edge_count": 2 }
    ],
    "by_source_nodes": [
      { "node": 52103, "aggregated_weight": 8 }
    ],
    "by_target_nodes": [
      { "node": 47291, "aggregated_weight": 8 }
    ]
  }
}
```

### Top-level fields

Same top-level shape as `outgoing_dependencies` at detail level: `nodes`, `from_scope`, `to_scope`, `edges`, `summary`. **`from_scope`** / **`to_scope`** echo the resolved `from_id` / `to_id` node ids (longs) — confirming the nodes the query scoped to after resolution and anchoring the `by_source_nodes` / `by_target_nodes` drill-downs. The edge direction is reversed relative to `outgoing_dependencies`: edges flow from `to_scope` (depender) entities into `from_scope` (depended-upon) entities.

### Edge fields, summary fields

Same as `outgoing_dependencies` at detail level: `from`, `from_parent`, `to`, `to_parent` (present only when the target is a method/field), `relationship`, `location` (`{ line_number }`, or `null` when unknown — no file path is emitted), and summary with `total_edges`, `returned`, `truncated`, `by_relationship`, `by_source_type` (plus `others_count` when more than 10 source types match), `by_source_nodes`, and `by_target_nodes`.

`by_source_type` (entries `{ type, edge_count }`, top 10 by descending `edge_count`) identifies which types in the `to_id` subtree concentrate the most incoming coupling to the `from_id` subtree.

Because edges flow from `to_id` entities (the source side) into `from_id` entities (the target side), the two node rollups follow the same orientation:

**`by_source_nodes`** — hierarchical drill-down of the **depender** scope (`to_id`, the edge-source side). The tool descends from `to_id` through single-child levels to the first branching level, then reports, for each child at that level, the aggregated **type-level** dependency weight from that child into the `from_id` subtree. Each entry is `{ node, aggregated_weight }`, where `node` references the `nodes` map; zero-weight children are omitted and the list is sorted by descending weight. This shows *which sub-parts of the depender* concentrate the coupling.

**`by_target_nodes`** — hierarchical drill-down of the **depended-upon** scope (`from_id`, the edge-target side): descends from `from_id` to its first branching level and reports, for each child, the aggregated **type-level** dependency weight received from the `to_id` subtree. Same `{ node, aggregated_weight }` shape, zero-weight children omitted, sorted by descending weight. This shows *which sub-parts of the depended-upon subtree* absorb the coupling.

> Both node rollups are computed from **type-level aggregated weights**, not from the count of detail edges on the current page, so they remain accurate even when the detail edge list is paginated.

## Pagination

Same as `outgoing_dependencies` at detail level:
- Edges sorted by `(relationship, source_type_qualified_name, source_entity_name, location.line_number)`, which also determines which edges survive truncation
- **Single-page truncation:** the implementation returns the first `limit` edges of the sorted full set; `truncated` flags that more matched and `total_edges` reports the full count, but surplus edges are dropped and not retrievable

> **Current gap:** Cursor-based pagination is specified (the `cursor` request parameter and a `next_cursor` response field) but is **not yet implemented** — the response carries no `cursor` or `next_cursor`, and the `cursor` parameter is ignored. To retrieve more than `limit` edges today, raise `limit` (up to its server-side cap) or narrow the query with a `relationship` filter or a more specific `from_id` / `to_id`.

## Input validation

Same as `outgoing_dependencies` at detail level:
- Unknown relationship kind returns structured error with valid kinds
- `NODE_NOT_FOUND` for unknown IDs
- `INVALID_PARAMETER` if `to_id` is omitted — required at detail level; the open-depender form is valid only at `detail_level="type"` (and `from_id` is always required)
- Standard cursor errors are **specified but not yet implemented** — the `cursor` parameter is currently ignored rather than validated (see *Pagination*)

## Architecture

Uses the `DetailDependencyProvider` with reversed type ID sets. The expansion strategy depends on whether the input ID refers to a **container node** (module, package, type) or a **member node** (method, field):

### Case 1: `from_id` / `to_id` is a module, package, or type

1. Expand `from_id` and `to_id` to their contained types
2. Call `provider.detailDependency.detailEdgeQuery(relationship, toTypeIds, fromTypeIds)` — note the swap
3. The provider translates to scanner-specific Cypher that starts from **type nodes** and traverses down to their methods/fields to find detail-level edges
4. Sort, compute summary, slice for page

### Case 2: `from_id` / `to_id` is a method or field

When a method or field ID is passed, the node does not *contain* types — it **is** a member of a type. The Cypher query must start from the **method/field node directly** rather than expanding to contained types:

1. Resolve the member node (method or field) from `from_id` / `to_id`
2. Call `provider.detailDependency.detailEdgeQueryForMember(relationship, memberNodeId, oppositeTypeIds)` (or equivalent) — with the swap applied for the incoming direction
3. The provider translates to scanner-specific Cypher that starts from the **member node** and matches its outgoing/incoming detail-level edges against the opposite subtree

> **Current gap:** This member-level Cypher path is not yet implemented. When a method or field ID is passed as `from_id` or `to_id`, the current implementation falls through to the type-expansion path, which yields zero contained types for a member node, resulting in an empty result set with no error. The fix requires a separate Cypher query (or query branch) that anchors on the member node directly.

### Mixed case

When one parameter is a container (module/package/type) and the other is a member (method/field), each side uses its own expansion strategy: the container side expands to types, the member side anchors on the member node.

## Use cases

- **"Which methods call into this class?"** — `incoming_dependencies(from_id: class_id, to_id: module_id, detail_level: "detail", relationship: "calls")`
- **"What reads this class's fields?"** — `incoming_dependencies(from_id: class_id, to_id: module_id, detail_level: "detail", relationship: "reads_field")`
- **"What annotations reference this annotation type?"** — `incoming_dependencies(from_id: annotation_type_id, to_id: module_id, detail_level: "detail", relationship: "annotated_by")`
