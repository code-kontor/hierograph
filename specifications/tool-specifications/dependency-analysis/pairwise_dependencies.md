# `pairwise_dependencies`

**Category:** Aggregated dependency analysis
**Result-size class:** Input-bounded (no pagination needed)

## Purpose

Returns the dependency matrix among a set of subtrees — all pairwise aggregated dependencies within the input set, shaped for matrix consumption. This is the DSM (Dependency Structure Matrix) tool, optimized for architectural analysis questions where the all-pairs coupling structure matters.

Structurally similar to `aggregated_dependencies(source_ids: node_ids, target_ids: node_ids)`, but the response includes matrix-style structural insights: density, cycle detection, strongly connected components, and topological order. These server-computed analytics answer many architectural questions directly from the summary, without the LLM needing to process the edge list.

## Signature

```
pairwise_dependencies(
    node_ids: long[],                  // required: 2-50 subtree IDs
    direction: "outgoing" | "incoming" | "both" = "both"
)
```

### Parameters

**`node_ids`** (long[], required)
The set of subtree IDs to analyze pairwise. Each ID can be a module, package, or type. Requires at least 2 IDs, capped at 50 for matrix usability.

Typical input: the children of a module or the top-level modules from `graph_overview`.

**`direction`** (string, optional, default `"both"`)
Controls which edges are included:
- `"both"` — includes edges in both directions (A→B and B→A). The standard DSM view.
- `"outgoing"` — only edges where the row node depends on the column node.
- `"incoming"` — only edges where the column node depends on the row node.

### Input validation

**Too few nodes.** Fewer than 2 IDs returns:

```json
{
  "error": {
    "code": "INPUT_TOO_SMALL",
    "message": "pairwise_dependencies requires at least 2 node IDs, got 1.",
    "recovery": "For single-pair queries, use aggregated_dependencies instead."
  }
}
```

**Too many nodes.** More than 50 IDs returns:

```json
{
  "error": {
    "code": "INPUT_TOO_LARGE",
    "message": "pairwise_dependencies accepts at most 50 node IDs for matrix usability, got 68.",
    "node_count": 68,
    "max_nodes": 50,
    "recovery": "Reduce the node set, or use aggregated_dependencies with explicit source_ids and target_ids for larger asymmetric queries."
  }
}
```

**Invalid node kind.** Method and field IDs are rejected with the same `INVALID_NODE_KIND` structured error as `aggregated_dependencies`, including the declaring type for one-step recovery.

**Unknown node ID.** Returns `NODE_NOT_FOUND` error with recovery pointing to `find_node`.

## Response shape

Uses **slim payload encoding** — each node appears as both edge endpoints and in structural summaries.

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
      "attributes": {
        "is_extends": true,
        "is_implements": true,
        "is_annotated_by": false,
        "is_depends_on_other": true
      }
    },
    {
      "from": 1001,
      "to": 1003,
      "weight": 91,
      "type_pair_count": 14,
      "attributes": {
        "is_extends": false,
        "is_implements": false,
        "is_annotated_by": true,
        "is_depends_on_other": true
      }
    },
    {
      "from": 1002,
      "to": 1003,
      "weight": 33,
      "type_pair_count": 7,
      "attributes": {
        "is_extends": false,
        "is_implements": false,
        "is_annotated_by": false,
        "is_depends_on_other": true
      }
    }
  ],
  "summary": {
    "node_count": 3,
    "edge_count": 3,
    "possible_edges": 6,
    "density": 0.5,
    "has_cycles": false,
    "topological_order": [1003, 1002, 1001],
    "strongly_connected_components": []
  }
}
```

### Edge fields

Same as `aggregated_dependencies`: `from`, `to`, `weight`, `type_pair_count`, `attributes`.

Edges are ordered by `(from, to)` following the node insertion order in the `nodes` map.

### Summary fields

**`node_count`** — number of nodes in the analysis set.

**`edge_count`** — number of edges with non-zero weight.

**`possible_edges`** — maximum possible directed edges (`node_count * (node_count - 1)` when excluding self-loops).

**`density`** — `edge_count / possible_edges`, rounded to 2 decimal places. A measure of overall coupling within the set. 0.0 = no coupling; 1.0 = fully coupled.

**`has_cycles`** — `true` if any dependency cycle exists among the input nodes. The headline answer for "is there a cycle?" questions.

**`topological_order`** — a topological ordering of the nodes (list of IDs). Present only when `has_cycles` is `false`. When `true`, the field is absent — a topological order doesn't exist for cyclic graphs. The ordering represents a valid layering: nodes earlier in the list depend on nothing later in the list.

**`strongly_connected_components`** — list of SCCs, each an array of node IDs. Only includes SCCs with 2+ members (single-node "trivial" SCCs are omitted). Empty when `has_cycles` is `false`. When cycles exist, each SCC identifies a group of mutually dependent nodes — the cycle participants.

## Architecture

`pairwise_dependencies` operates entirely on the **in-memory hierarchical model**:

1. Expand each input ID to its contained types
2. For each ordered pair (A, B) where A != B, query the in-memory type-level dependency graph
3. Aggregate edges (same as `aggregated_dependencies`)
4. Compute structural analytics (density, cycles, SCCs, topological order) over the resulting edge set

The structural analytics (Tarjan's SCC, topological sort) are computed server-side because they're cheap on small graphs (≤50 nodes) and expensive for the LLM to derive from an edge list. For many architectural questions, the summary alone is the answer.

No Neo4j queries. Response assembly is microseconds to low milliseconds.

## Use cases

- **"Show me the DSM for the top-level modules"** — `pairwise_dependencies(node_ids: [module IDs from graph_overview])`
- **"Are there any dependency cycles among these modules?"** — same call, read `summary.has_cycles` and `summary.strongly_connected_components`
- **"What's a valid layering for these modules?"** — same call, read `summary.topological_order`
- **"How tightly coupled is this set of packages?"** — `pairwise_dependencies(node_ids: [package IDs])`, read `summary.density`

## When to use `pairwise_dependencies` vs `aggregated_dependencies`

| Question shape | Tool |
|---|---|
| All-pairs coupling within a set (DSM, cycles, layering) | `pairwise_dependencies` |
| One-directional: "what does A depend on?" | `aggregated_dependencies` |
| Asymmetric: different source set and target set | `aggregated_dependencies` |
| Single pair: "does A depend on B?" | `aggregated_dependencies` |
| More than 50 nodes | `aggregated_dependencies` (no node-count cap, only cross-product cap) |

## LLM tool description

The `@Tool` description should communicate:

1. This is the DSM / coupling-matrix tool — use it for all-pairs analysis within a node set
2. Returns edges plus server-computed structural insights (density, cycles, SCCs, topological order)
3. The summary often answers the architectural question directly — check it before processing individual edges
4. Input is 2-50 nodes; for larger or asymmetric queries, use `aggregated_dependencies`
5. For evidence of a specific dependency pair, use `outgoing_dependencies` or `incoming_dependencies`
