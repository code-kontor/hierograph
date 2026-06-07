# `pairwise_dependencies` Pagination & DSM Efficiency

**Status:** Implemented.
This document records the behavior that was actually built for `pairwise_dependencies` and the
efficiency audit and fixes made to the DSM/SCC/FAS algorithms that back it. It is a companion to the
design specs (`tool-specifications/dependency-analysis/pairwise_dependencies.md`,
`hierarchicalgraph-specifications/hierarchicalgraph-algorithms-spec.md`, `hierograph-pagination.md`)
and reflects the code as shipped.

## 1. Motivation

`pairwise_dependencies` is the DSM / coupling-matrix tool: given a set of subtrees it returns the
pairwise dependency matrix plus server-computed structural analytics (density, cycle detection,
strongly connected components, topological order).

The original tool capped its input at **50 nodes** "for matrix usability." That cap was on the wrong
axis and had a real analytical cost: cycle detection, SCCs, and a global topological order are only
valid over the *complete* node set. Forcing a caller to slice a 68-module system into ≤50-node chunks
meant a cycle spanning two chunks was invisible to every chunk, and no global layering could be
produced. Since no other tool computes SCC/topological order, the cap closed off the single most
valuable thing this tool produces.

The fix has two parts, both implemented:

1. **Reshape the tool's protection** from a node cap to a cost-class split — keep the (small) summary
   whole, paginate the (potentially large) edge list (Section 2).
2. **Linearize the algorithms** that build the DSM so the raised node cap is honest on real graphs
   (Section 3).

## 2. Implemented tool behavior

### 2.1 Signature

```
pairwise_dependencies(
    node_ids: long[],                              // required: 2+ subtree IDs (soft cap 1000)
    direction: "outgoing" | "incoming" | "both" = "both",
    edge_sort: "dsm" | "weight_desc" = "dsm",      // ordering of the paginated edge list
    min_weight: int = 1,                           // drop edges below this weight, server-side
    limit: int = 200,                              // page size for the edge list (max 500)
    cursor: string?                                // pagination cursor; omit for the first page
)
```

Implemented in `PairwiseDependenciesTool.kt`; exposed over REST as
`GET /pairwise-dependencies` with the matching query parameters.

### 2.2 The cost-class split

The response has two parts with different size behavior, and the tool treats them differently — this
is why it is classified **hybrid** (input-bounded summary + data-bounded edges) rather than purely
input-bounded:

- **Summary** — `O(node_count)`: SCCs partition the nodes and `topological_order` is one entry per
  node. Computed over the **entire** node set and returned **on the first page only** (when no
  `cursor` is supplied).
- **Edge list** — `O(edges)`: bounded by how coupled the subtrees are, not by the input. **Paginated**
  via `limit`/`cursor` and thinnable with `min_weight`.

A first-page response carries `nodes`, `summary`, `edges`, and (if more remain) `next_cursor`. A
continuation response (cursor supplied) carries only `edges` and `next_cursor` — the summary and
`nodes` map are global, unchanging across pages, and the `topological_order` array can be large, so
repeating them would defeat the purpose.

### 2.3 Parameters

- **`node_ids`** — 2+ subtree IDs (module/package/type). Upper bound is a **soft cap of 1000** whose
  only purpose is to bound the `O(node_count)` summary payload; every realistic module/package-level
  DSM is far below it. Replaces the old behavioral cap of 50. Method/field IDs are rejected with the
  shared `INVALID_NODE_KIND` error (carrying the declaring type for one-step recovery).
- **`direction`** — `both` (default, full matrix), `outgoing` (row depends on column), `incoming`
  (column depends on row, i.e. the transpose).
- **`edge_sort`** — ordering of the paginated edge list; does not affect the summary:
  - `dsm` (default): by `(from, to)` using each node's index in the matrix's computed order (matrix
    reading order, base modules first). That index equals the `topological_order` position when
    acyclic; when cyclic, the matrix's deterministic FAS-minimizing order is used.
  - `weight_desc`: heaviest coupling first, tie-broken by `(from, to)`.
- **`min_weight`** — drops edges with `weight < min_weight` from the **edge list** before pagination.
  Does **not** affect the analytics: `has_cycles`, SCCs, `topological_order`, and `density` are always
  computed over the full, unfiltered edge set, so filtering for readability can never change the
  cycle/layering verdict.
- **`limit`** / **`cursor`** — standard pagination (`hierograph-pagination.md`): default page 200,
  server cap 500; the summary is first-page-only.

### 2.4 Summary fields

- `node_count` — nodes analyzed.
- `edge_count` — non-zero-weight edges in the full graph, **before** `min_weight`. Basis for
  `density`.
- `returned_edge_count` — edges surviving `min_weight` that will be paged through (equals
  `edge_count` when `min_weight = 1`). Page count is `ceil(returned_edge_count / limit)`.
- `possible_edges` — `node_count * (node_count - 1)`.
- `density` — `edge_count / possible_edges` (2 dp); independent of `min_weight`.
- `has_cycles` — true if any dependency cycle exists among the nodes.
- `strongly_connected_components` — SCCs with ≥2 members (cycle participants); empty when acyclic.
- `topological_order` — a valid layering of node IDs; present only when `has_cycles` is false.

### 2.5 Edge ordering and cursor stability

Edge order is determined by `edge_sort` and is **stable across pages** (required for a coherent
cursor): both orders are total (`weight_desc` is tie-broken by `(from, to)`). Pagination reuses the
existing infrastructure (`Paginator`, `PaginationSpec`, `QueryHash`, `DataHashProvider`,
`CursorValidator`):

- The cursor embeds a query hash (over `node_ids`, `direction`, `edge_sort`, `min_weight` — not
  `limit`/`cursor`) and a data-snapshot hash. Changing any query parameter mid-pagination yields
  `STALE_CURSOR_QUERY`; a reload of the graph yields `STALE_CURSOR_DATA`; a cursor from another tool
  yields `WRONG_TOOL_CURSOR`; a corrupt cursor yields `INVALID_CURSOR_FORMAT`.
- The loaded graph is an immutable snapshot for the session, so the ordering is deterministic.

### 2.6 Degenerate density

A near-fully-connected node set (`density → 1`) produces `O(node_count²)` edges and therefore many
pages. This is allowed but rarely useful — the summary will already report `has_cycles: true` with one
large SCC, which is the real answer. Pagination degrades gracefully (the caller stops after the
summary) and `min_weight` is the lever to thin such a matrix to its load-bearing edges.

## 3. Efficiency audit of the DSM algorithms

Raising the node cap made the `core.algorithms` module the binding constraint, so every algorithm on
the DSM path was audited. The DSM is built by:

```
createDependencyStructureMatrix(nodes)
  ├─ detectStronglyConnectedComponents(nodes)        // Tarjan
  ├─ for each SCC: FastFasSorter.sort(scc)           // FastFAS greedy FAS
  └─ assemble orderedNodes / cycles / upwardDeps
```

Five issues were found; all were fixed.

| # | Location | Before | After | Fix |
|---|---|---|---|---|
| 0 | `GraphUtils.computeAdjacencyList` / `computeAdjacencyMatrix` | `O(n²)` — one `getAggregatedDependency` per cell | `O(V+E)` | Single linear bucketing pass: map every node in each selected subtree to its bucket, then charge each subtree's accumulated outgoing edges to the target's cell. |
| 1 | `Tarjan` | `O(V·E + V²)` | `O(V+E)` | `onStack: BooleanArray` for O(1) membership (was `List.contains`); push/pop at the tail via `ArrayDeque` (was index-0 insert/remove). |
| 2 | `DependencyStructureMatrixImpl` ordering | `O(n²)` | `O(n)` | `HashSet<identifier>` membership instead of `node !in ordered` over a growing `List`. |
| 3 | `FastFAS` | `O(n³)` | `O(n²)` | Compute weighted in/out degree + unweighted in/out counts once, then maintain them incrementally as vertices are removed, instead of rescanning the matrix each step. |
| 4 | `DependencyStructureMatrixImpl.getMatrix` / `getWeight` | `O(n²)` `getAggregatedDependency` calls | `O(n²+E)` once, cached; `getWeight` O(1) | Back both by one lazily-built `computeAdjacencyMatrix` (includes the diagonal, so the two stay mutually consistent). |

### 3.1 Notes on behavior preservation

- **#0 / #4** — the bucketing matrix is identical to the per-cell aggregation for the disjoint-subtree
  inputs the DSM uses; the diagonal (subtree-internal weight) is preserved, so `getMatrix`/`getWeight`
  consistency holds for hierarchical inputs too. Nested selections (a selected node inside another)
  attribute each contained node to the innermost-by-input-order selected node — an inherently
  ill-defined case the DSM workflow already discourages.
- **#1 (Tarjan)** — output is unchanged (same SCCs, same component composition). The traversal stays
  recursive; depth is bounded by the input node count, which is safe at the ≤1000 cap. It is not
  intended to run directly over the full type graph.
- **#3 (FastFAS)** — the greedy selection rule is unchanged (emit a sink, else a source, else the
  vertex of maximum **weighted** `out − in` degree, lowest index breaking ties) and the skipped-edge
  (upward-dependency) set is identical, so the DSM ordering is unchanged. Weighted degree still drives
  the max-delta choice, so the heaviest dependencies remain least likely to be cut. `O(n²)` is the
  floor for a dense-matrix input, since the matrix itself is `O(n²)`.

### 3.2 End-to-end complexity of the DSM / `pairwise_dependencies` path

| Stage | Complexity |
|---|---|
| Adjacency / aggregation build (`computeAdjacencyList`/`Matrix`, `computePairwiseAggregation`) | `O(V + E)` |
| SCC detection (Tarjan) | `O(V + E)` |
| Per-SCC FAS ordering (FastFAS) | `O(Σ sccᵢ²)` |
| DSM ordering assembly | `O(V)` |
| Tool: edge order + `min_weight` filter + page slice | `O(E log E)` (sort) + `O(page)` |

The only remaining super-linear term is the per-SCC `O(sccᵢ²)` in FastFAS, which is inherent to the
dense-matrix representation and confined to *actual* cycle clusters. For the common module-graph case
(mostly singleton SCCs) it is negligible; it bites only on a single large cyclic tangle, where the
summary has already told the caller the graph is heavily entangled. This is the one place a future
sparse, bucket-list Eades implementation (`O(V+E)`) could help if large SCCs ever become common.

## 4. Test coverage

- `PairwiseDependenciesPaginationTest` (server) — first-page summary/nodes/topo; continuation omits
  summary/nodes; cursor enumerates every edge once; `weight_desc` ordering; `min_weight` filters the
  edge list while leaving analytics intact; cycle → `has_cycles` + SCC + no `topological_order`;
  `STALE_CURSOR_QUERY`; `INVALID_CURSOR_FORMAT`; `INPUT_TOO_SMALL`; `INPUT_TOO_LARGE` (max_nodes 1000).
- `GraphUtilsTest` (algorithms) — existing adjacency/matrix assertions still hold (verifying the
  linearization is behavior-preserving), plus `computePairwiseAggregation` edge set, weights, and
  no-self-loops.
- `TarjanTest`, `FastFasSorterTest`, `DependencyStructureMatrixTest` — unchanged and green, confirming
  the SCC/FAS/ordering output is preserved by the optimizations.

Full module test runs are green (algorithms + server).

## 5. Files touched

- `io.hierograph.hierarchicalgraph.core.algorithms/.../GraphUtils.kt` — linear adjacency builders +
  `computePairwiseAggregation`.
- `io.hierograph.hierarchicalgraph.core.algorithms/.../impl/Tarjan.kt` — O(V+E) SCC.
- `io.hierograph.hierarchicalgraph.core.algorithms/.../impl/FastFAS.kt` — O(n²) incremental greedy FAS.
- `io.hierograph.hierarchicalgraph.core.algorithms/.../impl/DependencyStructureMatrixImpl.kt` — HashSet
  ordering + lazily-built weight matrix.
- `io.hierograph.mcp.server/.../tools/dependencyanalysis/PairwiseDependenciesTool.kt` — pagination,
  `edge_sort`, `min_weight`, first-page-only summary, soft cap 1000.
- `io.hierograph.mcp.server/.../rest/GraphController.kt` — REST endpoint parameters.
