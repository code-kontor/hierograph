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
4. **Adjacency representations** — matrix and list forms of the dependency graph

All algorithms operate on a flat collection of `HGNode` instances. The dependency
structure between these nodes is derived from `node.getOutgoingDependenciesTo(otherNode)`,
which returns aggregated dependencies (see model spec Section 3.3).

---

## 2. Public Interfaces

### 2.1 INodeSorter

Topological sorting of nodes, identifying cycle-breaking ("upward") dependencies.

```
interface INodeSorter {
    fun sort(nodes: List<HGNode>): SortResult
}

interface SortResult {
    val orderedNodes: List<HGNode>
    val upwardDependencies: List<HGAggregatedDependency>
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
    val upwardDependencies: List<HGAggregatedDependency>
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
fun detectStronglyConnectedComponents(nodes: Collection<HGNode>): List<List<HGNode>>
```

Returns **all** strongly connected components, including single-node components.
A single-node component means the node has no self-loop and is not part of a cycle.

```
fun detectCycles(nodes: Collection<HGNode>): List<List<HGNode>>
```

Returns only SCCs with size > 1 (actual cycles).

### 3.2 DSM Creation

```
fun createDependencyStructureMatrix(nodes: Collection<HGNode>): IDependencyStructureMatrix
```

Creates a DSM using the following procedure:

1. Detect all SCCs using Tarjan's algorithm.
2. Sort each SCC (including single-node ones) using FastFAS sorter.
3. Collect all upward dependencies from the sort results.
4. Build the ordered node list:
   a. First, place single-node SCCs that have no outgoing core dependencies.
   b. Then, place all remaining nodes (preserving their sorted order within each SCC).
   c. Reverse the entire list.
5. Filter cycles to only those with size > 1.

### 3.3 Adjacency Matrix

```
fun computeAdjacencyMatrix(nodes: List<HGNode>): Array<IntArray>
```

Returns an NxN matrix where `matrix[i][j]` is the aggregated dependency weight
from `nodes[i]` to `nodes[j]`. Weight is 0 if no dependency exists.

### 3.4 Adjacency List

```
fun computeAdjacencyList(nodes: Collection<HGNode>): Array<IntArray>
```

Returns an array of arrays where `result[i]` contains the indices of all nodes
that `nodes[i]` has an outgoing dependency to. Only includes edges with non-zero weight.

### 3.5 Sorter Factory

```
fun createFasNodeSorter(): INodeSorter
```

Creates a FastFAS-based node sorter.

---

## 4. Algorithms — Detailed Behavior

### 4.1 Tarjan's Algorithm

**Input:** A collection of `HGNode` instances and their dependency structure.

**Algorithm:**
1. Build an adjacency list from the nodes using `computeAdjacencyList`.
2. Execute Tarjan's classic DFS-based SCC algorithm:
   - Maintain a DFS index counter, a stack, and per-node `vindex`/`vlowlink` arrays.
   - For each unvisited node, perform DFS:
     - Assign `vindex[v] = vlowlink[v] = index++`.
     - Push `v` onto the stack.
     - For each neighbor `n` of `v`:
       - If unvisited: recurse, then `vlowlink[v] = min(vlowlink[v], vlowlink[n])`.
       - If on stack: `vlowlink[v] = min(vlowlink[v], vindex[n])`.
     - If `vlowlink[v] == vindex[v]`: pop nodes from stack until `v` is popped — these form an SCC.
3. Return all SCCs.

**Complexity:** O(V + E)

### 4.2 FastFAS Algorithm

**Input:** An NxN adjacency matrix (weights).

**Output:** An ordered sequence of node indices and a list of "skipped" (upward) edges.

**Algorithm:**
1. Initialize: vertices = {0..N-1}, s1 = [], s2 = [], skippedEdges = [].
2. While vertices is non-empty:
   a. **Find sink:** A vertex with no outgoing edges to other remaining vertices.
      If found: remove from vertices, prepend to s2. Continue loop.
   b. **Find source:** A vertex with no incoming edges from other remaining vertices.
      If found: remove from vertices, append to s1. Continue loop.
   c. **Find max-delta vertex:** The vertex with maximum (out-degree - in-degree) among
      remaining vertices. Remove it, record all incoming edges from remaining vertices
      as skipped edges, append to s1.
3. Result = s1 ++ s2.

**Reference:** Eades, Lin, Smyth: "A fast and effective heuristic for the feedback arc set problem" (1993).

### 4.3 FastFAS Sorter (INodeSorter implementation)

**Input:** A list of `HGNode` instances.

**Algorithm:**
1. Compute the adjacency matrix from the input nodes.
2. Run FastFAS on the adjacency matrix to get an initial ordering.
3. **Bubble sort refinement:** For each pair of adjacent nodes in the ordering,
   if swapping them would place the heavier dependency direction "downward", swap them.
   Specifically: if `matrix[ordered[i]][ordered[i-1]] > matrix[ordered[i-1]][ordered[i]]`,
   swap `ordered[i]` and `ordered[i-1]` and continue bubbling.
4. Reverse the ordering.
5. Map indices back to `HGNode` objects → `orderedNodes`.
6. For each skipped edge from FastFAS: look up the aggregated dependency between the
   source and target nodes → `upwardDependencies`.

### 4.4 Dependency Structure Matrix Construction

See Section 3.2 for the procedure. The DSM combines Tarjan (for SCC decomposition) with
FastFAS sorter (for ordering within and across SCCs).

---

## 5. Type Mapping from EMF

The EMF version used `AbstractHGDependency` as the type for upward dependencies.
In the Kotlin model, there is no `AbstractHGDependency` base type. The upward dependencies
are always `HGAggregatedDependency` instances (obtained via `node.getOutgoingDependenciesTo(other)`),
so we use `HGAggregatedDependency` directly.

---

## 6. Scope Exclusions

- **Thread safety** — not thread-safe, same as the Java implementation.
- **Alternative algorithms** — only FastFAS-based sorting is specified. Other sorting strategies may be added later.
