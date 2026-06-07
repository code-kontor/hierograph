# Hierarchical Graph Algorithms — Kotlin Reimplementation Specification

This document specifies the graph algorithms that operate on the hierarchical graph model.
It serves as the contract for the pure-Kotlin reimplementation that will replace the
current Java-based `org.slizaa.hierarchicalgraph.core.algorithms` module.

The algorithms module depends on the Kotlin model specified in `hierarchicalgraph-kotlin-spec.md`.

---

## 1. Overview

The module provides four core capabilities:

1. **Strongly Connected Component (SCC) detection** — Tarjan's algorithm
2. **Topological sorting** — FastFAS (Feedback Arc Set) heuristic with bubble sort refinement
3. **Dependency Structure Matrix (DSM)** — combines SCC detection and sorting into a single analysis
4. **Adjacency representations** — matrix, list, and pairwise-aggregation forms of the dependency graph

All algorithms operate on a flat collection of `HGNode` instances together with the owning
`Hierarchy`. The dependency structure between these nodes is derived from the hierarchy's
structural navigation and aggregation API — primarily `hierarchy.accumulatedOutgoing(node)`
(the accumulated outgoing edges of a node's subtree) and `hierarchy.getAggregatedDependency(from, to)`
(see model spec Section 3.3). Every public entry point therefore takes a `hierarchy: Hierarchy`
argument in addition to the node collection.

---

## 2. Public Interfaces

### 2.1 INodeSorter

Topological sorting of nodes, identifying cycle-breaking ("upward") dependencies.

```
interface INodeSorter {
    fun sort(nodes: List<HGNode>, hierarchy: Hierarchy): SortResult
}

interface SortResult {
    val orderedNodes: List<HGNode>
    val upwardDependencies: List<AggregatedDependency>
}
```

**Semantics:**
- `orderedNodes` is a permutation of the input `nodes`, ordered such that dependencies
  flow "downward" (from earlier to later nodes) as much as possible.
- `upwardDependencies` contains the aggregated dependencies that violate the topological
  order — i.e., edges from a later node to an earlier node. These are the edges that
  would need to be removed to make the graph acyclic.

### 2.2 IDependencyStructureMatrix

A Dependency Structure Matrix (DSM) combining topological ordering with cycle analysis.

```
interface IDependencyStructureMatrix {
    val orderedNodes: List<HGNode>
    val upwardDependencies: List<AggregatedDependency>
    val cycles: List<List<HGNode>>

    fun isCellInCycle(i: Int, j: Int): Boolean
    fun isRowInCycle(i: Int): Boolean
    fun getWeight(i: Int, j: Int): Int
    fun getMatrix(): Array<IntArray>
}
```

| Member | Description |
|--------|-------------|
| `orderedNodes` | Topologically ordered nodes. Nodes without outgoing dependencies and not in cycles appear last (reversed). |
| `upwardDependencies` | Aggregated dependencies that violate the topological order. |
| `cycles` | List of cycles (SCCs with size > 1). Each cycle is a list of nodes in sorted order. |
| `isCellInCycle(i, j)` | True if both `orderedNodes[i]` and `orderedNodes[j]` belong to the same cycle. Returns false if indices are out of bounds. |
| `isRowInCycle(i)` | True if `orderedNodes[i]` belongs to any cycle. Equivalent to `isCellInCycle(i, i)`. |
| `getWeight(i, j)` | The aggregated dependency weight from `orderedNodes[i]` to `orderedNodes[j]`. Returns 0 if no dependency exists. Returns -1 if indices are out of bounds. |
| `getMatrix()` | Returns the full NxN weight matrix where `matrix[i][j] = getWeight(i, j)`. |

---

## 3. GraphUtils — Public Facade

All algorithms are accessed through a single utility object.

### 3.1 SCC Detection

```
fun detectStronglyConnectedComponents(nodes: Collection<HGNode>, hierarchy: Hierarchy): List<List<HGNode>>
```

Returns **all** strongly connected components, including single-node components.
A single-node component means the node has no self-loop and is not part of a cycle.

```
fun detectCycles(nodes: Collection<HGNode>, hierarchy: Hierarchy): List<List<HGNode>>
```

Returns only SCCs with size > 1 (actual cycles).

### 3.2 DSM Creation

```
fun createDependencyStructureMatrix(nodes: Collection<HGNode>, hierarchy: Hierarchy): IDependencyStructureMatrix
```

Creates a DSM using the following procedure:

1. Detect all SCCs using Tarjan's algorithm.
2. Sort each SCC (including single-node ones) using FastFAS sorter.
3. Collect all upward dependencies from the sort results.
4. Build the ordered node list:
   a. First, place single-node SCCs whose node has no **direct** outgoing core
      dependencies — i.e. `node.outgoingCoreDependencies` is empty. This uses the node's
      own outgoing core-dependency edges (the `HGNode.outgoingCoreDependencies` property),
      *not* aggregated dependencies; such a node is a pure sink and is placed first so it
      ends up last after the reversal in step 4c.
   b. Then, place all remaining nodes (preserving their sorted order within each SCC).
   c. Reverse the entire list.
5. Filter cycles to only those with size > 1.

### 3.3 Adjacency Matrix

```
fun computeAdjacencyMatrix(nodes: List<HGNode>, hierarchy: Hierarchy): Array<IntArray>
```

Returns an NxN matrix where `matrix[i][j]` is the summed weight of all dependencies from
anything in subtree `i` to anything in subtree `j`. Weight is 0 if no dependency exists. The
diagonal carries each subtree's internal weight.

The matrix is built by a **single linear `O(V + E)` bucketing pass**, not by `n²` per-cell
`getAggregatedDependency` calls: each selected subtree's accumulated outgoing edges
(`hierarchy.accumulatedOutgoing(node)`) are walked once, and every edge is charged to the
`(i, j)` cell of the selected nodes that contain its endpoints. Endpoint-to-cell membership is
resolved through a shared bucket map (built once by traversing each selected subtree). For
disjoint-subtree inputs (the normal case) the result is identical to the former per-cell
computation; for nested inputs each contained node is attributed to the later (higher-index)
selected node that contains it.

### 3.4 Adjacency List

```
fun computeAdjacencyList(nodes: Collection<HGNode>, hierarchy: Hierarchy): Array<IntArray>
```

Returns an array of arrays where `result[i]` is the ascending, de-duplicated list of indices `j`
such that subtree `i` depends on subtree `j`. Uses the same single linear bucketing pass as
`computeAdjacencyMatrix`. A self-edge `i` appears only when subtree `i` has an internal
dependency.

### 3.5 Pairwise Aggregation

```
fun computePairwiseAggregation(nodes: List<HGNode>, hierarchy: Hierarchy): List<AggregatedEdge>

data class AggregatedEdge(
    val fromIndex: Int,
    val toIndex: Int,
    val weight: Int,
    val typePairCount: Int,
    val attributesBitmap: Int,
)
```

Computes every non-empty **off-diagonal** aggregated edge among `nodes` in a single linear pass —
the matrix-shaped counterpart to calling `getAggregatedDependency` for each of the `n²` cells.
Each subtree's accumulated outgoing edges are walked once and bucketed into the `(i, j)` cell of
the selected nodes that contain its endpoints, accumulating:

- `weight` — the summed weight of the contributing dependencies,
- `typePairCount` — the number of distinct `(source type, target type)` pairs that contributed,
- `attributesBitmap` — the union (bitwise OR) of the contributing dependencies' attribute bitmaps.

Self-loops (`i == j`) and zero-weight cells are omitted. Indices are positions in `nodes`, so
callers that pass an already ordered list (e.g. a DSM's `orderedNodes`) get edges indexed in that
order.

### 3.6 Sorter Factory

```
fun createFasNodeSorter(): INodeSorter
```

Creates a FastFAS-based node sorter.

---

## 4. Algorithms — Detailed Behavior

### 4.1 Tarjan's Algorithm

**Input:** A collection of `HGNode` instances and the owning `Hierarchy`.

**Algorithm:**
1. Build an adjacency list from the nodes using `computeAdjacencyList(nodes, hierarchy)`.
2. Execute Tarjan's classic DFS-based SCC algorithm:
   - Maintain a DFS index counter, a working stack (an `ArrayDeque` pushed/popped at its tail),
     an `onStack` `BooleanArray` for O(1) stack-membership tests, and per-node
     `vindex`/`vlowlink` arrays.
   - For each unvisited node, perform DFS:
     - Assign `vindex[v] = vlowlink[v] = index++`.
     - Push `v` onto the stack and set `onStack[v] = true`.
     - For each neighbor `n` of `v`:
       - If unvisited: recurse, then `vlowlink[v] = min(vlowlink[v], vlowlink[n])`.
       - Else if `onStack[n]`: `vlowlink[v] = min(vlowlink[v], vindex[n])`.
     - If `vlowlink[v] == vindex[v]`: pop nodes from the stack tail (clearing `onStack`) until
       `v` is popped — these form an SCC.
3. Return all SCCs.

The traversal is recursive, so its depth is bounded by the input node count; this is intended for
DSM-sized selected node sets, not the full type graph.

**Complexity:** O(V + E) — the `onStack` flag array and tail push/pop avoid the `List.contains` +
index-0 insert/remove that would otherwise make the inner loop O(V·E) and the stack ops O(V²).

### 4.2 FastFAS Algorithm

**Input:** An NxN adjacency matrix (weights).

**Output:** An ordered sequence of node indices and a list of "skipped" (upward) edges.

**Algorithm:**
1. Initialize: vertices = {0..N-1}, s1 = [], s2 = [], skippedEdges = []. Compute, in one pass over
   the matrix, the per-vertex weighted in/out degree (drives the max-delta choice) and unweighted
   in/out edge counts (drive sink/source detection), each excluding the diagonal. These degrees are
   then **maintained incrementally** as vertices are removed, rather than recomputed each step.
2. While vertices is non-empty:
   a. **Find sink:** the lowest-index remaining vertex with no outgoing edges (`countOut == 0`).
      If found: remove it, prepend to s2. Continue loop.
   b. **Find source:** the lowest-index remaining vertex with no incoming edges (`countIn == 0`).
      If found: remove it, append to s1. Continue loop.
   c. **Find max-delta vertex:** the remaining vertex with maximum weighted
      (out-degree − in-degree), lowest index breaking ties. Record all incoming edges from
      remaining vertices as skipped edges, remove it, append to s1.
3. Result = s1 ++ s2.

This reproduces the ordering and skipped-edge set of the textbook formulation exactly, including
the same vertex ordering and upward-dependency output, while running in **O(n²)** instead of O(n³)
(degrees are maintained incrementally rather than re-scanned). O(n²) is the floor for a dense
matrix input anyway. Weighted degree drives the max-delta choice so the heaviest dependencies are
least likely to be cut; plain edge presence drives sink/source detection.

**Reference:** Eades, Lin, Smyth: "A fast and effective heuristic for the feedback arc set problem" (1993).

### 4.3 FastFAS Sorter (INodeSorter implementation)

**Input:** A list of `HGNode` instances and the owning `Hierarchy`.

**Algorithm:**
1. Compute the adjacency matrix from the input nodes via `computeAdjacencyMatrix(nodes, hierarchy)`.
2. Run FastFAS on the adjacency matrix to get an initial ordering.
3. **Bubble sort refinement:** For each pair of adjacent nodes in the ordering,
   if swapping them would place the heavier dependency direction "downward", swap them.
   Specifically: if `matrix[ordered[i]][ordered[i-1]] > matrix[ordered[i-1]][ordered[i]]`,
   swap `ordered[i]` and `ordered[i-1]` and continue bubbling.
4. Reverse the ordering.
5. Map indices back to `HGNode` objects → `orderedNodes`.
6. For each skipped edge from FastFAS: look up the aggregated dependency between the
   source and target nodes via `hierarchy.getAggregatedDependency(from, to)` → `upwardDependencies`.

### 4.4 Dependency Structure Matrix Construction

See Section 3.2 for the procedure. The DSM combines Tarjan (for SCC decomposition) with
FastFAS sorter (for ordering within and across SCCs).

The node-ordering assembly tracks "already added?" membership with a `HashSet` of node
identifiers, so the build is O(n) rather than the O(n²) a `node !in ordered` scan over the growing
list would cost.

`getMatrix()` and `getWeight(i, j)` are both backed by a **single, lazily-built weight matrix**
(computed once via `computeAdjacencyMatrix(orderedNodes, hierarchy)`, including the subtree-internal
diagonal), replacing the former O(n²) of one `getAggregatedDependency` call per cell. `getWeight`
returns `-1` for out-of-bounds indices; otherwise it indexes directly into the cached matrix.

---

## 5. Type Mapping from EMF

The EMF version used `AbstractHGDependency` as the type for upward dependencies.
In the Kotlin model, there is no `AbstractHGDependency` base type. The upward dependencies
are always `AggregatedDependency` instances (obtained via `hierarchy.getAggregatedDependency(from, to)`),
so we use `AggregatedDependency` directly.

---

## 6. Scope Exclusions

- **Thread safety** — not thread-safe, same as the Java implementation.
- **Alternative algorithms** — only FastFAS-based sorting is specified. Other sorting strategies may be added later.
