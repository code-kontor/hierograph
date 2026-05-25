# `outgoing_dependencies` at detail level

> **Implementation note:** This spec and `outgoing_dependencies_type.md` describe two levels of the same tool. They are split into separate spec files for clarity, but must be implemented as a **single `outgoing_dependencies` MCP tool** with a `detail_level` parameter that switches between them.

**Category:** Detail-level dependency evidence
**Result-size class:** Data-bounded (cursor-based pagination)

## Purpose

Returns method/field-level edges from a source subtree to a target subtree, with source locations. This is the detail-level evidence tool: given that a type-level query revealed coupling between specific types, `outgoing_dependencies` at detail level shows the concrete method calls, field reads, annotations, etc. that constitute it.

## Signature

```
outgoing_dependencies(
    from_id: long,                              // required
    to_id: long,                                // required
    detail_level: "detail",                     // required for this spec
    relationship: string?,                      // optional filter
    limit: int = 80,                            // optional
    cursor: string?                             // for pagination
)
```

### Parameters

**`from_id`** (long, required)
Source subtree root. Accepts module, package, type, method, or field IDs.

**`to_id`** (long, required)
Target subtree root. Same kind constraints as `from_id`.

**`detail_level`** (string, required)
Must be `"detail"` for this spec.

**`relationship`** (string, optional)
Filters to a specific detail-level relationship kind.

Valid values (surfaced via `graph_overview`):
- Method-originated: `throws`, `calls`, `returns`, `parameter_type`, `reads_field`, `writes_field`, `overrides`, `annotated_by`, `parameter_annotated_by`
- Field-originated: `has_type`, `annotated_by`, `read_by`, `written_by`

Unknown values return a structured error listing the valid kinds.

**`limit`** (int, optional, default 80)
Maximum items per page. Default 80 (~550 bytes/edge, ~44 KB). Server-side cap: 250.

**`cursor`** (string, optional)
Opaque cursor from a previous response's `next_cursor`.

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
  }
}
```

### Edge fields

**`from`** / **`to`** — source and target entity IDs (method, field, or type; reference the `nodes` map).

**`from_parent`** / **`to_parent`** — declaring type IDs of the source and target entities. Present for navigation back to the type level. When the entity is itself a type, `from_parent` or `to_parent` equals `from` or `to`.

**`relationship`** — the detail-level relationship kind (e.g., `calls`, `reads_field`, `annotated_by`).

**`source_file`** — relative file path of the source location.

**`source_line`** — line number in the source file.

### Summary fields

**`total`** — true count of all matching edges (across all pages).

**`returned`** — number of edges in this page.

**`truncated`** — `true` if more pages exist.

**`by_relationship`** — distribution of edges by relationship kind, computed over the full result set. Each edge has exactly one relationship kind.

**`by_source_type`** — top source types by edge count, computed over the full result set.

## Pagination

### Iteration order

Edges sorted by `(source_type_qualified_name, source_entity_name, target_qualified_name, relationship)`. Edges from the same source type are grouped; edges from the same source entity are sub-grouped; within a source/target pair, relationship kinds are alphabetical.

### Cursor protocol

Standard Hierograph cursor protocol.

## Input validation

**Unknown relationship kind.** Returns a structured error listing the valid kinds from the provider's vocabulary.

**Unknown node ID.** Returns `NODE_NOT_FOUND` error.

**Invalid cursor.** Standard cursor error responses per the pagination protocol.

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
