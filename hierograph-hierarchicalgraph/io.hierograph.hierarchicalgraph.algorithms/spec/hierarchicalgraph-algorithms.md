# Hierograph Hierarchical Graph Algorithms — Specification

`io.hierograph.hierarchicalgraph.algorithms` is Hierograph's **structure analysis
toolkit**. Given a set of nodes of a hierarchical graph and the hierarchy they live
in, it computes how those nodes depend on one another: weighted adjacency between
their subtrees, the cycles among them, and an ordering that makes the layering
visible. Its central product is the **dependency structure matrix** (DSM) behind
Hierograph's matrix views and pairwise dependency analysis.

This document explains why the algorithms exist, what each one computes, how they
behave, and how to work with them.

---

## Contents

1. [Purpose](#1-purpose)
2. [Where it sits](#2-where-it-sits)
3. [Concepts](#3-concepts)
4. [Adjacency](#4-adjacency)
5. [Strongly connected components](#5-strongly-connected-components)
6. [Ordering](#6-ordering)
7. [The dependency structure matrix](#7-the-dependency-structure-matrix)
8. [Example](#8-example)
9. [Limits](#9-limits)
10. [API reference](#10-api-reference)
11. [Working with the algorithms](#11-working-with-the-algorithms)
12. [Guarantees](#12-guarantees)
13. [Boundaries](#13-boundaries)

---

## 1. Purpose

The [hierarchical graph model](../../io.hierograph.hierarchicalgraph.model/spec/hierarchicalgraph-model.md)
answers questions about **one pair** of nodes: what does this subtree depend on, and
how strongly does it depend on that one. Architectural analysis asks about a **whole
set** of nodes at once. Which of these modules form cycles? In which order do they
layer? What does the full matrix of their mutual dependencies look like? Answering
such questions pair by pair costs `n²` aggregations and still leaves the cycle and
ordering problems unsolved. This module answers them for the set as a whole.

Its design goals:

**Structure-aware.** Every algorithm takes the hierarchy explicitly. A node stands
for its whole subtree, and the dependencies between two nodes are those between
their subtrees *in that hierarchy*. The same node set gives different results under
a different arrangement. This is exactly what makes a forked scenario hierarchy
analysable with the same code.

**One pass, not `n²`.** Adjacency is computed by walking each selected subtree's
accumulated outgoing dependencies once and sorting each dependency into its cell.
The cost is proportional to the dependencies involved, not to the number of node
pairs.

**Cycles and layers together.** The DSM groups each cycle into a contiguous block,
orders the blocks so that dependencies point one way, and names the dependencies
that point the other way. One object answers "is it layered", "where are the
cycles" and "what would have to be cut".

**Deterministic.** For the same node list and the same model, every result has the
same content in the same order. Two views of the same matrix never disagree.

---

## 2. Where it sits

```
                 ┌──────────────────────────────┐
                 │  HGModel: graph + hierarchy  │
                 └──────────────┬───────────────┘
                                │ accumulatedOutgoing, traverse,
                                │ getAggregatedDependency
                                ▼
 ┌─────────────────────────────────────────────────────────────┐
 │                         GraphUtils                          │
 │  adjacency matrix · adjacency list · pairwise aggregation   │
 │  SCCs / cycles (Tarjan) · ordering (FastFAS) · DSM          │
 └──────────────┬───────────────────────────────┬──────────────┘
                ▼                               ▼
     pairwise_dependencies tool        GraphQL NodeSet
     (DSM order + pairwise edges)      (orderedAdjacencyMatrix)
```

The module depends only on the hierarchical graph model. It reads the hierarchy
through its public API (accumulated outgoing dependencies, subtree traversal,
aggregated dependencies) and never modifies it.

**Consumers** are the server's analysis surfaces. The `pairwise_dependencies` MCP
tool builds a DSM for a node set and lists its edges with pairwise aggregation, in
DSM order. The GraphQL `NodeSet` type serves the DSM's ordered nodes and weight
matrix.

---

## 3. Concepts

### 3.1 Node sets and subtrees

Every algorithm takes a **node set**, a list of `HGNode`s, together with a
`Hierarchy`. Each node in the set stands for its **subtree** in that hierarchy: the
node itself and all its descendants. A dependency counts between two nodes of the
set when its source lies in the first node's subtree and its target in the second
one's.

Results are indexed by **position in the node list**: index `i` is the `i`-th node
passed in, or, for a DSM, the `i`-th node of its ordering.

Node sets are meant to be **disjoint**: no node of the set is an ancestor of
another. Typical sets are the children of one node (the modules of a project, the
packages of a module) or a hand-picked set of unrelated subtrees. Section 4.4
describes what happens when subtrees overlap.

### 3.2 Bucketing

The adjacency computations share one mechanism. First, a **bucket map** assigns
every node in every selected subtree the index of its selected node, by traversing
each subtree once. Then, for each selected node `i`, every dependency in the
hierarchy's `accumulatedOutgoing` of node `i` is charged to the cell `(i, j)`, where
`j` is the bucket of the dependency's target. Dependencies whose target lies outside
every selected subtree are ignored.

### 3.3 Reading a DSM

A DSM is a square matrix over an ordered node list. Cell `(i, j)` holds the weight
with which row node `i` depends on column node `j`. The diagonal holds each
subtree's internal weight: dependencies between nodes of the same subtree.

The ordering puts **dependents before their dependencies**. In a layered set, every
non-zero off-diagonal cell lies **above the diagonal**: each node depends only on
nodes further down the list. A cell below the diagonal is a dependency pointing back
up the list. The DSM calls these **upward dependencies**. In an acyclic set the
ordering avoids them entirely. In a set with cycles they are unavoidable, and they
are the dependencies whose removal would break the cycles.

### 3.4 Cycles

A **cycle** is a strongly connected component with more than one node: a group of
nodes in which every node depends, directly or indirectly, on every other.
Single-node components are not cycles, even when the node's subtree has internal
dependencies (a non-zero diagonal cell).

---

## 4. Adjacency

### 4.1 Adjacency matrix

```kotlin
fun computeAdjacencyMatrix(nodes: List<HGNode>, hierarchy: Hierarchy): Array<IntArray>
```

An `n × n` matrix: `matrix[i][j]` is the summed `weight` of all core dependencies
from subtree `i` to subtree `j`, and `0` where there are none. The diagonal holds
each subtree's internal weight.

For disjoint subtrees, `matrix[i][j]` equals
`hierarchy.getAggregatedDependency(nodes[i], nodes[j])?.aggregatedWeight ?: 0` for
`i ≠ j`.

### 4.2 Adjacency list

```kotlin
fun computeAdjacencyList(nodes: Collection<HGNode>, hierarchy: Hierarchy): Array<IntArray>
```

The unweighted form of the matrix: `result[i]` lists, in ascending order and without
duplicates, every index `j` that subtree `i` depends on. `i` itself appears in
`result[i]` when the subtree has internal dependencies. The collection is indexed in
its iteration order.

The adjacency list is the input to cycle detection (§5).

### 4.3 Pairwise aggregation

```kotlin
fun computePairwiseAggregation(nodes: List<HGNode>, hierarchy: Hierarchy): List<AggregatedEdge>

data class AggregatedEdge(
    val fromIndex: Int,        // depending node, an index into nodes
    val toIndex: Int,          // depended-on node, an index into nodes
    val weight: Int,           // summed weight of the contributing dependencies
    val typePairCount: Int,    // distinct (from, to) node pairs among them
    val attributesBitmap: Int, // bitwise OR of their attribute bitmaps
)
```

One `AggregatedEdge` per non-empty **off-diagonal** cell, computed in the same single
pass as the matrix. It carries what a matrix cell can't hold:

| Field | Meaning | Why it exists |
|-------|---------|---------------|
| `weight` | the cell's weight, as in the matrix | strength of the coupling |
| `typePairCount` | the number of distinct `(from.identifier, to.identifier)` pairs among the cell's dependencies | breadth of the coupling. In models built from a GraphStore, core dependencies connect types, so this counts how many type-to-type relations make up the edge. Weight 40 from one pair is a different problem from weight 40 spread over 30 pairs. |
| `attributesBitmap` | the union of the dependencies' `attributesBitmap`s | the *nature* of the coupling. In GraphStore models, this tells whether any of the dependencies is an *extends*, *implements*, *annotated by* or *other use*. |

The diagonal is omitted, as is any cell whose summed weight is `0` or less. The list
has no defined order; sort it if an order matters. Pass the DSM's `orderedNodes` as
`nodes` to get edges indexed in DSM order.

### 4.4 Overlapping subtrees

When one selected node is an ancestor of another, the results are well-defined but
rarely meaningful:

- **Rows count twice.** A dependency inside the inner subtree is in the accumulated
  outgoing dependencies of both selected nodes, so it is charged to both rows.
- **Columns count once.** A node in both subtrees is bucketed to whichever of the
  two selected nodes comes **later** in the list.

Select disjoint subtrees to get a matrix in which every dependency appears once.

---

## 5. Strongly connected components

```kotlin
fun detectStronglyConnectedComponents(nodes: Collection<HGNode>, hierarchy: Hierarchy): List<List<HGNode>>
fun detectCycles(nodes: Collection<HGNode>, hierarchy: Hierarchy): List<List<HGNode>>
```

`detectStronglyConnectedComponents` partitions the node set into its strongly
connected components, single nodes included. `detectCycles` returns only the
components with more than one node (§3.4).

Both run **Tarjan's algorithm** over the adjacency list (§4.2):

1. Compute the adjacency list of the node set.
2. Depth-first search from each unvisited node, in input order, assigning each node
   a discovery index and a low-link value, and keeping visited nodes on a stack.
3. When a node's low-link equals its own index, pop the stack down to that node.
   The popped nodes form one component.

Components are returned in the order Tarjan completes them, which is **reverse
topological**: a component comes before every component that depends on it. Within
a component, nodes are in stack-pop order.

The cost is `O(V + E)` over the node set and its adjacency. The search is recursive,
so its depth can reach the size of the node set. It is meant for node sets of
DSM size, not for running over every type of a codebase at once.

---

## 6. Ordering

### 6.1 FastFAS

`FastFAS` orders the rows of a weighted adjacency matrix with the greedy
feedback-arc-set heuristic of Eades, Lin and Smyth (*A fast and effective heuristic
for the feedback arc set problem*, 1993). It builds two sequences, `s1` from the
front and `s2` from the back, by repeatedly removing one vertex:

1. **A sink** (no outgoing edges to remaining vertices): prepend it to `s2`.
2. Otherwise **a source** (no incoming edges from remaining vertices): append it to
   `s1`.
3. Otherwise the vertex with the largest **weighted out-degree minus weighted
   in-degree**: append it to `s1`, and record each of its incoming edges from a
   remaining vertex as a **skipped edge**.

The result is `s1` followed by `s2`. Ties go to the lowest index in every step. The
diagonal is ignored. Sinks and sources are recognised by the presence of edges,
while step 3 uses weights, so that the heaviest dependencies are the least likely
to be skipped. Degrees are computed once and updated as vertices are removed. The
cost is `O(n²)`, which is the size of the matrix itself.

In the result, dependencies point from earlier to later vertices, except for the
skipped edges, which point backwards.

### 6.2 Node sorter

```kotlin
interface INodeSorter {
    fun sort(nodes: List<HGNode>, hierarchy: Hierarchy): SortResult
}

interface SortResult {
    val orderedNodes: List<HGNode>                       // a permutation of nodes
    val upwardDependencies: List<AggregatedDependency>   // the skipped edges
}
```

`GraphUtils.createFasNodeSorter()` returns the FastFAS-based sorter. `sort`:

1. computes the adjacency matrix of `nodes` (§4.1);
2. orders it with FastFAS;
3. **refines** the order with a bubble pass: walking from front to back, a vertex is
   moved forward past its predecessor as long as it depends on that predecessor more
   heavily than the predecessor depends on it;
4. **reverses** the order;
5. maps each skipped edge to its aggregated dependency
   (`hierarchy.getAggregatedDependency`) to form `upwardDependencies`.

The reversal makes the sorter's order run from depended-on to depending. The DSM
reverses it once more (§7.1), so that in the DSM, dependents come first.

---

## 7. The dependency structure matrix

```kotlin
fun createDependencyStructureMatrix(nodes: Collection<HGNode>, hierarchy: Hierarchy): IDependencyStructureMatrix
```

### 7.1 Construction

1. **Components.** Detect the strongly connected components of the node set (§5).
2. **Sort each component** with the node sorter (§6.2), single-node components
   included, and collect every component's upward dependencies.
3. **Assemble.** Build a list, first of the single-node components whose node has
   no **direct** outgoing core dependencies (`node.outgoingCoreDependencies` is
   empty), then of all other components in Tarjan order, each in its sorted order.
4. **Reverse** the list. This is `orderedNodes`.
5. **Cycles** are the sorted components with more than one node.

Tarjan returns components in reverse topological order, so the reversal in step 4
puts dependents before their dependencies. The weight matrix is computed lazily, on
first access, over `orderedNodes`.

### 7.2 Members

| Member | Description |
|--------|-------------|
| `orderedNodes` | The node set in DSM order: a permutation of the input. Each cycle forms a contiguous block. |
| `cycles` | The cycles (§3.4). Each lists its nodes in the sorter's order, which is the reverse of their order in `orderedNodes`. |
| `upwardDependencies` | The aggregated dependencies the ordering skipped: the dependencies inside cycles that point back up the list. Empty for an acyclic set. |
| `getMatrix()` | The `n × n` weight matrix over `orderedNodes` (§4.1), diagonal included. The DSM's own array: read it, don't modify it. |
| `getWeight(i, j)` | `getMatrix()[i][j]`, or `-1` when either index is out of range. |
| `isCellInCycle(i, j)` | Whether `orderedNodes[i]` and `orderedNodes[j]` belong to the same cycle. `false` when either index is out of range. |
| `isRowInCycle(i)` | Whether `orderedNodes[i]` belongs to a cycle; the same as `isCellInCycle(i, i)`. |

---

## 8. Example

Four types of a billing application, each a node of the set. `Invoice` and `Ledger`
depend on each other:

| From | To | Weight | Attributes |
|------|----|-------:|------------|
| `Invoice` | `Money` | 3 | other use |
| `Invoice` | `Ledger` | 1 | other use |
| `Ledger` | `Invoice` | 2 | other use |
| `Ledger` | `Money` | 1 | extends |
| `Report` | `Ledger` | 1 | other use |
| `Report` | `Money` | 1 | other use |

Passing the nodes in the order `Money, Invoice, Ledger, Report`:

**Components** (§5): `[Money]`, `[Ledger, Invoice]`, `[Report]`. `Money` depends on
nothing and completes first, and `Report`, which depends on the cycle, completes
last.

**DSM** (§7):

```
                 Report  Ledger  Invoice  Money
   0  Report        ·       1       ·       1
   1  Ledger        ·       ·       2       1      ┐ cycle
   2  Invoice       ·       1       ·       3      ┘
   3  Money         ·       ·       ·       ·
```

- `orderedNodes` is `Report, Ledger, Invoice, Money`. Every cell lies above the
  diagonal, except `Invoice → Ledger`.
- `cycles` is `[[Invoice, Ledger]]`, the reverse of their order in the DSM.
- `upwardDependencies` is the single aggregated dependency `Invoice → Ledger`, weight
  `1`. The ordering keeps the heavier direction, `Ledger → Invoice` (weight 2),
  pointing down. Cutting the lighter one would break the cycle.
- `isRowInCycle` is `true` for rows 1 and 2, and `getWeight(9, 9)` is `-1`.

**Pairwise aggregation** (§4.3) over `orderedNodes` gives six edges. One of them
reads:

```kotlin
AggregatedEdge(fromIndex = 1, toIndex = 3, weight = 1, typePairCount = 1, attributesBitmap = 1)
```

`Ledger → Money`, weight 1, from one node pair, and bit 0 set: an *extends*.

---

## 9. Limits

**The DSM's sink rule looks at direct dependencies.** Step 3 of the construction
(§7.1) moves a single-node component to the end of the order when the node has no
*direct* outgoing core dependencies, whatever its subtree depends on. For leaf nodes
such as types, "no direct dependencies" means "depends on nothing in the set", and
the order is topological. Container nodes such as modules and packages usually have
no direct dependencies of their own: their dependencies belong to the types inside
them. In a DSM of containers, every acyclic container therefore qualifies. The cycles
come first in the order, followed by all acyclic containers in reverse Tarjan order.
Dependencies from an acyclic container into a cycle then lie below the diagonal and
are not listed as upward dependencies.

**Upward dependencies come from FastFAS, before refinement.** The sorter's bubble
pass (§6.2, step 3) can swap two adjacent vertices after FastFAS has recorded its
skipped edges. When it does, `upwardDependencies` can name a dependency that points
down in the final order, and miss the one that now points up. This affects only
nodes inside cycles, and only some orderings.

**Overlapping subtrees** double-count rows (§4.4).

**Recursion depth** of the component search grows with the node set (§5).

**Pairwise edges are unordered** (§4.3).

---

## 10. API reference

All entry points are on the `GraphUtils` object. The classes in the `impl` package
(`Tarjan`, `FastFAS`, `FastFasSorter`, `DependencyStructureMatrixImpl`) implement
them and are not meant to be used directly.

| Method | Returns | Section |
|--------|---------|---------|
| `computeAdjacencyMatrix(nodes, hierarchy)` | `Array<IntArray>`: summed weights, diagonal included | §4.1 |
| `computeAdjacencyList(nodes, hierarchy)` | `Array<IntArray>`: ascending neighbour indices | §4.2 |
| `computePairwiseAggregation(nodes, hierarchy)` | `List<AggregatedEdge>`: non-empty off-diagonal cells | §4.3 |
| `detectStronglyConnectedComponents(nodes, hierarchy)` | `List<List<HGNode>>`: all components | §5 |
| `detectCycles(nodes, hierarchy)` | `List<List<HGNode>>`: components with more than one node | §5 |
| `createFasNodeSorter()` | `INodeSorter` | §6.2 |
| `createDependencyStructureMatrix(nodes, hierarchy)` | `IDependencyStructureMatrix` | §7 |

Every call computes its result afresh. Only the DSM caches anything, and only its
own weight matrix.

---

## 11. Working with the algorithms

### 11.1 The DSM of a module's packages

```kotlin
val packages = model.hierarchy.childrenOf(module)
val dsm = GraphUtils.createDependencyStructureMatrix(packages, model.hierarchy)

val matrix = dsm.getMatrix()
dsm.orderedNodes.forEachIndexed { i, node ->
    val marker = if (dsm.isRowInCycle(i)) "*" else " "
    println("$marker ${node.identifier}  ${matrix[i].joinToString(" ")}")
}
```

### 11.2 Cycles only

```kotlin
val cycles = GraphUtils.detectCycles(packages, model.hierarchy)
val isLayered = cycles.isEmpty()
```

`detectCycles` is cheaper than building a DSM when the order isn't needed.

### 11.3 DSM edges with their nature

```kotlin
val dsm = GraphUtils.createDependencyStructureMatrix(nodes, hierarchy)
val edges = GraphUtils.computePairwiseAggregation(dsm.orderedNodes, hierarchy)
    .sortedWith(compareBy({ it.fromIndex }, { it.toIndex }))
```

Indexing the pairwise edges by `dsm.orderedNodes` makes their indices match the DSM's
rows and columns.

### 11.4 Evaluate a scenario

```kotlin
val scenario = model.fork()
scenario.hierarchy.move(ledgerType, accountingPackage)

val before = GraphUtils.detectCycles(packages, model.hierarchy)
val after  = GraphUtils.detectCycles(packagesInScenario, scenario.hierarchy)
```

The algorithms read the hierarchy they are given, so the same calls analyse the
baseline and the restructured fork side by side.

### 11.5 Concurrency

The algorithms keep no shared state, but they read the hierarchy, and the
hierarchy fills its caches lazily on first read. Running algorithms concurrently
on the same hierarchy is safe only once its caches are warm, or under the caller's
own synchronization (see the model specification).

---

## 12. Guarantees

For a node set of distinct nodes:

1. **Permutation.** `orderedNodes` of a DSM and of a sort result contain every input
   node exactly once.
2. **Partition.** The strongly connected components contain every input node exactly
   once, and two nodes share a component exactly when each depends on the other,
   directly or through other nodes of the set.
3. **Contiguous cycles.** In a DSM, the nodes of each cycle are adjacent in
   `orderedNodes`.
4. **Consistent weights.** For disjoint subtrees, matrix cells, pairwise edge
   weights and aggregated dependency weights agree.
5. **Matrix and accessor agree.** `getWeight(i, j) == getMatrix()[i][j]` for every
   valid index pair.
6. **Upward dependencies are real.** Every upward dependency is a non-null aggregated
   dependency between two nodes of the same cycle.
7. **Determinism.** The same node list over the same model gives the same results,
   in the same order (apart from pairwise edges, §4.3).
8. **Read-only.** No algorithm modifies the hierarchy or the graph.

---

## 13. Boundaries

| Not in the algorithms | Where it lives |
|-----------------------|----------------|
| Accumulated and aggregated dependencies of a single pair | the hierarchy |
| Choosing the node set (children, scopes, filters) | the caller: MCP tools and GraphQL resolvers |
| Rendering, labelling, direction (incoming vs outgoing) | the consumers |
| Interpreting attribute bits | whoever built the model (`JavaEdgeAttributes` for GraphStore models) |
| Restructuring and scenarios | the hierarchy's `fork`, `move` and local nodes |
| Caching results across calls | the caller; only a DSM caches its own matrix |

Keeping the algorithms to "a node set and a hierarchy in, an analysis out" is what
lets the same code analyse any level of the tree, and any scenario, without knowing
what the nodes are.
