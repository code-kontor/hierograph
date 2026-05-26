# `incoming_dependencies` at type level

> **Implementation note:** This spec and `incoming_dependencies_detail.md` describe two levels of the same tool. They are split into separate spec files for clarity, but must be implemented as a **single `incoming_dependencies` MCP tool** with a `detail_level` parameter that switches between them.

**Category:** Type-level dependency evidence
**Result-size class:** Data-bounded (cursor-based pagination)

## Purpose

Returns type-to-type edges from the `to_id` subtree into the `from_id` subtree. This is the reverse-direction type-level evidence tool: `incoming_dependencies` asks "what does the target side use of the source side?"

`outgoing_dependencies(from_id: A, to_id: B)` shows what A uses of B.
`incoming_dependencies(from_id: A, to_id: B)` shows what B uses of A.

## Signature

```
incoming_dependencies(
    from_id: long,                              // required: the subtree that is depended upon
    to_id: long,                                // required: the subtree that does the depending
    detail_level: "type",                       // explicit or omitted (default)
    limit: int = 100,                           // optional
    cursor: string?                             // for pagination
)
```

### Parameters

**`from_id`** (long, required)
The subtree that is *depended upon*. Accepts module, package, or type IDs. Method and field IDs are rejected with `INVALID_NODE_KIND`.

**`to_id`** (long, required)
The subtree that *does the depending*. Same kind constraints as `from_id`.

**`detail_level`** (string, optional, default `"type"`)
Must be `"type"` (or omitted) for this spec.

**`limit`** (int, optional, default 100)
Maximum items per page. Server-side cap: 400.

**`cursor`** (string, optional)
Opaque cursor from a previous response's `next_cursor`.

**`relationship`** — not valid at type level. Same error as `outgoing_dependencies`.

## Response shape

Identical structure to `outgoing_dependencies` at type level, but edges flow from `to_id` types to `from_id` types.

Uses **slim payload encoding**.

```json
{
  "nodes": {
    "52103": { "name": "TransportService", "qualified_name": "org.elasticsearch.transport.TransportService", "kind": "java.class" },
    "47291": { "name": "ClusterService", "qualified_name": "org.elasticsearch.cluster.ClusterService", "kind": "java.class" }
  },
  "edges": [
    {
      "from": 52103,
      "to": 47291,
      "weight": 8,
      "type_pair_count": 1,
      "attributes": {
        "is_extends": false,
        "is_implements": false,
        "is_annotated_by": false,
        "is_depends_on_other": true
      }
    }
  ],
  "summary": {
    "total": 12,
    "returned": 12,
    "truncated": false,
    "by_attribute": { "is_extends": 1, "is_implements": 0, "is_annotated_by": 0, "is_depends_on_other": 12 },
    "by_source_type": [
      { "id": 52103, "count": 5 },
      { "id": 52110, "count": 4 }
    ]
  }
}
```

### Edge fields, summary fields

Same as `outgoing_dependencies` at type level: `from`, `to`, `weight`, `type_pair_count`, `attributes`, and summary with `total`, `returned`, `truncated`, `by_attribute`, `by_source_type`.

`by_source_type` identifies which types in the `to_id` subtree concentrate the most incoming coupling to the `from_id` subtree.

## Pagination

Same as `outgoing_dependencies` at type level:
- Edges sorted by `(source_type_qualified_name, target_type_qualified_name)`
- Standard Hierograph cursor protocol

## Input validation

Same as `outgoing_dependencies` at type level:
- `INVALID_NODE_KIND` for method/field IDs (with declaring type for recovery)
- `INVALID_PARAMETER` for `relationship` at type level
- `NODE_NOT_FOUND` for unknown IDs

## Architecture

Operates on the **in-memory type-level dependency graph**, with source and target reversed:

1. Expand `from_id` and `to_id` to their contained types
2. Query the in-memory edge set for edges from `to_id` types to `from_id` types
3. Sort by iteration order, compute summary, slice for page

No Neo4j queries.

## Use cases

- **"What depends on this class?"** — `incoming_dependencies(from_id: class_id, to_id: package_or_module_id)`
- **"Which types in this module use types from that module?"** — `incoming_dependencies(from_id: mod_A, to_id: mod_B)`
