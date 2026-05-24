# `outgoing_dependencies`

**Category:** Type-level and detail-level dependency evidence
**Result-size class:** Data-bounded (cursor-based pagination)

## Purpose

Returns the edges from a source subtree to a target subtree, at the requested zoom level. This is an *evidence* tool: given that an aggregated query revealed a dependency between two subtrees, `outgoing_dependencies` tells the LLM what is actually between them — which specific types, methods, or fields are involved.

The directional naming reflects the question shape: `outgoing_dependencies` asks "what does the source side use of the target side?" For the reverse question ("what does the target side use of the source side?"), use `incoming_dependencies`.

Replaces the previous `outgoing_core_dependencies` tool and the outgoing direction of `detail_dependencies`.

## Signature

```
outgoing_dependencies(
    from_id: long,                              // required
    to_id: long,                                // required
    detail_level: "type" | "detail" = "type",   // optional, defaults to "type"
    relationship: string?,                      // only valid when detail_level == "detail"
    limit: int?,                                // optional, default varies by detail_level
    cursor: string?                             // for pagination
)
```

### Parameters

**`from_id`** (long, required)
Source subtree root. At `detail_level: "type"`, accepts module, package, or type IDs. At `detail_level: "detail"`, also accepts method and field IDs.

**`to_id`** (long, required)
Target subtree root. Same kind constraints as `from_id`.

**`detail_level`** (string, optional, default `"type"`)
Controls the zoom level of the returned edges:
- `"type"` — returns type-to-type edges (in-memory, fast)
- `"detail"` — returns method/field-level edges with source locations (Neo4j, slower)

**`relationship`** (string, optional)
Filters to a specific detail-level relationship kind. Only valid when `detail_level` is `"detail"`. Passing it with `detail_level: "type"` returns a structured error.

Valid values (from the `DetailDependencyProvider`'s vocabulary, surfaced via `graph_overview`):
- Method-originated: `throws`, `calls`, `returns`, `parameter_type`, `reads_field`, `writes_field`, `overrides`, `annotated_by`, `parameter_annotated_by`
- Field-originated: `has_type`, `annotated_by`, `read_by`, `written_by`

**`limit`** (int, optional)
Maximum items per page. Defaults vary by detail level to stay within the 10K-token warning threshold:
- At `detail_level: "type"`: default **100** (~350 bytes/edge, ~35 KB)
- At `detail_level: "detail"`: default **80** (~550 bytes/edge, ~44 KB)

Server-side caps: 400 (type level), 250 (detail level).

**`cursor`** (string, optional)
Opaque cursor from a previous response's `next_cursor` for pagination.

## Response shape at `detail_level: "type"`

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

### Type-level edge fields

**`from`** / **`to`** — source and target type IDs (reference the `nodes` map).

**`weight`** — number of underlying detail-level edges between this source type and target type.

**`type_pair_count`** — always 1 at the type level (each edge is a single type pair).

**`attributes`** — structured set of boolean flags indicating which specific kinds of underlying relationships contribute to this edge. For the Java provider: `is_extends`, `is_implements`, `is_annotated_by`, `is_depends_on_other`. Multiple can be true simultaneously.

## Response shape at `detail_level: "detail"`

Uses **slim payload encoding** — source entities, target entities, and their declaring types all appear across multiple edges.

```json
{
  "nodes": {
    "47291": { "name": "ClusterService", "qualified_name": "org.elasticsearch.cluster.ClusterService", "kind": "java.class" },
    "47305": { "name": "applyState", "qualified_name": "org.elasticsearch.cluster.ClusterService.applyState", "kind": "java.method" },
    "52103": { "name": "TransportService", "qualified_name": "org.elasticsearch.transport.TransportService", "kind": "java.class" },
    "52120": { "name": "sendRequest", "qualified_name": "org.elasticsearch.transport.TransportService.sendRequest", "kind": "java.method" }
  },
  "edges": [
    {
      "from": 47305,
      "to": 52120,
      "from_parent": 47291,
      "to_parent": 52103,
      "relationship": "calls",
      "source_file": "org/elasticsearch/cluster/ClusterService.java",
      "source_line": 247
    }
  ],
  "summary": {
    "total": 15,
    "returned": 15,
    "truncated": false,
    "by_relationship": {
      "calls": 8,
      "reads_field": 3,
      "parameter_type": 2,
      "returns": 2
    },
    "by_source_type": [
      { "id": 47291, "count": 12 },
      { "id": 47310, "count": 3 }
    ]
  },
  "next_cursor": null
}
```

### Detail-level edge fields

**`from`** / **`to`** — source and target entity IDs (method, field, or type; reference the `nodes` map).

**`from_parent`** / **`to_parent`** — declaring type IDs of the source and target entities. Present for navigation back to the type level. When the entity is itself a type, `from_parent` or `to_parent` equals `from` or `to`.

**`relationship`** — the detail-level relationship kind (e.g., `calls`, `reads_field`, `annotated_by`).

**`source_file`** — relative file path of the source location.

**`source_line`** — line number in the source file.

## Summary fields (both levels)

**`total`** — true count of all matching edges (across all pages).

**`returned`** — number of edges in this page.

**`truncated`** — `true` if more pages exist.

**`by_attribute`** (type level) / **`by_relationship`** (detail level) — distribution of edges by attribute/relationship, computed over the full result set. At the type level, each count indicates how many edges have that attribute flag set (an edge with multiple attributes is counted in each). At the detail level, each edge has exactly one relationship kind. Lets the LLM see the shape of the dependency without processing all edges.

**`by_source_type`** — top source types by edge count, computed over the full result set. Identifies which types concentrate the most outgoing coupling.

## Pagination

### Iteration order

**Type level:** edges sorted by `(source_type_qualified_name, target_type_qualified_name)`. All edges from the same source type appear contiguously.

**Detail level:** edges sorted by `(source_type_qualified_name, source_entity_name, target_qualified_name, relationship)`. Edges from the same source type are grouped; edges from the same source entity are sub-grouped; within a source/target pair, relationship kinds are alphabetical.

### Cursor protocol

Standard Hierograph cursor protocol. Stateless, self-validating, base64-encoded JSON with version, tool name, query hash, data hash, and offset.

## Input validation

**Invalid node kind at type level.** Method and field IDs are rejected with `INVALID_NODE_KIND` error including the declaring type.

**`relationship` at type level.** Returns:

```json
{
  "error": {
    "code": "INVALID_PARAMETER",
    "message": "The 'relationship' parameter is only valid at detail_level='detail'. At type level, edges carry kind flags (depends_on, extends, implements, annotated_by) but cannot be filtered by detail-level relationship.",
    "recovery": "Either remove the 'relationship' parameter, or set detail_level='detail' to filter by relationship kind."
  }
}
```

**Unknown relationship kind.** Returns a structured error listing the valid kinds from the provider's vocabulary.

**Unknown node ID.** Returns `NODE_NOT_FOUND` error.

**Invalid cursor.** Standard cursor error responses per the pagination protocol.

## Architecture

### At `detail_level: "type"` (in-memory)

Operates on the in-memory type-level dependency graph:

1. Expand `from_id` and `to_id` to their contained types
2. Query the in-memory edge set for edges from source types to target types
3. Sort by iteration order, compute summary, slice for page

No Neo4j queries. Microseconds to low milliseconds.

### At `detail_level: "detail"` (Neo4j via provider)

Uses the `DetailDependencyProvider` from the provider layer:

1. Expand `from_id` and `to_id` to their contained types
2. Call `provider.detailDependency.detailEdgeQuery(relationship, fromTypeIds, toTypeIds)`
3. The provider translates to scanner-specific Cypher and returns results in Hierograph domain terms
4. Sort by iteration order, compute summary, slice for page

The tool layer never sees Cypher or scanner-specific labels. The `DetailDependencyProvider` encapsulates the query and maps results to Hierograph's relationship vocabulary.

## Use cases

- **"What types in module A depend on types in module B?"** — `outgoing_dependencies(from_id: A, to_id: B)` (type level, default)
- **"What methods in class X call methods in class Y?"** — `outgoing_dependencies(from_id: X, to_id: Y, detail_level: "detail", relationship: "calls")`
- **"Show me all detail-level evidence between these two packages"** — `outgoing_dependencies(from_id: pkg_A, to_id: pkg_B, detail_level: "detail")`
- **"Which types in this package extend types in that package?"** — `outgoing_dependencies(from_id: pkg_A, to_id: pkg_B)` at type level, inspect `attributes.is_extends`

## LLM tool description

The `@Tool` description should communicate:

1. This is the evidence tool — use after an aggregated query reveals a dependency of interest
2. `detail_level: "type"` (default) returns type-to-type edges from the in-memory model (fast)
3. `detail_level: "detail"` returns method/field-level edges with source locations (slower, from Neo4j)
4. The `relationship` filter is only valid at detail level
5. Results are paginated; summaries (`by_attribute`/`by_relationship`, `by_source_type`) give the shape without needing to process all edges
6. Direction: this tool shows what the source uses of the target. For the reverse, use `incoming_dependencies`
