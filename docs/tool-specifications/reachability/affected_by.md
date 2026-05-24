# `affected_by`

**Category:** Reachability and impact
**Result-size class:** Data-bounded (cursor-based pagination)

## Purpose

Returns the types transitively connected to the input via type-level dependencies. This is the blast-radius tool: "what breaks if I change this?" (incoming direction) or "what does this rely on?" (outgoing direction).

Each result is a type enriched NodeRef with coupling distance, a source count indicating how many types in the input subtree reach this affected type, and a representative path showing how the dependency chain flows.

The tool accepts input at any subtree level (module, package, or type) and expands higher-level inputs to the contained types internally. The LLM doesn't need to enumerate types itself — the natural question "what's affected by changes to this package?" works directly.

## Signature

```
affected_by(
    node_id: long,                              // required: module, package, or type
    direction: "outgoing" | "incoming" = "incoming",
    max_depth: int?,                            // optional, default unbounded
    kind_filter: string[]?,                     // optional filter on result type kinds
    limit: int = 100,                           // optional
    cursor: string?                             // for pagination
)
```

### Parameters

**`node_id`** (long, required)
The node being analyzed. Accepts module, package, or type IDs. If a module or package, the tool expands internally to all contained types and returns the union of affected types across all sources.

**`direction`** (string, optional, default `"incoming"`)
- `"incoming"` (default) — return types that depend on the input, transitively. The "what breaks if I change this?" use case.
- `"outgoing"` — return types that the input depends on, transitively. The "what does this rely on?" use case.

**`max_depth`** (int, optional, default unbounded)
Caps traversal depth in type-level dependency hops. Unbounded by default. Set this to limit results to "things within N steps."

**`kind_filter`** (string[], optional)
Filters the *results* (returned affected types), not the traversal. Useful for narrowing to "only classes" or "only interfaces." Accepts specific kinds and group aliases.

**`limit`** (int, optional, default 100)
Maximum items per page. Default 100 is calibrated to ~45 KB (~11K tokens) per page at ~450 bytes per item.

Server-side cap: 350.

**`cursor`** (string, optional)
Opaque cursor from a previous response's `next_cursor`.

## Response shape

```json
{
  "source": {
    "id": 47291,
    "name": "ClusterService",
    "qualified_name": "org.elasticsearch.cluster.ClusterService",
    "kind": "java.class",
    "parent_id": 12503,
    "parent_kind": "java.package"
  },
  "direction": "incoming",
  "results": [
    {
      "node": {
        "id": 48102,
        "name": "ClusterStateObserver",
        "qualified_name": "org.elasticsearch.cluster.ClusterStateObserver",
        "kind": "java.class",
        "parent_id": 12503,
        "parent_kind": "java.package",
        "modifiers": ["public"],
        "member_count": 12,
        "method_count": 10,
        "field_count": 2,
        "annotation_count": 0,
        "interface_count": 0,
        "is_abstract": false,
        "is_generic": false,
        "parent_type": null
      },
      "distance": 1,
      "source_count": 1,
      "via": [
        { "from": 47291, "to": 48102, "weight": 8 }
      ]
    },
    {
      "node": { ... },
      "distance": 2,
      "source_count": 1,
      "via": [
        { "from": 47291, "to": 48102, "weight": 8 },
        { "from": 48102, "to": 49001, "weight": 3 }
      ]
    }
  ],
  "summary": {
    "total": 87,
    "returned": 87,
    "truncated": false,
    "by_distance": {
      "1": 12,
      "2": 28,
      "3": 31,
      "4": 16
    },
    "by_parent_module": [
      { "id": 1001, "name": "elasticsearch-server", "count": 54 },
      { "id": 1002, "name": "elasticsearch-core", "count": 33 }
    ]
  }
}
```

### Result fields

**`node`** — enriched NodeRef of the affected type.

**`distance`** — the *minimum* coupling distance from any type in the input subtree. 1 = direct dependent.

**`source_count`** — how many types in the input subtree reach this affected type. Higher means more coupled to the input. For single-type input, always 1.

**`via`** — one representative path from a source type in the input subtree to the affected type. Each step has `from`, `to`, and `weight`. The path demonstrates *how* the dependency chain flows, not every possible path.

### Summary fields

**`total`** — true count of all affected types (across all pages).

**`returned`** — number of items in this page.

**`truncated`** — `true` if more pages exist.

**`by_distance`** — distribution of affected types by coupling distance. Computed over the full result set.

**`by_parent_module`** — top modules by affected type count. Lets the LLM see which modules are most impacted without processing individual results. Capped at top 10.

## Pagination

### Iteration order

Affected types sorted by `(distance ascending, qualified_name alphabetical)`. The closest-affected types appear first, with ties broken alphabetically. This puts the highest-priority results (closest coupling) at the start.

### Cursor protocol

Standard Hierograph cursor protocol.

## Input validation

**Invalid node kind.** Method and field IDs are rejected with `INVALID_NODE_KIND` error including the declaring type.

**Unknown node ID.** Returns `NODE_NOT_FOUND` error.

**Invalid `kind_filter`.** Structured error listing valid kinds and aliases.

**No affected types.** Returns empty results with `total: 0`. Not an error — "nothing depends on this" is a meaningful answer.

## Architecture

`affected_by` operates entirely on the **in-memory hierarchical model**. The type-level dependency graph in memory is optimized for exactly this kind of traversal.

1. Expand `node_id` to contained types (if module or package)
2. BFS/DFS traversal over the in-memory type-level dependency graph in the specified direction
3. Visited-set deduplication: each type appears once at its shortest reachable distance
4. Exclude types within the input subtree itself (the source's own types are not "affected by" themselves)
5. Apply `kind_filter` to results
6. Sort by iteration order, compute summary, slice for page

No Neo4j queries. Traversal is microseconds to low milliseconds.

## Use cases

- **"What breaks if I change this class?"** — `affected_by(node_id: class_id)` (direction=incoming, default)
- **"What breaks if I refactor this package?"** — `affected_by(node_id: package_id)` (tool expands internally)
- **"What does this class transitively rely on?"** — `affected_by(node_id: class_id, direction: "outgoing")`
- **"Which modules are affected by changes to this module?"** — `affected_by(node_id: module_id)`, read `summary.by_parent_module`
- **"What's within 2 hops of this class?"** — `affected_by(node_id: class_id, max_depth: 2)`

## LLM tool description

The `@Tool` description should communicate:

1. This is the blast-radius / transitive-dependency tool
2. Default direction is `"incoming"` — "what breaks if I change this?"
3. Accepts modules, packages, or types; expands higher-level inputs internally
4. Each result carries `distance` and `source_count` for prioritization
5. The summary (`by_distance`, `by_parent_module`) often answers the question without processing individual results
6. Results are paginated; use `max_depth` or `kind_filter` to narrow if the result set is too large
