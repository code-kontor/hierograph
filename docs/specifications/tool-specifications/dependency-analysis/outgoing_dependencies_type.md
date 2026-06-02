# `outgoing_dependencies` at type level

> **Implementation note:** This spec and `outgoing_dependencies_detail.md` describe two levels of the same tool. They are split into separate spec files for clarity, but must be implemented as a **single `outgoing_dependencies` MCP tool** with a `detail_level` parameter that switches between them.

**Category:** Type-level dependency evidence
**Result-size class:** Data-bounded (cursor-based pagination)

## Purpose

Returns type-to-type edges from a source subtree to a target subtree. This is the type-level evidence tool: given that an aggregated query revealed a dependency between two subtrees, `outgoing_dependencies` at type level tells the LLM which specific types are involved.

The directional naming reflects the question shape: `outgoing_dependencies` asks "what does the source side use of the target side?" For the reverse question, use `incoming_dependencies`.

At type level, `to_id` is **optional**. When it is omitted the target side is left unconstrained and the tool returns **all** outgoing core (type-level) dependencies of `from_id` — every edge from a type in `from_id` to any type anywhere in the graph (other modules, packages, or external/virtual types, including edges within `from_id` itself). This answers open-ended questions such as *"show me the dependencies of X."* The response summary carries a **`by_target`** rollup (see *Summary fields*) — the depended-upon types ranked by summed weight over the full result set — so the heaviest dependencies are reported even when the edge list is paginated; in the open form this ranks across the whole graph. `from_id` itself remains required: omitting both sides is not supported, because that would request the entire dependency graph (an unbounded N×N result). Omitting `to_id` is permitted **only at type level** — see the detail-level spec, which rejects it.

## Signature

```
outgoing_dependencies(
    from_id: long,                              // required: source subtree
    to_id: long?,                               // optional at type level — omit to return ALL outgoing dependencies
    detail_level: "type",                       // explicit or omitted (default)
    limit: int = 100,                           // optional
    cursor: string?                             // for pagination
)
```

### Parameters

**`from_id`** (long, required)
Source subtree root. Accepts module, package, or type IDs. Method and field IDs are rejected with `INVALID_NODE_KIND`.

**`to_id`** (long, optional at type level)
Target subtree root. Same kind constraints as `from_id`. **When omitted, the target side is unconstrained: every type-level outgoing edge from `from_id`'s types is returned, regardless of where the target type lives** (other modules, packages, or external/virtual types). This answers open-ended questions like *"show me the dependencies of X."* The result is bounded by the out-degree of `from_id` and paginated as usual. Omitting `to_id` is **only permitted at type level**; at `detail_level="detail"` it is rejected (see the detail-level spec).

**`detail_level`** (string, optional, default `"type"`)
Must be `"type"` (or omitted) for this spec. Setting `"detail"` switches to the detail-level spec.

**`limit`** (int, optional, default 100)
Maximum items per page. Default 100 (~350 bytes/edge, ~35 KB). Server-side cap: 400.

**`cursor`** (string, optional)
Opaque cursor from a previous response's `next_cursor`.

**`relationship`** — not valid at type level. Passing it returns:

```json
{
  "error": {
    "code": "INVALID_PARAMETER",
    "message": "The 'relationship' parameter is only valid at detail_level='detail'. At type level, edges carry attribute flags but cannot be filtered by detail-level relationship.",
    "recovery": "Either remove the 'relationship' parameter, or set detail_level='detail' to filter by relationship kind."
  }
}
```

## Response shape

Uses **slim payload encoding** — source and target types appear as edge endpoints across multiple edges.

```json
{
  "nodes": {
    "47291": { "name": "ClusterService", "qualified_name": "org.elasticsearch.cluster.ClusterService", "kind": "java.class" },
    "52103": { "name": "TransportService", "qualified_name": "org.elasticsearch.transport.TransportService", "kind": "java.class" },
    "52110": { "name": "Transport", "qualified_name": "org.elasticsearch.transport.Transport", "kind": "java.interface" }
  },
  "edges": [
    {
      "from": 47291,
      "to": 52103,
      "weight": 15,
      "type_pair_count": 1,
      "attributes": {
        "is_extends": false,
        "is_implements": false,
        "is_annotated_by": false,
        "is_depends_on_other": true
      }
    },
    {
      "from": 47291,
      "to": 52110,
      "weight": 3,
      "type_pair_count": 1,
      "attributes": {
        "is_extends": false,
        "is_implements": true,
        "is_annotated_by": false,
        "is_depends_on_other": false
      }
    }
  ],
  "summary": {
    "total": 42,
    "returned": 42,
    "truncated": false,
    "by_attribute": {
      "is_extends": 3,
      "is_implements": 7,
      "is_annotated_by": 5,
      "is_depends_on_other": 38
    },
    "by_source_type": [
      { "id": 47291, "count": 12 },
      { "id": 47305, "count": 8 }
    ]
  }
}
```

### Edge fields

**`from`** / **`to`** — source and target type IDs (reference the `nodes` map).

**`weight`** — number of underlying detail-level edges between this source type and target type.

**`type_pair_count`** — always 1 at the type level (each edge is a single type pair).

**`attributes`** — structured set of boolean flags indicating which specific kinds of underlying relationships contribute to this edge. For the Java provider: `is_extends`, `is_implements`, `is_annotated_by`, `is_depends_on_other`. Multiple can be true simultaneously.

### Summary fields

**`total`** — true count of all matching edges (across all pages).

**`returned`** — number of edges in this page.

**`truncated`** — `true` if more pages exist.

**`by_attribute`** — distribution of edges by attribute flag, computed over the full result set. Each count indicates how many edges have that attribute flag set (an edge with multiple attributes is counted in each).

**`by_source_type`** — top source types by edge count, computed over the full result set. Identifies which types concentrate the most outgoing coupling.

**`by_target`** — the top 10 depended-upon types ranked by **summed edge weight**, each entry `{ id, weight }`, computed over the **full** result set (not just the returned page), so it stays accurate even when the edge list is truncated by pagination. **Always present.** In the open form (`to_id` omitted) it ranks across the whole graph; in the constrained form it ranks within the `to_id` subtree. This is the direct answer to "what does `from_id` lean on most heavily."

## Pagination

### Iteration order

Edges sorted by `(source_type_qualified_name, target_type_qualified_name)`. All edges from the same source type appear contiguously.

### Cursor protocol

Standard Hierograph cursor protocol.

## Input validation

**Invalid node kind.** Method and field IDs are rejected with `INVALID_NODE_KIND` error including the declaring type.

**Unknown node ID.** Returns `NODE_NOT_FOUND` error.

**Missing `from_id`.** `from_id` is always required. A request that omits it (or omits both sides) returns `INVALID_PARAMETER` — the open query is only on the `to_id` side. Returning the entire dependency graph is not supported.

**`to_id` omitted at detail level.** Valid only at type level. The detail-level tool returns `INVALID_PARAMETER` when `to_id` is omitted (the open-target form would be unbounded at member granularity).

**Invalid cursor.** Standard cursor error responses per the pagination protocol.

## Architecture

Operates on the **in-memory type-level dependency graph**:

1. Expand `from_id` to its contained types; if `to_id` is provided, expand it too, otherwise leave the target side unconstrained
2. Query the in-memory edge set for edges whose source type is in `from_id` — filtered to target types in `to_id` when provided, otherwise to all targets
3. Sort by iteration order, compute summary, slice for page

No Neo4j queries. Microseconds to low milliseconds.

## Use cases

- **"Show me the dependencies of X"** (everything X uses, across the whole graph) — `outgoing_dependencies(from_id: X)` with `to_id` omitted
- **"What types in module A depend on types in module B?"** — `outgoing_dependencies(from_id: A, to_id: B)`
- **"Which types in this package extend types in that package?"** — `outgoing_dependencies(from_id: pkg_A, to_id: pkg_B)`, inspect `attributes.is_extends`
