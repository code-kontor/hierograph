# `incoming_dependencies` at type level

> **Implementation note:** This spec and `incoming_dependencies_detail.md` describe two levels of the same tool. They are split into separate spec files for clarity, but must be implemented as a **single `incoming_dependencies` MCP tool** with a `detail_level` parameter that switches between them.

**Category:** Type-level dependency evidence
**Result-size class:** Data-bounded (cursor-based pagination)

## Purpose

Returns type-to-type edges from the `to_id` subtree into the `from_id` subtree. This is the reverse-direction type-level evidence tool: `incoming_dependencies` asks "what does the target side use of the source side?"

`outgoing_dependencies(from_id: A, to_id: B)` shows what A uses of B.
`incoming_dependencies(from_id: A, to_id: B)` shows what B uses of A.

At type level, `to_id` (the depender side) is **optional**. When it is omitted the depender side is left unconstrained and the tool returns **all** incoming core (type-level) dependencies of `from_id` — every edge from any type anywhere in the graph that targets a type in `from_id` (including edges originating within `from_id` itself). This answers open-ended questions such as *"what depends on X?"* / *"show me everything that uses X."* The response summary carries a **`by_target`** rollup (see *Summary fields*): because every edge targets a type in `from_id`, this ranks the **`from_id` types by summed incoming weight** — i.e. the most heavily used types within `from_id` — computed over the full result set, so the ranking is complete even when the edge list is paginated. In the open form this counts incoming from anywhere; this is the direct answer to "which types here are used the most." `from_id` itself remains required: omitting both sides is not supported, because that would request the entire dependency graph (an unbounded N×N result). Omitting `to_id` is permitted **only at type level** — see the detail-level spec, which rejects it.

## Signature

```
incoming_dependencies(
    from_id: long,                              // required: the subtree that is depended upon
    to_id: long?,                               // optional at type level — omit to return ALL incoming dependencies
    detail_level: "type",                       // explicit or omitted (default)
    limit: int = 100,                           // optional
    cursor: string?                             // for pagination
)
```

### Parameters

**`from_id`** (long, required)
The subtree that is *depended upon*. Accepts module, package, or type IDs. Method and field IDs are rejected with `INVALID_NODE_KIND`.

**`to_id`** (long, optional at type level)
The subtree that *does the depending*. Same kind constraints as `from_id`. **When omitted, the depender side is unconstrained: every type-level edge that targets a type in `from_id` is returned, regardless of where the depending type lives** (other modules, packages, or external/virtual types). This answers open-ended questions like *"what depends on X?"* The result is bounded by the in-degree of `from_id` and paginated as usual. Omitting `to_id` is **only permitted at type level**; at `detail_level="detail"` it is rejected (see the detail-level spec).

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

Same as `outgoing_dependencies` at type level: `from`, `to`, `weight`, `type_pair_count`, `attributes`, and summary with `total`, `returned`, `truncated`, `by_attribute`, `by_source_type`, and `by_target`.

`by_source_type` identifies which types in the `to_id` subtree concentrate the most incoming coupling to the `from_id` subtree. `by_target` (always present) ranks the `from_id` types by summed incoming weight over the full result set — the most heavily used types within `from_id` — each entry `{ id, weight }`, top 10. In the open form this counts incoming edges from anywhere; in the constrained form, only those from the `to_id` subtree.

## Pagination

Same as `outgoing_dependencies` at type level:
- Edges sorted by `(source_type_qualified_name, target_type_qualified_name)`
- Standard Hierograph cursor protocol

## Input validation

Same as `outgoing_dependencies` at type level:
- `INVALID_NODE_KIND` for method/field IDs (with declaring type for recovery)
- `INVALID_PARAMETER` for `relationship` at type level
- `NODE_NOT_FOUND` for unknown IDs
- `INVALID_PARAMETER` if `from_id` is omitted (or both sides omitted) — the open query is only on the `to_id` side; returning the entire dependency graph is not supported
- `INVALID_PARAMETER` if `to_id` is omitted at `detail_level="detail"` — the open-depender form is valid only at type level

## Architecture

Operates on the **in-memory type-level dependency graph**, with source and target reversed:

1. Expand `from_id` to its contained types; if `to_id` is provided, expand it too, otherwise leave the depender side unconstrained
2. Query the in-memory edge set for edges whose target type is in `from_id` — filtered to source types in `to_id` when provided, otherwise from all sources
3. Sort by iteration order, compute summary, slice for page

No Neo4j queries.

## Use cases

- **"What depends on X?"** (everything that uses X, from anywhere) — `incoming_dependencies(from_id: X)` with `to_id` omitted
- **"What depends on this class?"** (scoped to one module/package) — `incoming_dependencies(from_id: class_id, to_id: package_or_module_id)`
- **"Which types in this module use types from that module?"** — `incoming_dependencies(from_id: mod_A, to_id: mod_B)`
