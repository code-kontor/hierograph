# `find_dependency_path`

**Category:** Reachability and impact
**Result-size class:** Input-bounded (no pagination needed)

## Purpose

Returns paths in the type-level dependency graph from a source to a target. Each path is a sequence of types connected by type-level edges, with weight per edge. This answers the question "why does A end up depending on B?" by showing the concrete chain of types that connects them.

The tool accepts input at any subtree level (module, package, or type) and expands higher-level inputs to the contained types internally. When either endpoint is a module or package, the tool finds paths between *any* type in the source subtree and *any* type in the target subtree, with explicit endpoints on each returned path.

## Signature

```
find_dependency_path(
    from_id: long,                              // required: module, package, or type
    to_id: long,                                // required: module, package, or type
    max_paths: int = 5,                         // optional
    max_length: int?                            // optional cap on path length
)
```

### Parameters

**`from_id`** (long, required)
Source subtree root. Accepts module, package, or type IDs.

**`to_id`** (long, required)
Target subtree root. Same kind constraints as `from_id`.

**`max_paths`** (int, optional, default 5)
Maximum number of distinct paths to return. Most use cases want the few shortest paths, not an exhaustive enumeration. Capped at 20 server-side.

**`max_length`** (int, optional, default unbounded)
Maximum path length in hops. Useful when the LLM wants to find only short connections ("are these two types within 3 steps of each other?").

## Response shape

Uses **slim payload encoding** — types appear as path steps across multiple paths.

```json
{
  "nodes": {
    "47291": { "name": "ClusterService", "qualified_name": "org.elasticsearch.cluster.ClusterService", "kind": "java.class" },
    "48102": { "name": "ClusterStateObserver", "qualified_name": "org.elasticsearch.cluster.ClusterStateObserver", "kind": "java.class" },
    "52103": { "name": "TransportService", "qualified_name": "org.elasticsearch.transport.TransportService", "kind": "java.class" }
  },
  "paths": [
    {
      "length": 2,
      "total_weight": 11,
      "steps": [
        { "from": 47291, "to": 48102, "weight": 8 },
        { "from": 48102, "to": 52103, "weight": 3 }
      ]
    },
    {
      "length": 3,
      "total_weight": 18,
      "steps": [
        { "from": 47291, "to": 49001, "weight": 5 },
        { "from": 49001, "to": 49050, "weight": 7 },
        { "from": 49050, "to": 52103, "weight": 6 }
      ]
    }
  ],
  "summary": {
    "from": 47291,
    "to": 52103,
    "path_count": 2,
    "shortest_length": 2,
    "exists": true
  }
}
```

### Path fields

**`length`** — number of hops in this path.

**`total_weight`** — sum of edge weights along the path.

**`steps`** — ordered list of edges. Each step has `from`, `to` (type IDs referencing the `nodes` map), and `weight`.

### Summary fields

**`from`** / **`to`** — the actual source and target type IDs used (after subtree expansion, these identify specific types, not the input module/package).

**`path_count`** — number of paths returned.

**`shortest_length`** — length of the shortest path found.

**`exists`** — `true` if at least one path was found. When `false`, `paths` is empty — "no dependency relationship" is a meaningful answer, not an error.

### Result ordering

Paths are sorted by:
1. Length (shortest first)
2. Total edge weight (heaviest first within the same length)

The LLM gets the most direct, strongest connections first.

## Input validation

**Invalid node kind.** Method and field IDs are rejected with `INVALID_NODE_KIND` error including the declaring type.

**Unknown node ID.** Returns `NODE_NOT_FOUND` error.

**No path exists.** Returns `paths: []` with `summary.exists: false`. Not an error.

**Same source and target.** Returns `paths: []` with `summary.exists: false`. A node does not have a dependency path to itself.

## Architecture

`find_dependency_path` operates entirely on the **in-memory hierarchical model**. The type-level dependency graph in memory supports efficient path-finding via BFS.

1. Expand `from_id` and `to_id` to contained types (if module or package)
2. BFS from source types toward target types over the in-memory type-level dependency graph
3. Collect distinct shortest paths up to `max_paths`, respecting `max_length`
4. Sort by length, then by total weight
5. Register all path-step types in the `nodes` map

No Neo4j queries. Path-finding is microseconds to low milliseconds on the in-memory graph.

### Input-bounded result size

The result is bounded by `max_paths` (default 5, cap 20). Each path is bounded by `max_length` or the graph diameter. The LLM controls result size through these parameters, so no pagination is needed.

## Use cases

- **"Why does class A end up depending on class B?"** — `find_dependency_path(from_id: A, to_id: B)`
- **"Is there any dependency from the API layer to the database layer?"** — `find_dependency_path(from_id: api_module_id, to_id: db_module_id)` (tool expands internally)
- **"Show me the shortest connections between these two packages"** — `find_dependency_path(from_id: pkg_A, to_id: pkg_B, max_paths: 10)`
- **"Are these two types within 3 steps of each other?"** — `find_dependency_path(from_id: A, to_id: B, max_length: 3)`, check `summary.exists`

## LLM tool description

The `@Tool` description should communicate:

1. Finds transitive dependency paths between two subtrees in the type-level graph
2. Returns the shortest paths with concrete type-level steps and weights
3. An empty result (`exists: false`) is definitive — no dependency chain of any length exists
4. Accepts modules, packages, or types; expands internally
5. Use `max_paths` and `max_length` to control result scope
6. For blast-radius analysis (all things affected), use `affected_by` instead
