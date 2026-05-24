# `incoming_dependencies`

**Category:** Type-level and detail-level dependency evidence
**Result-size class:** Data-bounded (cursor-based pagination)

## Purpose

Returns the edges from a source subtree into a target subtree, at the requested zoom level. This is the reverse-direction evidence tool: `incoming_dependencies` asks "what does the target side use of the source side?" — the mirror of `outgoing_dependencies`.

The two tools operate on the same pair of subtrees but answer different questions. `outgoing_dependencies(from_id: A, to_id: B)` shows what A uses of B. `incoming_dependencies(from_id: A, to_id: B)` shows what B uses of A.

Replaces the previous `incoming_core_dependencies` tool and the incoming direction of `detail_dependencies`.

## Signature

```
incoming_dependencies(
    from_id: long,                              // required
    to_id: long,                                // required
    detail_level: "type" | "detail" = "type",   // optional, defaults to "type"
    relationship: string?,                      // only valid when detail_level == "detail"
    limit: int?,                                // optional, default varies by detail_level
    cursor: string?                             // for pagination
)
```

### Parameters

Identical to `outgoing_dependencies`. The only difference is the direction of traversal:

- `outgoing_dependencies(from_id: A, to_id: B)` returns edges **from A to B** (what A uses of B)
- `incoming_dependencies(from_id: A, to_id: B)` returns edges **from B to A** (what B uses of A)

**`from_id`** (long, required)
The subtree that is *depended upon*. At `detail_level: "type"`, accepts module, package, or type IDs. At `detail_level: "detail"`, also accepts method and field IDs.

**`to_id`** (long, required)
The subtree that *does the depending*. Same kind constraints as `from_id`.

**`detail_level`** (string, optional, default `"type"`)
- `"type"` — type-to-type edges (in-memory, fast)
- `"detail"` — method/field-level edges with source locations (Neo4j, slower)

**`relationship`** (string, optional)
Filters to a specific detail-level relationship kind. Only valid when `detail_level` is `"detail"`.

**`limit`** (int, optional)
- At `detail_level: "type"`: default **100**, server-side cap 400
- At `detail_level: "detail"`: default **80**, server-side cap 250

**`cursor`** (string, optional)
Opaque cursor from a previous response's `next_cursor`.

## Response shape

Identical structure to `outgoing_dependencies` at both detail levels. The only difference is which direction the edges flow:

- At type level: edges go from types in the `to_id` subtree to types in the `from_id` subtree
- At detail level: edges go from entities in the `to_id` subtree to entities in the `from_id` subtree

Uses **slim payload encoding**.

### Type-level response

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

### Detail-level response

Same structure as `outgoing_dependencies` detail-level response, with `from`, `to`, `from_parent`, `to_parent`, `relationship`, `source_file`, `source_line` per edge.

### Summary fields

Identical to `outgoing_dependencies`: `total`, `returned`, `truncated`, `by_attribute`/`by_relationship`, `by_source_type`.

`by_source_type` here identifies which types in the `to_id` subtree concentrate the most incoming coupling to the `from_id` subtree.

## Pagination

Same protocol as `outgoing_dependencies`:

- **Type level:** edges sorted by `(source_type_qualified_name, target_type_qualified_name)`
- **Detail level:** edges sorted by `(source_type_qualified_name, source_entity_name, target_qualified_name, relationship)`

Standard Hierograph cursor protocol.

## Input validation

Identical to `outgoing_dependencies`:

- `INVALID_NODE_KIND` for method/field IDs at type level (with declaring type for recovery)
- `INVALID_PARAMETER` for `relationship` at type level
- `NODE_NOT_FOUND` for unknown IDs
- Standard cursor errors for invalid cursors

## Architecture

Identical to `outgoing_dependencies`, but with source and target reversed:

- At **type level**: query the in-memory edge set for edges from `to_id` types to `from_id` types
- At **detail level**: call the `DetailDependencyProvider` with reversed type ID sets

## Use cases

- **"What depends on this class?"** — `incoming_dependencies(from_id: class_id, to_id: package_or_module_id)` at type level
- **"Which methods call into this class?"** — `incoming_dependencies(from_id: class_id, to_id: module_id, detail_level: "detail", relationship: "calls")`
- **"What reads this class's fields?"** — `incoming_dependencies(from_id: class_id, to_id: module_id, detail_level: "detail", relationship: "reads_field")`

## LLM tool description

The `@Tool` description should communicate:

1. Mirror of `outgoing_dependencies` — same parameters, reversed direction
2. Shows what the `to_id` subtree uses of the `from_id` subtree
3. Use after an aggregated query reveals a dependency of interest, to see who depends on a target
4. Same `detail_level` parameter: `"type"` for type-to-type edges, `"detail"` for method/field-level evidence
5. Results are paginated with the same defaults and cursor protocol
