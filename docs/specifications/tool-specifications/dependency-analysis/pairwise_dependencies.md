# `pairwise_dependencies`

**Category:** Aggregated dependency analysis
**Result-size class:** Hybrid — input-bounded summary (`O(node_count)`), data-bounded edge list (paginated)

## Purpose

Returns the dependency matrix among a set of subtrees — all pairwise aggregated dependencies within the input set, shaped for matrix consumption. This is the DSM (Dependency Structure Matrix) tool, optimized for architectural analysis questions where the all-pairs coupling structure matters.

Structurally similar to `aggregated_dependencies(source_ids: node_ids, target_ids: node_ids)`, but the response includes matrix-style structural insights: density, cycle detection, strongly connected components, and topological order. These server-computed analytics answer many architectural questions directly from the summary, without the LLM needing to process the edge list.

The response has two parts with different size behavior, and the tool treats them differently (this is what makes it *hybrid* rather than purely input-bounded):

- The **summary** is `O(node_count)` — the SCCs partition the nodes and the topological order has one entry per node. It is computed over the *entire* node set and returned on the first page. This is the authoritative, global answer to cycle / layering / density questions.
- The **edge list** is `O(edges)`. Real module graphs are sparse — the edge count is far below `node_count²` — but it is bounded by how coupled the subtrees are, not by the input, so it is **paginated** (`limit` / `cursor`) and can be thinned server-side with `min_weight`.

This split is deliberate. Computing cycles, SCCs, and a global topological order is only valid over the *complete* node set; capping the node count (as an earlier draft did at 50 "for matrix usability") would force callers to slice a large system into chunks, which hides any cycle that spans two chunks and produces no global layering. Since no other tool computes SCC / topological order, that cap closed off the single most valuable thing this tool produces. Pagination on the edge list — not a cap on the node set — is the right protection. See *Architecture* and *Pagination* below.

## Signature

```
pairwise_dependencies(
    node_ids: long[],                              // required: 2+ subtree IDs (soft cap ~1000)
    direction: "outgoing" | "incoming" | "both" = "both",
    edge_sort: "dsm" | "weight_desc" = "dsm",      // ordering of the paginated edge list
    min_weight: int = 1,                           // drop edges below this weight server-side
    limit: int = 200,                              // page size for the edge list
    cursor: string?                                // pagination cursor; omit for the first page
)
```

### Parameters

**`node_ids`** (long[], required)
The set of subtree IDs to analyze pairwise. Each ID can be a module, package, or type. Requires at least 2 IDs.

The upper bound is a **soft cap (~1000)** whose only purpose is to bound the `O(node_count)` summary payload (the `nodes` map and `topological_order`) — *not* the edge list, which is paginated. Every realistic module- or package-level DSM is far below it; a whole-system view of, say, 68 modules passes in a single call. This replaces the earlier "max 50" behavioral cap, which throttled the cheap summary and made whole-system cycle/layering analysis impossible.

Typical input: the children of a module, or *all* top-level modules from `graph_overview`.

**`direction`** (string, optional, default `"both"`)
Controls which edges are included:
- `"both"` — includes edges in both directions (A→B and B→A). The standard DSM view.
- `"outgoing"` — only edges where the row node depends on the column node.
- `"incoming"` — only edges where the column node depends on the row node.

**`edge_sort`** (string, optional, default `"dsm"`)
Ordering of the paginated edge list. The summary is unaffected; only the order in which edges are streamed changes.
- `"dsm"` — matrix reading order: by `(from, to)` using each node's index in the matrix's computed node order (row-major, base modules first). This lets a consumer render the matrix incrementally, page by page. That order equals `topological_order` when the graph is acyclic; when it has cycles (no topological order exists) the matrix still produces a deterministic order (the FAS-minimizing order that places the fewest edges above the diagonal), and `dsm` uses that — never raw input order.
- `"weight_desc"` — heaviest coupling first, tie-broken by `(from, to)`. Lets the LLM read the structural hotspots first and stop paging once weights fall below architectural significance.

**`min_weight`** (int, optional, default `1`)
Drops edges with `weight < min_weight` from the **edge list**, server-side, before pagination. Most DSM noise is incidental weight-1/2 edges (a stray import, a single `extends`); raising `min_weight` collapses that long tail and, for sparse graphs, often returns the whole matrix in one page. Does **not** affect the summary analytics — `has_cycles`, SCCs, `topological_order`, and `density` are always computed over the full, unfiltered edge set, so filtering for readability can never change the cycle/layering verdict. `edge_count` reports the full count; `returned_edge_count` reports the post-filter total (see *Summary fields*).

**`limit`** (int, optional, default `200`)
Page size for the edge list. Honest truncation: when more edges remain, `next_cursor` is non-null. Follows the standard pagination protocol (`hierograph-pagination.md`).

**`cursor`** (string, optional)
Opaque pagination cursor from a prior response's `next_cursor`. Omit for the first page. The summary is returned **only on the first page** (cursor absent); subsequent pages omit it to avoid repeating the potentially large `topological_order` array. See *Pagination*.

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

**Too many nodes.** The node set is bounded only by the *soft cap* (~1000) that keeps the `O(node_count)` summary payload manageable; the edge list never drives this limit because it is paginated. Exceeding the soft cap returns:

```json
{
  "error": {
    "code": "INPUT_TOO_LARGE",
    "message": "pairwise_dependencies accepts at most 1000 node IDs (the summary — SCCs and topological order — is O(node_count)), got 1342.",
    "node_count": 1342,
    "max_nodes": 1000,
    "recovery": "Narrow to the subtrees you actually want to lay out (e.g. one level of list_children), or use aggregated_dependencies with explicit source_ids and target_ids for an asymmetric slice. The edge list itself is paginated, so node count is the only thing to reduce."
  }
}
```

Note the contrast with the old behavior: a 68-module whole-system DSM — which the previous 50-node cap rejected — is now a normal single call. The cap exists only as a backstop against pathologically large node sets, not as a routine constraint.

**Invalid node kind.** Method and field IDs are rejected with the same `INVALID_NODE_KIND` structured error as `aggregated_dependencies`, including the declaring type for one-step recovery.

**Unknown node ID.** Returns `NODE_NOT_FOUND` error with recovery pointing to `find_node`.

## Response shape

Uses **slim payload encoding** — each node appears as both edge endpoints and in structural summaries.

The example below is a **first page** (no `cursor` supplied), so it carries both the `summary` and the `nodes` map. Continuation pages (fetched with a `cursor`) carry only `edges` and `next_cursor` — the `summary` and `nodes` map are omitted, since they don't change between pages and the `topological_order` array can be large.

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
    "returned_edge_count": 3,
    "possible_edges": 6,
    "density": 0.5,
    "has_cycles": false,
    "topological_order": [1003, 1002, 1001],
    "strongly_connected_components": []
  },
  "next_cursor": null
}
```

When the edge list spans multiple pages, the first page looks the same but with a non-null `next_cursor`; a continuation page is just:

```json
{
  "edges": [ ... up to `limit` more edges, in `edge_sort` order ... ],
  "next_cursor": "eyJ..."
}
```

### Edge fields

Same as `aggregated_dependencies`: `from`, `to`, `weight`, `type_pair_count`, `attributes`.

Edge order is determined by `edge_sort` and is **stable across pages** (it has to be, for the cursor to be coherent):
- `"dsm"` (default) — by `(from, to)` using each node's index in the matrix's computed node order, i.e. matrix reading order with base modules first. That index equals the `topological_order` position when acyclic; when cyclic, the matrix's deterministic FAS-minimizing order is used.
- `"weight_desc"` — descending `weight`, tie-broken by `(from, to)`.

### Summary fields

The summary is computed over the **complete** node set and the **full, unfiltered** edge set, and is returned on the first page only.

**`node_count`** — number of nodes in the analysis set.

**`edge_count`** — number of edges with non-zero weight in the full graph, *before* any `min_weight` filtering. This is the true coupling count and the basis for `density` and for how many edges pagination will stream when `min_weight` is `1`.

**`returned_edge_count`** — number of edges that survive the `min_weight` filter and will therefore be paged through. Equals `edge_count` when `min_weight` is `1` (the default). The number of pages is `ceil(returned_edge_count / limit)`.

**`possible_edges`** — maximum possible directed edges (`node_count * (node_count - 1)` when excluding self-loops).

**`density`** — `edge_count / possible_edges`, rounded to 2 decimal places. A measure of overall coupling within the set. 0.0 = no coupling; 1.0 = fully coupled. Computed from the unfiltered `edge_count`, so it is independent of `min_weight`.

**`has_cycles`** — `true` if any dependency cycle exists among the input nodes. The headline answer for "is there a cycle?" questions.

**`topological_order`** — a topological ordering of the nodes (list of IDs). Present only when `has_cycles` is `false`. When `true`, the field is absent — a topological order doesn't exist for cyclic graphs. The ordering represents a valid layering: nodes earlier in the list depend on nothing later in the list.

**`strongly_connected_components`** — list of SCCs, each an array of node IDs. Only includes SCCs with 2+ members (single-node "trivial" SCCs are omitted). Empty when `has_cycles` is `false`. When cycles exist, each SCC identifies a group of mutually dependent nodes — the cycle participants.

## Architecture

`pairwise_dependencies` operates entirely on the **in-memory hierarchical model**:

1. Expand each input ID to its contained types
2. Build the aggregated edge set among the node set. This is one pass that buckets each underlying type-level edge into its `(source-subtree, target-subtree)` cell — roughly `O(type-level edges touched)`, **not** `O(node_count²)`. The quadratic term is only the *possible* pairs, which are never materialized for empty cells.
3. Compute structural analytics (density, cycles, SCCs, topological order) over the **full** edge set — Tarjan's SCC and topological sort, both `O(V + E)`
4. Order the edges by `edge_sort`, apply `min_weight`, then slice the requested page (`limit` / `cursor`)

The structural analytics are computed server-side because they're cheap and tedious for the LLM to derive from an edge list. For many architectural questions, the summary alone is the answer — which is exactly why it is computed over the whole node set and never gated behind pagination.

Nothing here is expensive in `node_count`: aggregation is linear in the edges actually present, and the analytics are linear in `V + E`. The only thing that grows with `node_count` is the *serialized summary* (the `nodes` map and `topological_order`), which is what the soft cap protects. The genuinely unbounded output is the edge list, which is why that — and only that — is paginated.

No Neo4j queries. Per-page response assembly is microseconds to low milliseconds.

## Performance

End-to-end cost of building and serving a request, where `V` is the node-set size and `E` the number of aggregated edges among them:

| Stage | Complexity |
|---|---|
| Aggregation build (single bucketing pass) | `O(V + E)` |
| SCC detection (Tarjan) | `O(V + E)` |
| Per-SCC layering (greedy feedback-arc-set) | `O(Σ sccᵢ²)` |
| Ordering assembly | `O(V)` |
| Edge ordering + `min_weight` filter + page slice | `O(E log E)` + `O(page)` |

The only super-linear term is the per-SCC `O(sccᵢ²)` layering, confined to actual cycle clusters; for the common (mostly acyclic) case it is negligible, and a heavily entangled set is surfaced by `has_cycles` / a large SCC in the summary before any matrix is rendered. This is why the node set scales to the ~1000 soft cap on real, sparse graphs. The algorithm internals (adjacency bucketing, Tarjan SCC, the greedy FAS layering, and the cached weight matrix) are specified in `hierarchicalgraph-specifications/hierarchicalgraph-core-spec.md`.

## Pagination

The edge list follows the standard pagination protocol (`hierograph-pagination.md`), with two specifics worth stating here:

**Summary on the first page only.** A request with no `cursor` returns `summary` + `nodes` + the first page of `edges` + `next_cursor`. A request with a `cursor` returns only `edges` + `next_cursor`. The summary is global and unchanging across pages, and `topological_order` can be large, so repeating it on every page would defeat the purpose.

**Cursor stability.** Pagination requires a total order that is stable between calls:
- The loaded graph is an **immutable snapshot** for the life of the session (no incremental rescans mid-pagination), so the `edge_sort` order is deterministic.
- The cursor embeds a **graph/version hash**. If a cursor is presented against a graph that has since been reloaded, the tool returns a `STALE_CURSOR` error directing the LLM to restart the query, rather than silently returning a misaligned page.
- Both `edge_sort` orders are *total* (`weight_desc` is tie-broken by `(from, to)`), so no two edges share a cursor position.

**Degenerate density.** A near-fully-connected node set (`density → 1`) produces `O(node_count²)` edges and therefore many pages. This is allowed but rarely useful: when it happens, the summary has already reported `has_cycles: true` with one large SCC, which is the real answer — there is no clean layering to render. Pagination degrades gracefully (the LLM stops after the summary) instead of the request failing outright; `min_weight` is the lever to thin such a matrix to its load-bearing edges.

## Use cases

- **"Show me the DSM for the top-level modules"** — `pairwise_dependencies(node_ids: [module IDs from graph_overview])`
- **"Are there any dependency cycles across the *whole* system?"** — pass every module in one call (the soft cap comfortably covers a 68-module system); read `summary.has_cycles` and `summary.strongly_connected_components`. This is the case the old 50-node cap made impossible, because a cycle spanning two slices is invisible to a sliced analysis.
- **"What's a valid layering for these modules?"** — same call, read `summary.topological_order`
- **"How tightly coupled is this set of packages?"** — `pairwise_dependencies(node_ids: [package IDs])`, read `summary.density`
- **"What's the heaviest coupling here, ignoring the noise?"** — `pairwise_dependencies(node_ids: [...], edge_sort: "weight_desc", min_weight: 10)`; the first page is the structural hotspots and the long tail of incidental edges is dropped.

## When to use `pairwise_dependencies` vs `aggregated_dependencies`

| Question shape | Tool |
|---|---|
| All-pairs coupling within a set (DSM, cycles, layering) | `pairwise_dependencies` |
| Cycle / SCC / topological-order analytics over a set | `pairwise_dependencies` (the only tool that computes them) |
| One-directional: "what does A depend on?" | `aggregated_dependencies` |
| Asymmetric: different source set and target set | `aggregated_dependencies` |
| Single pair: "does A depend on B?" | `aggregated_dependencies` |
| Large symmetric set (hundreds of nodes) | `pairwise_dependencies` (node set bounded only by the ~1000 soft cap; edges paginate) |
| Asymmetric set above the cross-product cap | `aggregated_dependencies` |

## LLM tool description

The `@Tool` description should communicate:

1. This is the DSM / coupling-matrix tool — use it for all-pairs analysis within a node set, including whole-system cycle and layering checks
2. Returns a global structural summary (density, cycles, SCCs, topological order) computed over the entire node set, plus a paginated edge list
3. The summary often answers the architectural question directly — check it before processing individual edges; for cycle/layering questions it is usually the whole answer and no edges need to be read
4. The node set is bounded only by a generous soft cap (~1000); the edge list is paginated (`limit` / `cursor`) and can be thinned with `min_weight`. Pass all the modules you care about in one call — do not pre-slice to fit a node limit
5. Use `edge_sort: "weight_desc"` to surface the heaviest coupling first; use `aggregated_dependencies` for one-directional or asymmetric queries
6. For evidence of a specific dependency pair, use `outgoing_dependencies` or `incoming_dependencies`
