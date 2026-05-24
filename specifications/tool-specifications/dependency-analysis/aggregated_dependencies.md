# `aggregated_dependencies`

**Category:** Aggregated dependency analysis
**Result-size class:** Input-bounded (no pagination needed)

## Purpose

Returns the aggregated dependency edges from a set of source subtrees to a set of target subtrees. Each edge represents the total dependency from one specific source to one specific target, with weight, type pair count, and type-level edge kind flags.

This is the general-purpose aggregation tool. It consolidates the previous `aggregated_outgoing`, `aggregated_incoming`, `dependency_between`, `outgoing_to`, and `incoming_from` tools into a single symmetric interface. The directional concept (outgoing vs. incoming) is implicit in how the LLM populates `source_ids` and `target_ids` — there is no direction parameter.

Aggregation is always **pairwise**. Given two subtrees A and B, there is exactly one aggregated edge from A to B. This tool computes that for every (source, target) pair in the cross product of the input sets. Pairs with no dependency are omitted from the response (not returned with zero weight).

## Signature

```
aggregated_dependencies(
    source_ids: long[],     // required: 1 or more source subtree IDs
    target_ids: long[]      // required: 1 or more target subtree IDs
)
```

### Parameters

**`source_ids`** (long[], required)
One or more node IDs identifying the source subtrees. Each ID can be a module, package, or type. The tool expands each to its contained types internally.

**`target_ids`** (long[], required)
One or more node IDs identifying the target subtrees. Same kind constraints as `source_ids`.

No `limit` parameter. The result size is fully bounded by the input: at most `|source_ids| x |target_ids|` edges. The LLM controls result size by controlling input size.

### Input validation

**Cross-product cap.** The product `|source_ids| x |target_ids|` is capped at 2500. Inputs exceeding this return a structured error:

```json
{
  "error": {
    "code": "INPUT_TOO_LARGE",
    "message": "The cross product of source_ids (120) x target_ids (30) = 3600 exceeds the maximum of 2500.",
    "source_count": 120,
    "target_count": 30,
    "cross_product": 3600,
    "max_cross_product": 2500,
    "recovery": "Narrow either source_ids or target_ids. For all-pairs analysis within a node set, use pairwise_dependencies instead."
  }
}
```

This is a defensive cap against degenerate inputs, not a behavioral limit — realistic workflows are well below it.

**Invalid node kind.** Method and field IDs are rejected with a structured error including the declaring type, so the LLM can recover in one step:

```json
{
  "error": {
    "code": "INVALID_NODE_KIND",
    "message": "This tool operates on type-level dependencies. The node 'org.elasticsearch.cluster.ClusterService.applyState' is a method, not a type.",
    "actual_kind": "java.method",
    "declaring_type": {
      "id": 47291,
      "name": "ClusterService",
      "qualified_name": "org.elasticsearch.cluster.ClusterService",
      "kind": "java.class"
    },
    "recovery": "To query dependencies involving this method's declaring type, pass id=47291. To query method-level evidence directly, use outgoing_dependencies or incoming_dependencies with detail_level='detail'."
  }
}
```

**Unknown node ID.** Returns `NODE_NOT_FOUND` error with recovery pointing to `find_node`.

## Response shape

Uses **slim payload encoding** — nodes appear as edge endpoints across multiple edges, so deduplication applies.

```json
{
  "nodes": {
    "1001": { "name": "elasticsearch-server", "qualified_name": "org.elasticsearch:elasticsearch-server", "kind": "java.module" },
    "1002": { "name": "elasticsearch-core", "qualified_name": "org.elasticsearch:elasticsearch-core", "kind": "java.module" },
    "1003": { "name": "elasticsearch-x-content", "qualified_name": "org.elasticsearch:elasticsearch-x-content", "kind": "java.module" }
  },
  "edges": [
    {
      "from": 1001,
      "to": 1002,
      "weight": 247,
      "type_pair_count": 38,
      "kinds": ["depends_on", "extends", "implements"]
    },
    {
      "from": 1001,
      "to": 1003,
      "weight": 91,
      "type_pair_count": 14,
      "kinds": ["depends_on", "annotated_by"]
    }
  ],
  "summary": {
    "total_pairs_requested": 6,
    "pairs_with_dependency": 2,
    "pairs_without_dependency": 4
  }
}
```

### Edge fields

**`from`** — source node ID (references the `nodes` map)

**`to`** — target node ID (references the `nodes` map)

**`weight`** — total number of type-level dependency edges between the source and target subtrees. Higher weight indicates stronger coupling.

**`type_pair_count`** — number of distinct (source type, target type) pairs that contribute to this aggregated edge. A high type pair count with moderate weight means the coupling is spread across many type pairs; a low type pair count with high weight means the coupling is concentrated in a few type pairs.

**`kinds`** — set of type-level edge kind flags present in this aggregated edge. Values: `depends_on`, `extends`, `implements`, `annotated_by`. Multiple flags can be true simultaneously.

### Summary fields

**`total_pairs_requested`** — `|source_ids| x |target_ids|`

**`pairs_with_dependency`** — number of pairs that have at least one dependency edge

**`pairs_without_dependency`** — `total_pairs_requested - pairs_with_dependency`

The summary gives the LLM honest accounting of what it asked for vs. what it got.

## Architecture

`aggregated_dependencies` operates entirely on the **in-memory hierarchical model**. For each (source, target) pair:

1. Expand each ID to its contained types (if the ID is a module or package)
2. Query the in-memory type-level dependency graph for edges between the source types and target types
3. Aggregate: sum weights, collect distinct type pairs, union the kind flags

No Neo4j queries. Response assembly is microseconds to low milliseconds.

## Use cases

- **"What does module A depend on?"** — `aggregated_dependencies(source_ids: [A], target_ids: [X, Y, Z])` where targets come from `list_children` or `graph_overview`
- **"What depends on module A?"** — `aggregated_dependencies(source_ids: [X, Y, Z], target_ids: [A])`. Same pattern, reversed.
- **"How coupled are A and B?"** — `aggregated_dependencies(source_ids: [A], target_ids: [B])`. Single-result query.
- **"Among these N modules, what depends on what?"** — consider using `pairwise_dependencies` for matrix-shaped results instead.

## LLM tool description

The `@Tool` description should communicate:

1. Returns aggregated pairwise dependencies from sources to targets
2. Direction is implicit: put the depender in `source_ids`, the depended-on in `target_ids`
3. No limit needed — result size is controlled by input size
4. Pairs with no dependency are omitted; check the summary for honest accounting
5. For matrix-style all-pairs analysis, use `pairwise_dependencies` instead
6. For type-level or detail-level evidence between a specific pair, use `outgoing_dependencies` or `incoming_dependencies`
