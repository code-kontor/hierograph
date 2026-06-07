# Hierarchical Graph Model — Kotlin Reimplementation Specification

This document specifies the data model, operations, invariants, and behavior of the
hierarchical graph that forms the core of slizaa. It serves as the contract for the
pure-Kotlin reimplementation that will replace the current EMF-based implementation.

---

## 1. Overview

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

Dependencies always connect two nodes and carry a type and weight. Two higher-level
concepts are derived from core dependencies, both computed **by a hierarchy** (because
they depend on the tree structure):

- **Accumulated dependencies** — the union of a node's own core dependencies and all
  core dependencies of its descendants (within a given hierarchy).
- **Aggregated dependencies** — a virtual edge between two nodes that summarizes all
  core dependencies flowing between their respective subtrees (within a given hierarchy).

`HGModel` bundles a core graph together with one hierarchy for convenient access.

---

## 2. Entities

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

**Operations:**

| Operation | Return | Description |
|-----------|--------|-------------|
| `getNodeSource<T>(clazz: Class<T>)` | `T?` | Returns the node source cast to `T` if it is an instance of `clazz`, null otherwise. |

> There is no dedicated root-node type. The root of a tree is a plain `HGNode` reached
> via `hierarchy.rootNode`. All structural navigation (`parent`, `children`,
> `predecessors`, `rootNode`, `accumulated*`, aggregated lookups, traversal) is provided
> by `Hierarchy`, not by `HGNode`.

### 2.2 HGGraph

The flat core graph. Owns the set of nodes and an extension registry. It has no notion
of hierarchy or root.

| Property | Type | Description |
|----------|------|-------------|
| `nodes` | `Collection<HGNode>` | All nodes registered in the graph. |

**Lookup:**

| Operation | Return | Description |
|-----------|--------|-------------|
| `lookupNode(identifier: Any)` | `HGNode?` | Returns the node with the given identifier, or null. Backed by an `identifier -> node` map maintained as nodes are created. |

**Extension Registry:**

The graph maintains a registry of named extensions (plugins). Extensions are keyed
either by class name (typed access) or by an arbitrary string key.

| Operation | Return | Description |
|-----------|--------|-------------|
| `registerExtension<T>(clazz: Class<T>, extension: T)` | `Unit` | Registers an extension keyed by `clazz.name`. Replaces any existing entry for that key. |
| `registerExtension(key: String, extension: Any)` | `Unit` | Registers an extension keyed by a string. |
| `getExtension<T>(clazz: Class<T>)` | `T?` | Returns the extension registered under `clazz.name`, cast to `T`. Null if not registered. |
| `getExtension<T>(key: String, clazz: Class<T>)` | `T?` | Returns the extension registered under `key`, cast to `T`. Null if not registered; throws if the registered value is not assignable to `clazz`. |
| `hasExtension<T>(clazz: Class<T>)` | `Boolean` | True if an extension is registered under `clazz.name`. |

### 2.3 Hierarchy

A tree structure layered over a shared `HGGraph`. Owns all structure-dependent
navigation and derived dependency computations. Multiple `Hierarchy` instances may share
the same core graph.

| Property | Type | Description |
|----------|------|-------------|
| `coreGraph` | `HGGraph` | The underlying core graph this hierarchy is layered over. |
| `rootNode` | `HGNode` | The root node of the tree. |
| `name` | `String?` | Human-readable name for this hierarchy. Mutable. |
| `localNodes` | `Collection<HGNode>` | Nodes created locally on this hierarchy (scenario-only; not present in the shared `coreGraph`). |

**Structure:**

| Operation | Return | Description |
|-----------|--------|-------------|
| `parentOf(node)` | `HGNode?` | The parent of `node`, or null for the root (or for unattached nodes). |
| `childrenOf(node)` | `List<HGNode>` | The direct children of `node`. Empty if leaf. |
| `predecessorsOf(node)` | `List<HGNode>` | All ancestors `[parent, parent.parent, ...]`. Empty for the root. Cached. |
| `isPredecessorOf(ancestor, descendant)` | `Boolean` | True if `ancestor` is an ancestor of `descendant`. Equivalently `predecessorsOf(descendant).contains(ancestor)`. |
| `isSuccessorOf(descendant, ancestor)` | `Boolean` | True if `ancestor` is an ancestor of `descendant`. Equivalent to `isPredecessorOf(ancestor, descendant)`. |

**Accumulated dependencies** (hierarchy-dependent):

| Operation | Return | Description |
|-----------|--------|-------------|
| `accumulatedOutgoing(node)` | `List<HGCoreDependency>` | `node.outgoingCoreDependencies` + the `accumulatedOutgoing` of all children (recursive). Cached. |
| `accumulatedIncoming(node)` | `List<HGCoreDependency>` | `node.incomingCoreDependencies` + the `accumulatedIncoming` of all children (recursive). Cached. |

**Aggregated dependencies** (hierarchy-dependent):

| Operation | Return | Description |
|-----------|--------|-------------|
| `getAggregatedDependency(from, to)` | `AggregatedDependency?` | The aggregated dependency summarizing all core deps from `from`'s subtree to `to`'s subtree. Returns null if there are no such deps. Cached. |
| `getAggregatedDependencies(from, targets)` | `List<AggregatedDependency>` | Batch: `targets.mapNotNull { getAggregatedDependency(from, it) }`. Entries with no deps are omitted. |
| `getAggregatedDependenciesFrom(to, sources)` | `List<AggregatedDependency>` | Batch: `sources.mapNotNull { getAggregatedDependency(it, to) }`. |

**Traversal:**

| Operation | Return | Description |
|-----------|--------|-------------|
| `traverse(node, action)` | `Unit` | Depth-first traversal of `node`'s subtree; runs `action` on each descendant (see Section 6). |
| `traverse(node, action, filter)` | `Unit` | Depth-first traversal; runs `action` only on descendants matching `filter`, and descends only into matching subtrees (see Section 6). |

**Local nodes (scenario-only):**

| Operation | Return | Description |
|-----------|--------|-------------|
| `createLocalNode(kind, nodeSourceSupplier)` | `HGNode` | Creates a node held only by this hierarchy (not registered in the shared `coreGraph`). Sets `kind` and the bidirectional `nodeSource.node` link. |
| `lookupNode(identifier)` | `HGNode?` | Looks up `identifier` among this hierarchy's local nodes first, then falls back to `coreGraph.lookupNode(identifier)`. |

**Mutation (for scenarios):**

| Operation | Return | Description |
|-----------|--------|-------------|
| `addChild(parent, child)` | `Unit` | Attaches `child` under `parent`. Clears all caches. |
| `move(node, newParent)` | `Unit` | Detaches `node` from its old parent (if any) and attaches it under `newParent`. Clears all caches. |
| `fork()` | `Hierarchy` | Returns a deep copy of the structure (independent parent/children maps) sharing the same `coreGraph`. Copies `name` and the local-node map. |

### 2.4 HGModel

A convenience wrapper bundling a core graph together with one hierarchy.

| Property | Type | Description |
|----------|------|-------------|
| `coreGraph` | `HGGraph` | The shared core graph. |
| `hierarchy` | `Hierarchy` | The hierarchy layered over the core graph. |

**Operations:**

| Operation | Return | Description |
|-----------|--------|-------------|
| `lookupNode(identifier)` | `HGNode?` | Delegates to `hierarchy.lookupNode(identifier)`. |
| `fork()` | `HGModel` | Returns a new `HGModel` over the same `coreGraph` with a forked hierarchy. |
| `scoped(block: HierarchyScope.() -> Unit)` | `Unit` | Runs `block` with a `HierarchyScope` receiver bound to `hierarchy`. |
| `withScope(block: HierarchyScope.() -> R)` | `R` | Like `scoped`, but returns the block's result. |

### 2.5 HierarchyScope

A receiver object that makes hierarchy navigation read like member access on `HGNode`. It
wraps a `Hierarchy` and exposes structural and dependency operations as extension
properties/functions on `HGNode`, delegating to the underlying hierarchy.

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

### 2.6 INodeSource

An interface providing identity and metadata for a node.

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The unique identifier for the node. |
| `node` | `HGNode?` | Back-reference to the owning node. Set by the framework during construction. |

### 2.7 IDependencySource

An interface providing identity and metadata for a core dependency.

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The unique identifier for the dependency. |
| `dependency` | `HGCoreDependency?` | Back-reference to the owning dependency. Set by the framework during construction. |

### 2.8 DefaultNodeSource

A concrete `INodeSource` with a string-keyed properties map.

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The node identifier. |
| `properties` | `MutableMap<String, String>` | Arbitrary key-value metadata. Defaults to an empty map. |

### 2.9 DefaultDependencySource

A concrete `IDependencySource` with a string-keyed properties map.

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The dependency identifier. |
| `properties` | `MutableMap<String, String>` | Arbitrary key-value metadata. Defaults to an empty map. |

### 2.10 SyntheticNodeSource

A concrete `INodeSource` for synthetic (framework-created) nodes that carries a name.

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The node identifier. |
| `name` | `String` | Human-readable name. |
| `qualifiedName` | `String` | Fully-qualified name. Defaults to `name`. |

### 2.11 HGCoreDependency

An atomic, directed dependency between two nodes.

| Property | Type | Description |
|----------|------|-------------|
| `from` | `HGNode` | The source node. Never null. |
| `to` | `HGNode` | The target node. Never null. |
| `type` | `String` | Dependency type classifier (e.g. `"USES"`, `"DEPENDS_ON"`). |
| `weight` | `Int` | Weight of this dependency. Mutable. Default: 1. |
| `attributesBitmap` | `Int` | Bit flags for domain-specific attributes. Mutable. Default: 0. |
| `dependencySource` | `IDependencySource` | Source metadata. Never null after construction. |

**Operations:**

| Operation | Return | Description |
|-----------|--------|-------------|
| `getDependencySource<T>(clazz: Class<T>)` | `T?` | Returns the dependency source cast to `T` if it is an instance of `clazz`, null otherwise. |

### 2.12 AggregatedDependency

A virtual, computed dependency that summarizes all core dependencies between two subtrees.
Not directly constructed by clients — created and cached internally by `Hierarchy` when
`getAggregatedDependency` (or one of its batch variants) is called.

| Property | Type | Description |
|----------|------|-------------|
| `from` | `HGNode` | The source node (not necessarily the direct `from` of wrapped core deps). |
| `to` | `HGNode` | The target node. |
| `coreDependencies` | `List<HGCoreDependency>` | The wrapped core dependencies (see Section 3.3). |
| `aggregatedWeight` | `Int` | The total weight, computed as `coreDependencies.sumOf { it.weight }`. |

---

## 3. Computed Properties — Detailed Semantics

All computations in this section are performed **by a hierarchy** and are relative to that
hierarchy's tree structure.

### 3.1 Accumulated Dependencies

For a node `N` in hierarchy `H`:

```
H.accumulatedOutgoing(N) =
    N.outgoingCoreDependencies
    + concat( H.accumulatedOutgoing(child) for child in H.childrenOf(N) )
```

Symmetrically for `accumulatedIncoming` using `incomingCoreDependencies`.

These are **cached** per node-identifier on the hierarchy and invalidated when the
hierarchy's structure changes (see Section 4).

### 3.2 Predecessors

```
H.predecessorsOf(root) = []
H.predecessorsOf(node) = [H.parentOf(node)] + H.predecessorsOf(H.parentOf(node))
```

Cached per node-identifier on the hierarchy.

### 3.3 Aggregated Dependency Computation

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

The batch variants are defined in terms of this single-pair operation:

- `getAggregatedDependencies(from, targets) = targets.mapNotNull { getAggregatedDependency(from, it) }`
- `getAggregatedDependenciesFrom(to, sources) = sources.mapNotNull { getAggregatedDependency(it, to) }`

**Note:** Aggregated dependencies are keyed and cached by the ordered pair
`(from.identifier, to.identifier)`. There is no separate per-node incoming/outgoing cache
and no bidirectional sharing of a single object: `getAggregatedDependency(A, B)` and
`getAggregatedDependency(B, A)` are independent entries.

---

## 4. Caching and Cache Invalidation

The core graph is normally built once via factory functions and then read. Structure-
dependent computed properties (`predecessorsOf`, `accumulatedOutgoing`,
`accumulatedIncoming`, and aggregated dependencies) are computed on first access and
cached **on the hierarchy**, not on the node.

Caching is implemented with **nullable backing maps** on `HierarchyImpl`, populated lazily
via `getOrPut`:

```kotlin
private var predecessorCache: MutableMap<Any, List<HGNode>>? = null
private var accOutCache: MutableMap<Any, List<HGCoreDependency>>? = null
private var accInCache: MutableMap<Any, List<HGCoreDependency>>? = null
private var aggDepCache: MutableMap<Pair<Any, Any>, AggregatedDependency?>? = null
```

Each map is keyed by node identifier (or, for aggregated dependencies, by the
`(from.identifier, to.identifier)` pair).

### 4.1 Cache Invalidation

The hierarchy structure may be mutated after computed properties have been read (via
`addChild` and `move`). Both mutation operations call the private `clearCaches()`, which
sets all four backing maps back to `null`, forcing recomputation on next access.

Invalidation is **whole-hierarchy** (all caches are dropped at once), not scoped to a
subtree. Mutating one hierarchy does not affect the caches of any other hierarchy sharing
the same core graph.

There is no separate `HGCacheInvalidator` utility; cache management is internal to
`HierarchyImpl` and triggered automatically by its mutators.

---

## 5. Factory Functions

Graph construction happens exclusively through factory objects. Clients do not
instantiate nodes, dependencies, or hierarchies directly.

### 5.1 `HGGraphFactory`

Creates the flat core graph and its contents.

#### `createHGGraph(): HGGraphImpl`

Creates a new, empty core graph.

#### `createNode(graph: HGGraphImpl, nodeSourceSupplier: () -> INodeSource): HGNode`

1. Create the node source via `nodeSourceSupplier()`.
2. Create a new `HGNodeImpl(nodeSource = source)`.
3. Set `source.node = node` (bidirectional).
4. Register `node` in the graph (`graph.registerNode(node)`), indexing it by identifier.
5. Return the node.

#### `createCoreDependency(source: HGNode, target: HGNode, type: String, depSourceSupplier: () -> IDependencySource): HGCoreDependency`

1. Create the dependency source via `depSourceSupplier()`.
2. Create a new `HGCoreDependencyImpl(from = source, to = target, type = type, dependencySource = depSource)`.
3. Set `depSource.dependency = dep` (bidirectional).
4. Add `dep` to `source`'s outgoing list and to `target`'s incoming list.
5. Return the dependency.

### 5.2 `HierarchyFactory`

Creates and populates hierarchies over an existing core graph.

#### `createHierarchy(coreGraph: HGGraph, rootNode: HGNode): HierarchyImpl`

Creates a new hierarchy over `coreGraph` with the given `rootNode` and empty
parent/children maps.

#### `addChild(hierarchy: Hierarchy, parent: HGNode, child: HGNode)`

Records `parent` as the parent of `child` and adds `child` to `parent`'s children list
(both keyed by identifier in the hierarchy's internal maps).

> Note: `Hierarchy` also exposes its own `addChild`/`move` instance methods (Section 2.3),
> which additionally clear the hierarchy's caches. The `HierarchyFactory.addChild` helper
> writes the parent/children maps directly and is intended for the initial build phase
> before any caches are warm.

---

## 6. Traversal

Depth-first traversal utilities, provided by `Hierarchy`.

### 6.1 `traverse(node: HGNode, action: (HGNode) -> Unit)`

Visits every **descendant** of `node` (the starting `node` itself is not visited), running
`action` on each.

```
for child in childrenOf(node):
    action(child)
    traverse(child, action)
```

### 6.2 `traverse(node: HGNode, action: (HGNode) -> Unit, filter: (HGNode) -> Boolean)`

Visits descendants, running `action` only on nodes matching `filter`. Descent is pruned:
traversal continues into a child's subtree only if that child matched the filter.

```
for child in childrenOf(node):
    if filter(child):
        action(child)
        traverse(child, action, filter)
```

> `HierarchyScope` additionally exposes `HGNode.traverse(action)` as a receiver-style
> shortcut for the two-argument form.

---

## 7. Structural Invariants

These invariants must hold at all times after a factory function returns. Structural
invariants (parent/child, predecessors, accumulated, aggregated) are stated **relative to
a hierarchy `H`**.

1. **Tree structure**: Within `H`, every node except the root has at most one parent
   (`H.parentOf(node)`). No cycles in the parent chain. `H.parentOf(H.rootNode) == null`.

2. **Bidirectional parent-children**: For all non-root nodes,
   `H.childrenOf(H.parentOf(node)).contains(node)`; and `H.parentOf(child) == node` for all
   `child in H.childrenOf(node)`.

3. **Bidirectional nodeSource**: `node.nodeSource.node == node`.

4. **Bidirectional dependencySource**: `dep.dependencySource.dependency == dep`.

5. **Dependency list membership**: A core dependency `d` appears in
   `d.from.outgoingCoreDependencies` and `d.to.incomingCoreDependencies`.

6. **Identifier derivation**: `node.identifier == node.nodeSource.identifier`.

7. **Graph membership**: For every node `n` created via `HGGraphFactory.createNode`,
   `coreGraph.lookupNode(n.identifier) == n`. (Local nodes created via
   `Hierarchy.createLocalNode` are resolved by the hierarchy, not the core graph.)

8. **Accumulated deps include self**: `node.outgoingCoreDependencies` is a subset of
   `H.accumulatedOutgoing(node)`.

9. **Accumulated deps include children**: For every `child in H.childrenOf(node)`,
   `H.accumulatedOutgoing(child)` is a subset of `H.accumulatedOutgoing(node)`.

10. **Aggregated weight consistency**: For any `agg = H.getAggregatedDependency(a, b)`,
    `agg.aggregatedWeight == agg.coreDependencies.sumOf { it.weight }`.

---

## 8. Test Model

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

### Expected behaviors on this model:

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
    - a2->b2: `H.isPredecessorOf(a1, a2)` = true -> keep
    - a3->b3: `H.isPredecessorOf(a1, a3)` = true -> keep
  - `coreDependencies.size == 2`, `aggregatedWeight == 2`

---

## 9. Scope Exclusions

The following are **not** part of this specification and will be addressed separately:

- **Proxy dependencies** — the EMF implementation had `HGProxyDependency` and
  `IProxyDependencyResolver` for lazy resolution of dependencies. This mechanism is not
  used and is excluded from the Kotlin reimplementation.
- **Algorithms** (DSM, topological sort, cycle detection) — separate module/spec.
- **GraphDB mapping** (populating the graph from Neo4j/Cypher) — separate concern.
- **Change observation** — there is no automatic change-notification/observer mechanism.
  Hierarchy mutators (`addChild`, `move`) clear the hierarchy's caches automatically; the
  core graph is otherwise expected to be read-only after construction.
- **Thread safety** — the model is not thread-safe. Hierarchy caches are plain
  (non-synchronized) nullable backing maps populated lazily, so concurrent first-access
  reads against the same hierarchy may race; external synchronization is required if a
  hierarchy is read from multiple threads before its caches are warm, and during any
  mutation.
- **Serialization** (XMI or other) — not in initial scope.
