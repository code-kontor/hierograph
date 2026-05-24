# `incoming_dependencies` at detail level

> **Implementation note:** This spec and `incoming_dependencies_type.md` describe two levels of the same tool. They are split into separate spec files for clarity, but must be implemented as a **single `incoming_dependencies` MCP tool** with a `detail_level` parameter that switches between them.

**Category:** Detail-level dependency evidence
**Result-size class:** Data-bounded (cursor-based pagination)

## Purpose

Returns method/field-level edges from the `to_id` subtree into the `from_id` subtree, with source locations. This is the reverse-direction detail-level evidence tool.

`outgoing_dependencies(from_id: A, to_id: B, detail_level: "detail")` shows detail-level edges from A to B.
`incoming_dependencies(from_id: A, to_id: B, detail_level: "detail")` shows detail-level edges from B to A.

## Signature

```
incoming_dependencies(
    from_id: long,                              // required: the subtree that is depended upon
    to_id: long,                                // required: the subtree that does the depending
    detail_level: "detail",                     // required for this spec
    relationship: string?,                      // optional filter
    limit: int = 80,                            // optional
    cursor: string?                             // for pagination
)
```

### Parameters

**`from_id`** (long, required)
The subtree that is *depended upon*. Accepts module, package, type, method, or field IDs.

**`to_id`** (long, required)
The subtree that *does the depending*. Same kind constraints as `from_id`.

**`detail_level`** (string, required)
Must be `"detail"` for this spec.

**`relationship`** (string, optional)
Filters to a specific detail-level relationship kind. Same valid values as `outgoing_dependencies` at detail level.

**`limit`** (int, optional, default 80)
Maximum items per page. Server-side cap: 250.

**`cursor`** (string, optional)
Opaque cursor from a previous response's `next_cursor`.

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
  "edges": [
    {
      "from": 52120,
      "to": 47305,
      "from_parent": 52103,
      "to_parent": 47291,
      "relationship": "calls",
      "source_file": "org/elasticsearch/transport/TransportService.java",
      "source_line": 312
    }
  ],
  "summary": {
    "total": 8,
    "returned": 8,
    "truncated": false,
    "by_relationship": {
      "calls": 5,
      "reads_field": 2,
      "parameter_type": 1
    },
    "by_source_type": [
      { "id": 52103, "count": 6 },
      { "id": 52110, "count": 2 }
    ]
  }
}
```

### Edge fields, summary fields

Same as `outgoing_dependencies` at detail level: `from`, `to`, `from_parent`, `to_parent`, `relationship`, `source_file`, `source_line`, and summary with `total`, `returned`, `truncated`, `by_relationship`, `by_source_type`.

`by_source_type` identifies which types in the `to_id` subtree concentrate the most incoming coupling to the `from_id` subtree.

## Pagination

Same as `outgoing_dependencies` at detail level:
- Edges sorted by `(source_type_qualified_name, source_entity_name, target_qualified_name, relationship)`
- Standard Hierograph cursor protocol

## Input validation

Same as `outgoing_dependencies` at detail level:
- Unknown relationship kind returns structured error with valid kinds
- `NODE_NOT_FOUND` for unknown IDs
- Standard cursor errors

## Architecture

Uses the `DetailDependencyProvider` with reversed type ID sets:

1. Expand `from_id` and `to_id` to their contained types
2. Call `provider.detailDependency.detailEdgeQuery(relationship, toTypeIds, fromTypeIds)` — note the swap
3. Sort, compute summary, slice for page

## Use cases

- **"Which methods call into this class?"** — `incoming_dependencies(from_id: class_id, to_id: module_id, detail_level: "detail", relationship: "calls")`
- **"What reads this class's fields?"** — `incoming_dependencies(from_id: class_id, to_id: module_id, detail_level: "detail", relationship: "reads_field")`
- **"What annotations reference this annotation type?"** — `incoming_dependencies(from_id: annotation_type_id, to_id: module_id, detail_level: "detail", relationship: "annotated_by")`
