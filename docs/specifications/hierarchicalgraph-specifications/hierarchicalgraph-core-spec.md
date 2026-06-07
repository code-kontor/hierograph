# Hierarchical Graph — Core Specification

This document specifies the hierarchical graph that forms the core of hierograph: its data
model, the multiple-hierarchies design, the graph algorithms, and JSON serialization. It is
the single contract for the `io.hierograph.hierarchicalgraph.*` modules.

It covers:

- **`io.hierograph.hierarchicalgraph.core.model`** — the core graph, nodes, dependencies,
  hierarchies, and their factories.
- **`io.hierograph.hierarchicalgraph.core.algorithms`** — DSM construction, SCC detection
  (Tarjan), feedback-arc-set ordering (FastFAS), and adjacency representations.
- **`io.hierograph.hierarchicalgraph.serialization`** — a flat, ID-keyed JSON snapshot
  serializer for `HGModel`.

---

## 1. Overview — the model at a glance

The core model is split into two layers:

- A flat **core graph** (`HGGraph`) that owns the set of **nodes** (`HGNode`) and the
  **core dependencies** (`HGCoreDependency`) between them. The core graph carries no
  structural (parent/child) information — it is just identity, dependencies, and an
  extension registry.
- One or more **hierarchies** (`Hierarchy`) layered over a shared core graph. A hierarchy
  imposes a tree structure (parent/child relationships) over the graph's nodes and owns
  all structure-dependent navigation and derived dependency computations.

This separation enables **multiple hierarchies** over the same underlying nodes and
dependencies: the same `HGNode` instances can be arranged into different trees by
different `Hierarchy` instances without duplicating graph data.

The model separates two concerns:

- **What a node *is*** — its identity and its own (direct) dependency edges. This is
  intrinsic and shared across every hierarchy.
- **How nodes are *arranged*** — parent/child structure and everything derived from it
  (ancestors, accumulated and aggregated dependencies). This is a *view*, and there can be
  many of them over the same nodes.

Two higher-level dependency concepts are derived from core dependencies, both computed
**by a hierarchy** (because they depend on the tree structure):

- **Accumulated dependencies** — the union of a node's own core dependencies and all core
  dependencies of its descendants (within a given hierarchy).
- **Aggregated dependencies** — a virtual edge between two nodes that summarizes all core
  dependencies flowing between their respective subtrees (within a given hierarchy).

`HGModel` bundles a core graph together with one hierarchy for convenient access.

### What lives where

| Concept | Owner | Notes |
|---|---|---|
| Node identity (`identifier`, `nodeSource`, `kind`) | `HGNode` | Intrinsic, shared across all hierarchies. |
| Direct core dependencies (`outgoing/incomingCoreDependencies`) | `HGNode` | Edge facts are intrinsic — the dependency between two types exists regardless of how they are grouped. |
| The node set + lookup + extension registry | `HGGraph` | Flat; no notion of tree or root. |
| Parent/child, predecessors, root | `Hierarchy` | A tree layered over the graph's nodes. |
| Accumulated dependencies (subtree rollups) | `Hierarchy` | Derived from structure → hierarchy-dependent. |
| Aggregated dependencies (pairwise subtree edges) | `Hierarchy` | Derived from structure → hierarchy-dependent. |
| Traversal, node lookup-by-id | `Hierarchy` | Lookup also resolves hierarchy-local nodes (§3.5). |
| A convenient (graph, hierarchy) pair | `HGModel` | What most callers hold and pass around. |

The key consequence: **`HGNode` carries no structural information.** Anything that depends
on *where* a node sits in a tree is a method on `Hierarchy`, not on the node:

```
node.outgoingCoreDependencies          // intrinsic — on the node
hierarchy.childrenOf(node)             // structural — on the hierarchy
hierarchy.accumulatedOutgoing(node)    // derived from structure — on the hierarchy
hierarchy.getAggregatedDependency(a,b) // derived from structure — on the hierarchy
```

---

## 2. The core graph

Graph construction happens exclusively through factory objects. Clients do not instantiate
nodes, dependencies, hierarchies, or sources directly.

### 2.1 HGNode

A node in the core graph. A node carries identity and its own (non-accumulated) core
dependencies; it carries **no** structural information. Parent/child navigation,
predecessors, accumulated and aggregated dependencies all live on `Hierarchy`.

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The unique identifier for the node. Shortcut for `nodeSource.identifier`. |
| `nodeSource` | `INodeSource` | The source that provides this node's identity and metadata. Never null after construction. |
| `kind` | `Any?` | Application-defined classifier (e.g. a `JavaNodeKind` enum value). Mutable. May be null. |
| `outgoingCoreDependencies` | `List<HGCoreDependency>` | Core dependencies where `from == this`. |
| `incomingCoreDependencies` | `List<HGCoreDependency>` | Core dependencies where `to == this`. |

| Operation | Return | Description |
|-----------|--------|-------------|
| `getNodeSource<T>(clazz: Class<T>)` | `T?` | Returns the node source cast to `T` if it is an instance of `clazz`, null otherwise. |

> There is no dedicated root-node type. The root of a tree is a plain `HGNode` reached via
> `hierarchy.rootNode`. All structural navigation (`parent`, `children`, `predecessors`,
> `rootNode`, `accumulated*`, aggregated lookups, traversal) is provided by `Hierarchy`.

Implemented by `HGNodeImpl`, whose `outgoing`/`incoming` lists are mutated only by
`HGGraphFactory.createCoreDependency`.

### 2.2 HGGraph

The flat core graph. Owns the set of nodes and an extension registry. It has no notion of
hierarchy or root. Implemented by `HGGraphImpl`.

| Property | Type | Description |
|----------|------|-------------|
| `nodes` | `Collection<HGNode>` | All nodes registered in the graph. |

| Operation | Return | Description |
|-----------|--------|-------------|
| `lookupNode(identifier: Any)` | `HGNode?` | Returns the node with the given identifier, or null. Backed by an `identifier -> node` map maintained as nodes are created. |

**Extension registry.** The graph maintains a registry of named extensions (plugins), keyed
either by class name (typed access) or by an arbitrary string key.

| Operation | Return | Description |
|-----------|--------|-------------|
| `registerExtension<T>(clazz: Class<T>, extension: T)` | `Unit` | Registers an extension keyed by `clazz.name`. Replaces any existing entry for that key. |
| `registerExtension(key: String, extension: Any)` | `Unit` | Registers an extension keyed by a string. |
| `getExtension<T>(clazz: Class<T>)` | `T?` | Returns the extension registered under `clazz.name`, cast to `T`. Null if not registered. |
| `getExtension<T>(key: String, clazz: Class<T>)` | `T?` | Returns the extension registered under `key`, cast to `T`. Null if not registered; throws if the registered value is not assignable to `clazz`. |
| `hasExtension<T>(clazz: Class<T>)` | `Boolean` | True if an extension is registered under `clazz.name`. |

The core graph is the shared substrate: two hierarchies built over the same `HGGraph`
reference the **same `HGNode` instances** and the **same `HGCoreDependency` edges** — they
differ only in how those nodes are wired into a tree, and therefore in how the edges roll up.

### 2.3 HGCoreDependency

An atomic, directed dependency between two nodes. Implemented by `HGCoreDependencyImpl`.

| Property | Type | Description |
|----------|------|-------------|
| `from` | `HGNode` | The source node. Never null. |
| `to` | `HGNode` | The target node. Never null. |
| `type` | `String` | Dependency type classifier (e.g. `"USES"`, `"DEPENDS_ON"`). |
| `weight` | `Int` | Weight of this dependency. Mutable. Default: 1. |
| `attributesBitmap` | `Int` | Bit flags for domain-specific attributes. Mutable. Default: 0. |
| `dependencySource` | `IDependencySource` | Source metadata. Never null after construction. |

| Operation | Return | Description |
|-----------|--------|-------------|
| `getDependencySource<T>(clazz: Class<T>)` | `T?` | Returns the dependency source cast to `T` if it is an instance of `clazz`, null otherwise. |

### 2.4 AggregatedDependency

A virtual, computed dependency that summarizes all core dependencies between two subtrees.
Not directly constructed by clients — created and cached internally by `Hierarchy`
(via `getAggregatedDependency` or its batch variants). Implemented by
`AggregatedDependencyImpl`.

| Property | Type | Description |
|----------|------|-------------|
| `from` | `HGNode` | The source node (not necessarily the direct `from` of wrapped core deps). |
| `to` | `HGNode` | The target node. |
| `coreDependencies` | `List<HGCoreDependency>` | The wrapped core dependencies (see §3.3). |
| `aggregatedWeight` | `Int` | The total weight, computed as `coreDependencies.sumOf { it.weight }`. |

### 2.5 Node and dependency sources

The identity and metadata of a node or dependency is provided by a pluggable *source*
behind the `INodeSource` / `IDependencySource` SPIs.

**`INodeSource`**

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The unique identifier for the node. |
| `node` | `HGNode?` | Back-reference to the owning node. Set by the framework during construction. |

**`IDependencySource`**

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The unique identifier for the dependency. |
| `dependency` | `HGCoreDependency?` | Back-reference to the owning dependency. Set by the framework during construction. |

**`DefaultNodeSource`** / **`DefaultDependencySource`** — concrete sources with a
string-keyed `properties: MutableMap<String, String>` (defaulting to an empty map) and an
`identifier: Any`.

**`SyntheticNodeSource`** — a concrete `INodeSource` for synthetic (framework-created)
nodes:

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The node identifier. |
| `name` | `String` | Human-readable name. |
| `qualifiedName` | `String` | Fully-qualified name. Defaults to `name`. |

### 2.6 `HGGraphFactory`

Creates the flat core graph and its contents.

**`createHGGraph(): HGGraphImpl`** — creates a new, empty core graph.

**`createNode(graph: HGGraphImpl, nodeSourceSupplier: () -> INodeSource): HGNode`**

1. Create the node source via `nodeSourceSupplier()`.
2. Create a new `HGNodeImpl(nodeSource = source)`.
3. Set `source.node = node` (bidirectional).
4. Register `node` in the graph (`graph.registerNode(node)`), indexing it by identifier.
5. Return the node.

**`createCoreDependency(source: HGNode, target: HGNode, type: String, depSourceSupplier: () -> IDependencySource): HGCoreDependency`**

1. Create the dependency source via `depSourceSupplier()`.
2. Create a new `HGCoreDependencyImpl(from = source, to = target, type = type, dependencySource = depSource)`.
3. Set `depSource.dependency = dep` (bidirectional).
4. Add `dep` to `source`'s outgoing list and to `target`'s incoming list.
5. Return the dependency.

`weight` and `attributesBitmap` are not parameters; they default to `1` and `0` and are set
post-construction when needed.

---

## 3. Hierarchies and the multiple-hierarchies model

A `Hierarchy` is a tree structure layered over a shared `HGGraph`. It owns all
structure-dependent navigation and derived dependency computations. Multiple `Hierarchy`
instances may share the same core graph. Implemented by `HierarchyImpl`.

| Property | Type | Description |
|----------|------|-------------|
| `coreGraph` | `HGGraph` | The underlying core graph this hierarchy is layered over. |
| `rootNode` | `HGNode` | The root node of the tree. |
| `name` | `String?` | Human-readable name for this hierarchy. Mutable. |
| `localNodes` | `Collection<HGNode>` | Nodes created locally on this hierarchy (scenario-only; not present in the shared `coreGraph`). |

`HierarchyImpl` stores structure as two maps — `parentMap` (`childId -> parentId`) and
`childrenMap` (`parentId -> [childIds]`) — and derives everything else from them.

### 3.1 Structure

| Operation | Return | Description |
|-----------|--------|-------------|
| `parentOf(node)` | `HGNode?` | The parent of `node`, or null for the root (or for unattached nodes). |
| `childrenOf(node)` | `List<HGNode>` | The direct children of `node`. Empty if leaf. |
| `predecessorsOf(node)` | `List<HGNode>` | All ancestors `[parent, parent.parent, ...]`. Empty for the root. Cached. |
| `isPredecessorOf(ancestor, descendant)` | `Boolean` | True if `ancestor` is an ancestor of `descendant`. Equivalently `predecessorsOf(descendant).contains(ancestor)`. |
| `isSuccessorOf(descendant, ancestor)` | `Boolean` | True if `ancestor` is an ancestor of `descendant`. Equivalent to `isPredecessorOf(ancestor, descendant)`. |

Predecessors are derived by walking `parentMap` to the root:

```
H.predecessorsOf(root) = []
H.predecessorsOf(node) = [H.parentOf(node)] + H.predecessorsOf(H.parentOf(node))
```

### 3.2 Accumulated dependencies (hierarchy-dependent)

| Operation | Return | Description |
|-----------|--------|-------------|
| `accumulatedOutgoing(node)` | `List<HGCoreDependency>` | `node.outgoingCoreDependencies` + the `accumulatedOutgoing` of all children (recursive). Cached. |
| `accumulatedIncoming(node)` | `List<HGCoreDependency>` | `node.incomingCoreDependencies` + the `accumulatedIncoming` of all children (recursive). Cached. |

For a node `N` in hierarchy `H`:

```
H.accumulatedOutgoing(N) =
    N.outgoingCoreDependencies
    + concat( H.accumulatedOutgoing(child) for child in H.childrenOf(N) )
```

Symmetrically for `accumulatedIncoming` using `incomingCoreDependencies`.

### 3.3 Aggregated dependencies (hierarchy-dependent)

| Operation | Return | Description |
|-----------|--------|-------------|
| `getAggregatedDependency(from, to)` | `AggregatedDependency?` | The aggregated dependency summarizing all core deps from `from`'s subtree to `to`'s subtree. Returns null if there are no such deps. Cached. |
| `getAggregatedDependencies(from, targets)` | `List<AggregatedDependency>` | Batch: `targets.mapNotNull { getAggregatedDependency(from, it) }`. Entries with no deps are omitted. |
| `getAggregatedDependenciesFrom(to, sources)` | `List<AggregatedDependency>` | Batch: `sources.mapNotNull { getAggregatedDependency(it, to) }`. |

When `H.getAggregatedDependency(from = A, to = B)` is called:

1. Compute the cache key `A.identifier to B.identifier`. If a cached entry exists (which
   may itself be `null`), return it.
2. Otherwise compute the wrapped core dependencies:
   a. Take `H.accumulatedIncoming(B)`.
   b. Filter to keep only dependencies `d` where `d.from === A` or
      `H.isPredecessorOf(A, d.from)`.
3. If the filtered list is empty, cache `null` for the key and return `null`.
4. Otherwise create an `AggregatedDependency` with `from = A`, `to = B`, and the filtered
   core dependencies; its `aggregatedWeight` is the sum of the wrapped weights. Cache and
   return it.

**Note:** Aggregated dependencies are keyed and cached by the ordered pair
`(from.identifier, to.identifier)`. There is no separate per-node incoming/outgoing cache
and no bidirectional sharing of a single object: `getAggregatedDependency(A, B)` and
`getAggregatedDependency(B, A)` are independent entries.

### 3.4 Traversal

| Operation | Return | Description |
|-----------|--------|-------------|
| `traverse(node, action)` | `Unit` | Depth-first traversal of `node`'s subtree; runs `action` on each descendant. |
| `traverse(node, action, filter)` | `Unit` | Depth-first traversal; runs `action` only on descendants matching `filter`, and descends only into matching subtrees. |

The starting `node` itself is not visited; only its descendants are.

```
// traverse(node, action)
for child in childrenOf(node):
    action(child)
    traverse(child, action)

// traverse(node, action, filter) — descent is pruned to matching subtrees
for child in childrenOf(node):
    if filter(child):
        action(child)
        traverse(child, action, filter)
```

### 3.5 Local nodes (scenario-only)

| Operation | Return | Description |
|-----------|--------|-------------|
| `createLocalNode(kind, nodeSourceSupplier)` | `HGNode` | Creates a node held only by this hierarchy (in `localNodeMap`, not registered in the shared `coreGraph`). Sets `kind` and the bidirectional `nodeSource.node` link. |
| `lookupNode(identifier)` | `HGNode?` | Looks up `identifier` among this hierarchy's local nodes first, then falls back to `coreGraph.lookupNode(identifier)`. |

A local node is resolvable only through the hierarchy that created it (and its forks, which
copy the local-node map); it is not in `HGGraph.nodes`. This lets a scenario introduce
hypothetical nodes (e.g. a not-yet-existing module) that are invisible to every other view
and to the graph itself.

### 3.6 Mutation and forking (scenarios)

| Operation | Return | Description |
|-----------|--------|-------------|
| `addChild(parent, child)` | `Unit` | Attaches `child` under `parent`. Clears all caches. |
| `move(node, newParent)` | `Unit` | Detaches `node` from its old parent (if any) and attaches it under `newParent`. Clears all caches. |
| `fork()` | `Hierarchy` | Returns an independent hierarchy over the **same** `coreGraph`: deep-copies the structural maps (`parentMap`, `childrenMap`) and the local-node map, shares `coreGraph` and `rootNode` identity, copies `name`. Its derived caches start empty and rebuild lazily. |

`fork()` is the mechanism for evaluating a proposed re-modularization without disturbing the
loaded model: restructuring the fork (`move`, `addChild`) leaves the original hierarchy — and
every other view — unchanged. Mutating a fork's tree is safe; mutating a shared node's
intrinsic state (e.g. `kind`) is visible everywhere, so scenarios should restructure rather
than rewrite node state.

### 3.7 Per-hierarchy caching and invalidation

The structure-dependent computed properties (`predecessorsOf`, `accumulatedOutgoing`,
`accumulatedIncoming`, and aggregated dependencies) are computed on first access and cached
**on the hierarchy**, not on the node. Each hierarchy has its own caches, so two views over
the same graph never share derived results — which is correct, because the same node can
have a different ancestor chain and different rollups in each view.

Caching uses **nullable backing maps** on `HierarchyImpl`, populated lazily via `getOrPut`:

```kotlin
private var predecessorCache: MutableMap<Any, List<HGNode>>? = null
private var accOutCache: MutableMap<Any, List<HGCoreDependency>>? = null
private var accInCache: MutableMap<Any, List<HGCoreDependency>>? = null
private var aggDepCache: MutableMap<Pair<Any, Any>, AggregatedDependency?>? = null
```

Each map is keyed by node identifier (or, for aggregated dependencies, by the
`(from.identifier, to.identifier)` pair).

The mutators `addChild` and `move` both call the private `clearCaches()`, which sets all
four backing maps back to `null`, forcing recomputation on next access. Invalidation is
**whole-hierarchy** (all caches dropped at once), not scoped to a subtree. Mutating one
hierarchy does not affect the caches of any other hierarchy sharing the same core graph; a
fork's caches are independent of its source's. Cache management is internal to
`HierarchyImpl` and triggered automatically by its mutators.

### 3.8 `HierarchyFactory`

Creates and populates hierarchies over an existing core graph.

**`createHierarchy(coreGraph: HGGraph, rootNode: HGNode): HierarchyImpl`** — creates a new
hierarchy over `coreGraph` with the given `rootNode` and empty parent/children maps.

**`addChild(hierarchy: Hierarchy, parent: HGNode, child: HGNode)`** — records `parent` as
the parent of `child` and adds `child` to `parent`'s children list (both keyed by identifier
in the hierarchy's internal maps).

> `Hierarchy` also exposes its own `addChild`/`move` instance methods (§3.6), which
> additionally clear the hierarchy's caches. The `HierarchyFactory.addChild` helper writes
> the parent/children maps directly and is intended for the initial build phase before any
> caches are warm.

### 3.9 HGModel

A convenience wrapper bundling a core graph together with one hierarchy.

| Property | Type | Description |
|----------|------|-------------|
| `coreGraph` | `HGGraph` | The shared core graph. |
| `hierarchy` | `Hierarchy` | The hierarchy layered over the core graph. |

| Operation | Return | Description |
|-----------|--------|-------------|
| `lookupNode(identifier)` | `HGNode?` | Delegates to `hierarchy.lookupNode(identifier)`. |
| `fork()` | `HGModel` | Returns a new `HGModel` over the same `coreGraph` with a forked hierarchy. |
| `scoped(block: HierarchyScope.() -> Unit)` | `Unit` | Runs `block` with a `HierarchyScope` receiver bound to `hierarchy`. |
| `withScope(block: HierarchyScope.() -> R)` | `R` | Like `scoped`, but returns the block's result. |

### 3.10 HierarchyScope — node-centric ergonomics, scoped to one view

`HierarchyScope` is a receiver object that makes hierarchy navigation read like member access
on `HGNode`, scoped to one chosen hierarchy. It wraps a `Hierarchy` and exposes structural
and dependency operations as extension properties/functions on `HGNode`, delegating to the
underlying hierarchy. These accessors are valid only inside the scope and always mean "within
*this* hierarchy".

| Member | Type | Delegates to |
|--------|------|-------------|
| `coreGraph` | `HGGraph` | `hierarchy.coreGraph` |
| `HGNode.parent` | `HGNode?` | `hierarchy.parentOf(this)` |
| `HGNode.children` | `List<HGNode>` | `hierarchy.childrenOf(this)` |
| `HGNode.predecessors` | `List<HGNode>` | `hierarchy.predecessorsOf(this)` |
| `HGNode.hasChildren` | `Boolean` | `hierarchy.childrenOf(this).isNotEmpty()` |
| `HGNode.isPredecessorOf(other)` | `Boolean` | `hierarchy.isPredecessorOf(this, other)` |
| `HGNode.isSuccessorOf(other)` | `Boolean` | `hierarchy.isSuccessorOf(this, other)` |
| `HGNode.accumulatedOutgoingCoreDependencies` | `List<HGCoreDependency>` | `hierarchy.accumulatedOutgoing(this)` |
| `HGNode.accumulatedIncomingCoreDependencies` | `List<HGCoreDependency>` | `hierarchy.accumulatedIncoming(this)` |
| `HGNode.outgoingTo(target)` | `AggregatedDependency?` | `hierarchy.getAggregatedDependency(this, target)` |
| `HGNode.outgoingTo(targets)` | `List<AggregatedDependency>` | `hierarchy.getAggregatedDependencies(this, targets)` |
| `HGNode.incomingFrom(source)` | `AggregatedDependency?` | `hierarchy.getAggregatedDependency(source, this)` |
| `HGNode.incomingFrom(sources)` | `List<AggregatedDependency>` | `hierarchy.getAggregatedDependenciesFrom(this, sources)` |
| `HGNode.traverse(action)` | `Unit` | `hierarchy.traverse(this, action)` |

```kotlin
model.withScope {           // 'this' is a HierarchyScope over model.hierarchy
    node.parent             // -> hierarchy.parentOf(node)
    node.children           // -> hierarchy.childrenOf(node)
    node.predecessors       // -> hierarchy.predecessorsOf(node)
    node.accumulatedOutgoingCoreDependencies
    node.outgoingTo(target) // -> hierarchy.getAggregatedDependency(node, target)
    node.traverse { … }     // -> hierarchy.traverse(node) { … }
}
```

### 3.11 Consequences for callers and algorithms

- Anything holding an `HGNode` and wanting structure must also have access to the relevant
  `Hierarchy` (typically via the `HGModel`). A bare node is intentionally not enough.
- Algorithms that compute over structure take the hierarchy explicitly — e.g.
  `GraphUtils.createDependencyStructureMatrix(nodes, hierarchy)` and
  `GraphUtils.computePairwiseAggregation(nodes, hierarchy)` — because the same node set
  produces a different result under a different arrangement (§4).
- `accumulatedOutgoing(node)` and aggregated edges can differ between two hierarchies over
  the same graph. That is by design.

### 3.12 End-to-end example

```kotlin
// One graph, the baseline hierarchy.
val model: HGModel = /* loaded from the scan */

// Baseline rollup for a module, in the real arrangement.
val baseline = model.hierarchy.accumulatedOutgoing(moduleNode)

// Explore a re-modularization without touching the baseline.
val scenario = model.fork()                  // independent hierarchy, same nodes
scenario.hierarchy.move(typeNode, otherModuleNode)

// The fork sees the new structure; the original is unchanged.
val proposed = scenario.hierarchy.accumulatedOutgoing(moduleNode)
val unchanged = model.hierarchy.accumulatedOutgoing(moduleNode)  // == baseline

// Ergonomic reads within a chosen view.
model.withScope {
    moduleNode.children.flatMap { it.outgoingCoreDependencies }
}
```

---

## 4. Algorithms

The algorithms module provides four core capabilities:

1. **Strongly Connected Component (SCC) detection** — Tarjan's algorithm.
2. **Topological sorting** — FastFAS (Feedback Arc Set) heuristic with bubble-sort refinement.
3. **Dependency Structure Matrix (DSM)** — combines SCC detection and sorting into a single
   analysis.
4. **Adjacency representations** — matrix, list, and pairwise-aggregation forms.

All algorithms operate on a flat collection of `HGNode` instances together with the owning
`Hierarchy`. The dependency structure between these nodes is derived from the hierarchy's
structural navigation and aggregation API — primarily `hierarchy.accumulatedOutgoing(node)`
and `hierarchy.getAggregatedDependency(from, to)` (§3.2–3.3). Every public entry point
therefore takes a `hierarchy: Hierarchy` argument in addition to the node collection.

### 4.1 Public interfaces

**`INodeSorter`** — topological sorting of nodes, identifying cycle-breaking ("upward")
dependencies.

```kotlin
interface INodeSorter {
    fun sort(nodes: List<HGNode>, hierarchy: Hierarchy): SortResult
}

interface SortResult {
    val orderedNodes: List<HGNode>
    val upwardDependencies: List<AggregatedDependency>
}
```

- `orderedNodes` is a permutation of the input `nodes`, ordered such that dependencies flow
  "downward" (from earlier to later nodes) as much as possible.
- `upwardDependencies` contains the aggregated dependencies that violate the topological
  order — edges from a later node to an earlier node, i.e. the edges that would need to be
  removed to make the graph acyclic.

**`IDependencyStructureMatrix`** — a DSM combining topological ordering with cycle analysis.

```kotlin
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
| `isCellInCycle(i, j)` | True if both `orderedNodes[i]` and `orderedNodes[j]` belong to the same cycle. False if indices are out of bounds. |
| `isRowInCycle(i)` | True if `orderedNodes[i]` belongs to any cycle. Equivalent to `isCellInCycle(i, i)`. |
| `getWeight(i, j)` | The aggregated dependency weight from `orderedNodes[i]` to `orderedNodes[j]`. Returns 0 if no dependency exists, -1 if indices are out of bounds. |
| `getMatrix()` | The full NxN weight matrix where `matrix[i][j] = getWeight(i, j)`. |

### 4.2 GraphUtils — public facade

All algorithms are accessed through the `GraphUtils` object.

**SCC detection**

```kotlin
fun detectStronglyConnectedComponents(nodes: Collection<HGNode>, hierarchy: Hierarchy): List<List<HGNode>>
fun detectCycles(nodes: Collection<HGNode>, hierarchy: Hierarchy): List<List<HGNode>>
```

`detectStronglyConnectedComponents` returns **all** SCCs, including single-node components
(a single-node component means the node has no self-loop and is not part of a cycle).
`detectCycles` returns only SCCs with size > 1 (actual cycles).

**DSM creation**

```kotlin
fun createDependencyStructureMatrix(nodes: Collection<HGNode>, hierarchy: Hierarchy): IDependencyStructureMatrix
```

See §4.5 for the construction procedure.

**Adjacency matrix**

```kotlin
fun computeAdjacencyMatrix(nodes: List<HGNode>, hierarchy: Hierarchy): Array<IntArray>
```

Returns an NxN matrix where `matrix[i][j]` is the summed weight of all dependencies from
anything in subtree `i` to anything in subtree `j`. Weight is 0 if no dependency exists; the
diagonal carries each subtree's internal weight.

The matrix is built by a **single linear `O(V + E)` bucketing pass**: each selected
subtree's accumulated outgoing edges (`hierarchy.accumulatedOutgoing(node)`) are walked once,
and every edge is charged to the `(i, j)` cell of the selected nodes that contain its
endpoints. Endpoint-to-cell membership is resolved through a shared bucket map (built once by
traversing each selected subtree). For nested inputs, each contained node is attributed to
the later (higher-index) selected node that contains it.

**Adjacency list**

```kotlin
fun computeAdjacencyList(nodes: Collection<HGNode>, hierarchy: Hierarchy): Array<IntArray>
```

Returns an array of arrays where `result[i]` is the ascending, de-duplicated list of indices
`j` such that subtree `i` depends on subtree `j`. Uses the same single linear bucketing pass
as `computeAdjacencyMatrix`. A self-edge `i` appears only when subtree `i` has an internal
dependency.

**Pairwise aggregation**

```kotlin
fun computePairwiseAggregation(nodes: List<HGNode>, hierarchy: Hierarchy): List<AggregatedEdge>

data class AggregatedEdge(
    val fromIndex: Int,
    val toIndex: Int,
    val weight: Int,
    val typePairCount: Int,
    val attributesBitmap: Int,
)
```

Computes every non-empty **off-diagonal** aggregated edge among `nodes` in a single linear
pass — the matrix-shaped counterpart to calling `getAggregatedDependency` for each of the
`n²` cells. Each subtree's accumulated outgoing edges are walked once and bucketed into the
`(i, j)` cell of the selected nodes that contain its endpoints, accumulating:

- `weight` — the summed weight of the contributing dependencies,
- `typePairCount` — the number of distinct `(from.identifier, to.identifier)` pairs that
  contributed,
- `attributesBitmap` — the union (bitwise OR) of the contributing dependencies' attribute
  bitmaps.

Self-loops (`i == j`) and zero-weight cells are omitted. Indices are positions in `nodes`,
so callers that pass an already-ordered list (e.g. a DSM's `orderedNodes`) get edges indexed
in that order.

**Sorter factory**

```kotlin
fun createFasNodeSorter(): INodeSorter
```

Creates a FastFAS-based node sorter.

### 4.3 Tarjan's algorithm

Implemented by `Tarjan`.

**Input:** A collection of `HGNode` instances and the owning `Hierarchy`.

**Algorithm:**
1. Build an adjacency list from the nodes using `computeAdjacencyList(nodes, hierarchy)`.
2. Execute Tarjan's classic DFS-based SCC algorithm:
   - Maintain a DFS index counter, a working stack (an `ArrayDeque` pushed/popped at its
     tail), an `onStack` `BooleanArray` for O(1) stack-membership tests, and per-node
     `vindex`/`vlowlink` arrays.
   - For each unvisited node, perform DFS:
     - Assign `vindex[v] = vlowlink[v] = index++`.
     - Push `v` onto the stack and set `onStack[v] = true`.
     - For each neighbor `n` of `v`:
       - If unvisited: recurse, then `vlowlink[v] = min(vlowlink[v], vlowlink[n])`.
       - Else if `onStack[n]`: `vlowlink[v] = min(vlowlink[v], vindex[n])`.
     - If `vlowlink[v] == vindex[v]`: pop nodes from the stack tail (clearing `onStack`)
       until `v` is popped — these form an SCC.
3. Return all SCCs.

The traversal is recursive, so its depth is bounded by the input node count; it is intended
for DSM-sized selected node sets, not the full type graph.

**Complexity:** O(V + E).

### 4.4 FastFAS

Implemented by `FastFAS` (the Eades–Lin–Smyth greedy feedback-arc-set ordering) over a
weighted adjacency matrix.

**Input:** An NxN adjacency matrix (weights).
**Output:** An ordered sequence of node indices and a list of "skipped" (upward) edges.

**Algorithm:**
1. Initialize `vertices = {0..N-1}`, `s1 = []`, `s2 = []`, `skippedEdges = []`. Compute, in
   one pass over the matrix, the per-vertex weighted in/out degree (drives the max-delta
   choice) and unweighted in/out edge counts (drive sink/source detection), each excluding
   the diagonal. These degrees are **maintained incrementally** as vertices are removed.
2. While vertices is non-empty:
   a. **Find sink:** the lowest-index remaining vertex with no outgoing edges
      (`countOut == 0`). If found: remove it, prepend to `s2`. Continue.
   b. **Find source:** the lowest-index remaining vertex with no incoming edges
      (`countIn == 0`). If found: remove it, append to `s1`. Continue.
   c. **Find max-delta vertex:** the remaining vertex with maximum weighted
      (out-degree − in-degree), lowest index breaking ties. Record all incoming edges from
      remaining vertices as skipped edges, remove it, append to `s1`.
3. Result = `s1 ++ s2`.

Weighted degree drives the max-delta choice so the heaviest dependencies are least likely to
be cut; plain edge presence drives sink/source detection.

**Complexity:** O(n²) — the floor for a dense-matrix input, since the matrix itself is O(n²).

**Reference:** Eades, Lin, Smyth: "A fast and effective heuristic for the feedback arc set
problem" (1993).

### 4.5 FastFAS sorter and DSM construction

The `INodeSorter` implementation is `FastFasSorter`. The DSM is `DependencyStructureMatrixImpl`,
which combines Tarjan (for SCC decomposition) with `FastFasSorter` (for ordering within and
across SCCs).

**`FastFasSorter.sort(nodes, hierarchy)`**
1. Compute the adjacency matrix via `computeAdjacencyMatrix(nodes, hierarchy)`.
2. Run FastFAS on the matrix to get an initial ordering.
3. **Bubble-sort refinement:** for each pair of adjacent nodes in the ordering, if
   `matrix[ordered[i]][ordered[i-1]] > matrix[ordered[i-1]][ordered[i]]`, swap
   `ordered[i]` and `ordered[i-1]` and continue bubbling (so the heavier dependency
   direction ends up "downward").
4. Reverse the ordering.
5. Map indices back to `HGNode` objects → `orderedNodes`.
6. For each skipped edge from FastFAS, look up the aggregated dependency between the source
   and target nodes via `hierarchy.getAggregatedDependency(from, to)` → `upwardDependencies`.

**DSM construction (`createDependencyStructureMatrix`)**
1. Detect all SCCs using Tarjan's algorithm.
2. Sort each SCC (including single-node ones) using `FastFasSorter`.
3. Collect all upward dependencies from the sort results.
4. Build the ordered node list:
   a. First, place single-node SCCs whose node has no **direct** outgoing core dependencies
      — i.e. `node.outgoingCoreDependencies` is empty. This uses the node's own outgoing
      core-dependency edges, *not* aggregated dependencies; such a node is a pure sink and is
      placed first so it ends up last after the reversal in step 4c.
   b. Then place all remaining nodes (preserving their sorted order within each SCC).
   c. Reverse the entire list.
5. Filter `cycles` to only SCCs with size > 1.

The node-ordering assembly tracks "already added?" membership with a `HashSet` of node
identifiers, so the build is O(n).

`getMatrix()` and `getWeight(i, j)` are both backed by a **single, lazily-built weight
matrix** (computed once via `computeAdjacencyMatrix(orderedNodes, hierarchy)`, including the
subtree-internal diagonal). `getWeight` returns `-1` for out-of-bounds indices; otherwise it
indexes directly into the cached matrix.

Upward dependencies are always `AggregatedDependency` instances, obtained via
`hierarchy.getAggregatedDependency(from, to)`.

---

## 5. Serialization

The `io.hierograph.hierarchicalgraph.serialization` module provides a flat, ID-keyed JSON
serializer for `HGModel` instances using Jackson.

The HG model has cyclic structure (`from/to ↔ outgoing/incoming` references), parent/child
structure held in a `Hierarchy`, polymorphic `INodeSource` / `IDependencySource` SPIs, and
several derived caches on the hierarchy that would bloat output. Rather than configure a
generic object-graph serializer for all of this, the module projects the model to a flat
record stream — nodes by id, core dependencies referencing nodes by id — and round-trips it
via `HGGraphFactory` / `HierarchyFactory`. Caches are derived and rebuild lazily after load.

### 5.1 Public API

A single entry-point object, `HGGraphJson`. Everything else is in the `.internal`
subpackage and is not part of the public API.

```kotlin
object HGGraphJson {
    fun write(model: HGModel, prettyPrint: Boolean = false): String
    fun write(model: HGModel, sink: OutputStream, prettyPrint: Boolean = false)

    fun read(json: String): HGModel
    fun read(source: InputStream): HGModel
}
```

Out of the box, graphs whose nodes / dependencies use any of the following sources
round-trip:

- `DefaultNodeSource` / `DefaultDependencySource` — round-trip into the same type,
  preserving `properties` and identifier type.
- `GraphDbRootNodeSource` / `GraphDbNodeSource` / `GraphDbDependencySource` — round-trip
  into `DefaultNodeSource` / `DefaultDependencySource` (no Bolt at read time). The snapshot
  stores **identifier only** — lazy `labels` / `properties` are not force-loaded from Neo4j
  on write, and `GraphDbDependencySource.type` is dropped (the relationship type is already
  carried by `HGCoreDependency.type` and stored on the `DepRecord`).

Other `INodeSource` / `IDependencySource` impls fail fast on `write`. There is no public
hook to register custom codecs; the internal registry is promoted to public API when that
need arises.

`read` rejects snapshots whose `schemaVersion` differs from the current version with an
`IllegalArgumentException`.

### 5.2 Wire format (internal)

The on-disk shape, written by Jackson via the internal data classes:

```kotlin
data class GraphSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,   // SCHEMA_VERSION == 1
    val root: NodeRecord,
    val nodes: List<NodeRecord>,    // every non-root node, parents before children
    val deps:  List<DepRecord>      // every core dependency, deduplicated by identity
)

data class NodeRecord(
    val id: String,                 // String form of nodeSource.identifier
    val parentId: String?,          // null only for the root
    val kind: KindRef?,             // null when HGNode.kind is null
    val source: SourceRef
)

data class DepRecord(
    val id: String,                 // String form of dependencySource.identifier
    val fromId: String,
    val toId:   String,
    val type:   String,             // HGCoreDependency.type, e.g. "DEPENDS_ON"
    val weight: Int,
    val attributesBitmap: Int,      // raw bits; decode via JavaEdgeAttributes
    val source: SourceRef
)

data class KindRef(val type: String, val value: String)
data class SourceRef(val type: String, val payload: Map<String, String> = emptyMap())
```

Design choices baked in:

- **IDs as `String`.** `HGNode.identifier` / `dependencySource.identifier` are `Any` (in
  practice `Long`, `Int`, or `String`). Stringifying gives a stable wire form; per-source
  codecs own the coercion back.
- **`KindRef` carries `(type, value)`.** `HGNode.kind` is `Any?`. The pair stores the fully
  qualified class name and the string form, so any enum kind round-trips without the
  serialization module having to know about the enum class.
- **`SourceRef` is a discriminated `Map<String, String>` payload.** This keeps the snapshot
  open: it does not have to know every `INodeSource` / `IDependencySource` impl statically.

### 5.3 Codec SPI and registry (internal)

The serializer never reflects on `INodeSource` or `IDependencySource` directly — it defers
per-impl encoding to codecs registered in a `CodecRegistry`. A codec's `read` return type is
the SPI base (`INodeSource` / `IDependencySource`), not the type parameter `S`; this lets a
codec write one impl and read back a plain copy of a different impl (the graphdb codecs use
this to round-trip `GraphDb*Source` → `Default*Source` without a Bolt client at read time).

```kotlin
interface NodeSourceCodec<S : INodeSource> {
    val typeId: String                              // written to SourceRef.type
    val sourceClass: Class<S>
    fun write(source: S): Map<String, String>
    fun read(identifier: String, payload: Map<String, String>): INodeSource   // not S
}

interface DepSourceCodec<S : IDependencySource> { /* mirror */ }

class CodecRegistry {
    fun register(codec: NodeSourceCodec<*>): CodecRegistry
    fun register(codec: DepSourceCodec<*>): CodecRegistry

    fun nodeCodecFor(source: INodeSource): NodeSourceCodec<INodeSource>
    fun nodeCodecFor(typeId: String):      NodeSourceCodec<INodeSource>
    fun depCodecFor(source: IDependencySource): DepSourceCodec<IDependencySource>
    fun depCodecFor(typeId: String):            DepSourceCodec<IDependencySource>

    companion object {
        /** Registry preloaded with codecs for the default and graphdb sources. */
        fun defaults(): CodecRegistry
    }
}
```

Lookup by class matches the exact class first, then walks the source's superclasses, so
codecs registered for `DefaultNodeSource` also handle simple subclasses unless overridden by
an exact-class codec. Lookup by `typeId` is exact. Both lookups raise an error when no codec
matches.

**Codecs shipped out of the box.** `HGGraphJson` uses `CodecRegistry.defaults()`, which
preloads:

| typeId          | Writes                                                            | Reads back into                       |
|-----------------|-------------------------------------------------------------------|---------------------------------------|
| `default-node`  | `DefaultNodeSource.properties` plus `_idType`                     | `DefaultNodeSource(id, props)`        |
| `default-dep`   | `DefaultDependencySource.properties` plus `_idType`               | `DefaultDependencySource(id, props)`  |
| `graphdb-root`  | identifier + `_idType` only (no Bolt I/O, no `labels`/`properties`) | `DefaultNodeSource(id)`             |
| `graphdb-node`  | identifier + `_idType` only (no Bolt I/O, no `labels`/`properties`) | `DefaultNodeSource(id)`             |
| `graphdb-dep`   | identifier + `_idType` only (no Bolt I/O, drops `type`)           | `DefaultDependencySource(id)`         |

The `_idType` payload key (`long` / `int` / `string`) lets the reader coerce the wire string
back to the original identifier type via the shared `coerceIdentifier()` helper; adding new
identifier types is a codec-only change. Identifiers of any other type raise an
`UnsupportedOperationException` on write.

The three `graphdb-*` codecs are deliberately identifier-only: the read side has no Bolt
client, so all graphdb sources hydrate as plain `Default*Source` copies. To preserve
`labels` / `properties` in a future revision, extend the graphdb codecs to materialize them
on write and bump `GraphSnapshot.SCHEMA_VERSION`.

### 5.4 Kind round-trip

`HGNode.kind` is `Any?`. The serializer writes `KindRef(type, value)` where `type` is the
kind class's FQCN and `value` is its string form (via the internal `encodeKind` /
`decodeKind` helpers):

- **`null` kind** → `KindRef` is null in the wire record.
- **Enum kind** (e.g. `JavaNodeKind`) → `value = enum.name`. The reader resolves via
  `Class.forName(type).enumConstants.first { (it as Enum<*>).name == value }`.
- **String kind** → `type = "java.lang.String"`, `value = kindString`.

No codec registration is needed for these cases. Other kind classes raise an
`UnsupportedOperationException` on write.

### 5.5 Writer (internal)

`GraphWriter` performs one pre-order traversal of the hierarchy tree plus one identity-dedup
pass for dependencies:

```kotlin
class GraphWriter(private val codecs: CodecRegistry) {
    fun write(model: HGModel): GraphSnapshot
}
```

It reads the tree from `model.hierarchy`: it starts at `hierarchy.rootNode` and recurses
through `hierarchy.childrenOf(node)`, emitting the root into `GraphSnapshot.root` and all
other nodes into `nodes` in pre-order.

Invariants:

- **Traverse only `outgoingCoreDependencies`.** Every dependency appears in exactly one
  `outgoing` list and one `incoming` list (by `HGGraphFactory`'s construction); picking the
  outgoing side, accumulated across every visited node into a `LinkedHashSet`, avoids
  duplicates without identity-equality machinery on the wire.
- **Never touch derived caches.** `accumulatedOutgoing/Incoming`, `predecessorsOf(...)`,
  `getAggregatedDependency(...)` etc. on the `Hierarchy` are derived; touching them on the
  write path would force pointless work.

### 5.6 Reader (internal)

`GraphReader` performs two passes: create nodes (parents before children), then attach
dependencies:

```kotlin
class GraphReader(private val codecs: CodecRegistry) {
    fun read(snapshot: GraphSnapshot): HGModel
}
```

It rebuilds the model from its parts: creates an empty graph with
`HGGraphFactory.createHGGraph()`, materializes the root with
`HGGraphFactory.createNode(graph) { ... }`, then opens a `Hierarchy` via
`HierarchyFactory.createHierarchy(coreGraph, root)`. Each non-root node is created the same
way and attached with `HierarchyFactory.addChild(hierarchy, parent, node)`. Dependencies are
created with `HGGraphFactory.createCoreDependency(from, to, type) { ... }`. The result is
wrapped as `HGModel(coreGraph, hierarchy)`.

Invariants:

- **Parents before children.** `HierarchyFactory.addChild` requires the parent to already
  exist in the `byId` map; the writer's pre-order layout satisfies this in one linear pass.
  A non-root record with a `null` or unknown `parentId` is rejected with an
  `IllegalArgumentException`.
- **Caches stay cold.** The factories never populate the hierarchy's derived caches, so the
  deserialized model is clean — first access to `accumulatedOutgoing/Incoming` etc. rebuilds
  lazily.
- **`weight` and `attributesBitmap` are settable post-construction** on `HGCoreDependency`,
  which is why they are not part of `createCoreDependency`'s signature; the reader sets them
  from the `DepRecord` after creating each dependency.

### 5.7 Jackson configuration

`HGGraphJson` holds two long-lived `ObjectMapper` singletons (compact + pretty), both built
from `jacksonObjectMapper()` (the Kotlin module) with:

- `SerializationFeature.INDENT_OUTPUT` toggled per mapper.
- `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` disabled — leaving forward-compat room
  for schema fields added by later writers; combined with the explicit `schemaVersion` check
  for hard breaks.

`GraphReader.read` enforces `schemaVersion == GraphSnapshot.SCHEMA_VERSION` and throws
`IllegalArgumentException` otherwise.

### 5.8 Module layout

```
io.hierograph.hierarchicalgraph.serialization/
├── HGGraphJson.kt              ← public entry point
└── internal/
    ├── CodecRegistry.kt
    ├── DefaultCodecs.kt        (DefaultNodeSourceCodec, DefaultDependencySourceCodec; plus shared identifier helpers ID_TYPE_KEY, identifierTypeKey(), coerceIdentifier())
    ├── GraphDbCodecs.kt        (GraphDbRootNodeSourceCodec, GraphDbNodeSourceCodec, GraphDbDependencySourceCodec)
    ├── GraphReader.kt
    ├── GraphSnapshot.kt        (GraphSnapshot, NodeRecord, DepRecord, KindRef, SourceRef)
    ├── GraphWriter.kt
    ├── KindReferences.kt       (encodeKind / decodeKind)
    └── SourceCodecs.kt         (NodeSourceCodec, DepSourceCodec)
```

The `.internal` subpackage is kept off the public surface so it can evolve freely.

### 5.9 What serialization does NOT preserve

| Thing                                                | Behavior                                              |
|------------------------------------------------------|-------------------------------------------------------|
| Identity equality across round-trip                  | Broken; `node1 === node2` won't hold. Equality on `identifier` does. |
| `HGGraph` extensions (`registerExtension(...)`)      | Skipped. Runtime hooks (Spring beans, bolt clients) are caller-owned. |
| `AggregatedDependency`                               | Not serialized; the hierarchy regenerates it on demand from core deps. |
| `GraphDbDependencySource.userObject`                 | Not serialized; runtime-only annotation.              |
| `INodeSource.node` / `IDependencySource.dependency`  | Set automatically by `HGGraphFactory` on read.        |

**Approximate cost:** O(N + E) on both write and read. Wire size (gzipped JSON, rough):
~50–80 bytes per node, ~30–50 bytes per core dep. A 100k-node / 500k-edge graph lands
~30–60 MB uncompressed, ~5–15 MB gzipped.

---

## 6. Structural invariants

These invariants hold at all times after a factory function returns. Structural invariants
(parent/child, predecessors, accumulated, aggregated) are stated **relative to a hierarchy
`H`**.

1. **Tree structure.** Within `H`, every node except the root has at most one parent
   (`H.parentOf(node)`). No cycles in the parent chain. `H.parentOf(H.rootNode) == null`.
2. **Bidirectional parent-children.** For all non-root nodes,
   `H.childrenOf(H.parentOf(node)).contains(node)`; and `H.parentOf(child) == node` for all
   `child in H.childrenOf(node)`.
3. **Bidirectional nodeSource.** `node.nodeSource.node == node`.
4. **Bidirectional dependencySource.** `dep.dependencySource.dependency == dep`.
5. **Dependency list membership.** A core dependency `d` appears in
   `d.from.outgoingCoreDependencies` and `d.to.incomingCoreDependencies`.
6. **Identifier derivation.** `node.identifier == node.nodeSource.identifier`.
7. **Graph membership.** For every node `n` created via `HGGraphFactory.createNode`,
   `coreGraph.lookupNode(n.identifier) == n`. (Local nodes created via
   `Hierarchy.createLocalNode` are resolved by the hierarchy, not the core graph.)
8. **Accumulated deps include self.** `node.outgoingCoreDependencies` is a subset of
   `H.accumulatedOutgoing(node)`.
9. **Accumulated deps include children.** For every `child in H.childrenOf(node)`,
   `H.accumulatedOutgoing(child)` is a subset of `H.accumulatedOutgoing(node)`.
10. **Aggregated weight consistency.** For any `agg = H.getAggregatedDependency(a, b)`,
    `agg.aggregatedWeight == agg.coreDependencies.sumOf { it.weight }`.

---

## 7. Test model

The following graph structure (one core graph with a single hierarchy `H`) serves as the
canonical test model:

```
root
 +-- a1                deps: a1 -> b1 (USES, weight=1)
 |    +-- a2                  a1 -> b1 (DEPENDS_ON, weight=1)
 |         +-- a3             a2 -> b2 (USES, weight=1)
 +-- b1                       a3 -> b3 (DEPENDS_ON, weight=1)
      +-- b2
           +-- b3
```

**Predecessors:**
- `H.predecessorsOf(a3) == [a2, a1, root]`
- `H.predecessorsOf(a1) == [root]`
- `H.predecessorsOf(root) == []`

**isPredecessorOf:**
- `H.isPredecessorOf(root, a3) == true`
- `H.isPredecessorOf(a1, a3) == true`
- `H.isPredecessorOf(a1, b1) == false`

**Core dependencies:**
- `a1.outgoingCoreDependencies.size == 2` (the two a1->b1 deps)
- `a1.incomingCoreDependencies.size == 0`
- `b1.incomingCoreDependencies.size == 2`

**Accumulated dependencies:**
- `H.accumulatedOutgoing(root).size == 4` (all four deps)
- `H.accumulatedOutgoing(a1).size == 4` (a1's 2 + a2's 1 + a3's 1)
- `H.accumulatedOutgoing(a2).size == 2` (a2's 1 + a3's 1)

**Aggregated dependencies:**
- `H.getAggregatedDependency(a1, b1)`:
  - `coreDependencies.size == 4` (all deps flow from a1's subtree to b1's subtree)
  - `aggregatedWeight == 4`
- `H.getAggregatedDependency(a2, b2)`:
  - `coreDependencies.size == 2` (a2->b2 + a3->b3)
  - `aggregatedWeight == 2`
- `H.getAggregatedDependency(a1, b2)`:
  - Takes `H.accumulatedIncoming(b2)` = [a2->b2, a3->b3]
  - Filters where `dep.from === a1` or `H.isPredecessorOf(a1, dep.from)`:
    - a2->b2: `H.isPredecessorOf(a1, a2)` = true → keep
    - a3->b3: `H.isPredecessorOf(a1, a3)` = true → keep
  - `coreDependencies.size == 2`, `aggregatedWeight == 2`

---

## 8. Scope exclusions

- **Proxy dependencies** — there is no lazy proxy-dependency resolution mechanism.
- **GraphDB mapping** — populating the graph from Neo4j/Cypher is a separate concern.
- **Change observation** — there is no automatic change-notification/observer mechanism.
  Hierarchy mutators (`addChild`, `move`) clear the hierarchy's caches automatically; the
  core graph is otherwise expected to be read-only after construction.
- **Thread safety** — the model and algorithms are not thread-safe. Hierarchy caches are
  plain (non-synchronized) nullable backing maps populated lazily, so concurrent
  first-access reads against the same hierarchy may race; external synchronization is
  required if a hierarchy is read from multiple threads before its caches are warm, and
  during any mutation.
- **Alternative sorting strategies** — only FastFAS-based sorting is provided; other
  strategies may be added later.
