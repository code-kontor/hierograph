# Hierarchical Graph Model — Kotlin Reimplementation Specification

This document specifies the data model, operations, invariants, and behavior of the
hierarchical graph that forms the core of slizaa. It serves as the contract for the
pure-Kotlin reimplementation that will replace the current EMF-based implementation.

---

## 1. Overview

The hierarchical graph is a tree of **nodes** connected by **core dependencies**.
Nodes form a strict parent-child hierarchy (a forest rooted at a single `HGRootNode`).
Dependencies always connect two nodes and carry a type and weight.

Two higher-level concepts are derived from core dependencies:

- **Accumulated dependencies** — the union of a node's own core dependencies and all
  core dependencies of its descendants.
- **Aggregated dependencies** — a virtual edge between two nodes that summarizes all
  core dependencies flowing between their respective subtrees.

---

## 2. Entities

### 2.1 HGNode

A node in the hierarchical graph.

| Property | Type | Description |
|----------|------|-------------|
| `kind` | `Any?` | Application-defined classifier (e.g. a `JavaNodeKind` enum value). May be null. |
| `parent` | `HGNode?` | The parent node. Null only for the root node. |
| `children` | `List<HGNode>` | Ordered list of direct children. Empty if leaf. |
| `nodeSource` | `INodeSource` | The source that provides this node's identity and metadata. Never null after construction. |

**Derived properties** (not stored, computed on access):

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | Shortcut for `nodeSource.identifier`. |
| `rootNode` | `HGRootNode` | The root of the tree. Computed by walking up the parent chain. For the root node itself, returns `this`. |
| `predecessors` | `List<HGNode>` | All ancestors: `[parent, parent.parent, ...]`. Empty for root. |
| `outgoingCoreDependencies` | `List<HGCoreDependency>` | Core dependencies where `from == this`. |
| `incomingCoreDependencies` | `List<HGCoreDependency>` | Core dependencies where `to == this`. |
| `accumulatedOutgoingCoreDependencies` | `List<HGCoreDependency>` | `outgoingCoreDependencies` + all children's `accumulatedOutgoingCoreDependencies` (recursive). |
| `accumulatedIncomingCoreDependencies` | `List<HGCoreDependency>` | `incomingCoreDependencies` + all children's `accumulatedIncomingCoreDependencies` (recursive). |

**Operations:**

| Operation | Return | Description |
|-----------|--------|-------------|
| `isPredecessorOf(node)` | `Boolean` | True if `this` is an ancestor of `node`. Equivalently: `node.predecessors.contains(this)`. Returns false if `node` is null. |
| `isSuccessorOf(node)` | `Boolean` | True if `node` is an ancestor of `this`. Equivalently: `node.isPredecessorOf(this)`. Returns false if `node` is null. |
| `getOutgoingDependenciesTo(target: HGNode)` | `HGAggregatedDependency?` | Returns the aggregated dependency summarizing all core deps from this node's subtree to `target`'s subtree. Returns null if the aggregated weight is 0. |
| `getOutgoingDependenciesTo(targets: List<HGNode>)` | `List<HGAggregatedDependency>` | Batch version. The result list may contain fewer elements than `targets` (entries with weight 0 are omitted). |
| `getIncomingDependenciesFrom(source: HGNode)` | `HGAggregatedDependency?` | Symmetric to `getOutgoingDependenciesTo`. |
| `getIncomingDependenciesFrom(sources: List<HGNode>)` | `List<HGAggregatedDependency>` | Batch version. |
| `getNodeSource<T>(clazz: Class<T>)` | `T?` | Returns the node source cast to `T` if it is an instance of `clazz`, null otherwise. |

### 2.2 HGRootNode

The root of the hierarchy. Extends `HGNode`.

| Property | Type | Description |
|----------|------|-------------|
| `name` | `String?` | Human-readable name for this graph. |

**Overridden behavior:**

- `parent` is always null.
- `predecessors` is always empty.
- `rootNode` returns `this`.

**Extension Registry:**

The root node maintains a registry of named extensions (plugins). Extensions are keyed
either by class name (typed access) or by an arbitrary string key.

| Operation | Return | Description |
|-----------|--------|-------------|
| `registerExtension<T>(clazz: Class<T>, extension: T)` | `Unit` | Registers an extension keyed by `clazz.name`. Replaces any existing entry for that key. |
| `registerExtension(key: String, extension: Any)` | `Unit` | Registers an extension keyed by a string. |
| `getExtension<T>(clazz: Class<T>)` | `T?` | Returns the extension registered under `clazz.name`, cast to `T`. Null if not registered. |
| `getExtension<T>(key: String, clazz: Class<T>)` | `T?` | Returns the extension registered under `key`, cast to `T`. Throws if the registered value is not assignable to `clazz`. |
| `hasExtension<T>(clazz: Class<T>)` | `Boolean` | True if an extension is registered under `clazz.name`. |
| `hasExtension<T>(key: String, clazz: Class<T>)` | `Boolean` | True if an extension is registered under `key` and is assignable to `clazz`. |

**Cache Management:**

| Operation | Description |
|-----------|-------------|
| `invalidateAllCaches()` | Invalidates all cached derived properties on every node in the tree. |
| `invalidateCaches(nodes: List<HGNode>)` | Invalidates caches on the given nodes **and all their ancestors** (up to and including the root). |
| `initializeCaches(nodes: List<HGNode>)` | Pre-computes caches on the given nodes **and all their ancestors**. |
| `lookupNode(identifier: Any)` | `HGNode?` | Returns the node with the given identifier. Uses a lazily-built `identifier -> node` map that indexes all nodes in the tree on first call. |

### 2.3 INodeSource

An interface providing identity and metadata for a node.

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The unique identifier for the node. |
| `node` | `HGNode?` | Back-reference to the owning node. Set by the framework during construction. |

### 2.4 IDependencySource

An interface providing identity and metadata for a core dependency.

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The unique identifier for the dependency. |
| `dependency` | `HGCoreDependency?` | Back-reference to the owning dependency. Set by the framework during construction. |

### 2.5 DefaultNodeSource

A concrete `INodeSource` with a string-keyed properties map.

| Property | Type | Description |
|----------|------|-------------|
| `properties` | `MutableMap<String, String>` | Arbitrary key-value metadata. |


### 2.6 DefaultDependencySource

A concrete `IDependencySource` with a string-keyed properties map.

| Property | Type | Description |
|----------|------|-------------|
| `properties` | `MutableMap<String, String>` | Arbitrary key-value metadata. |

### 2.7 HGCoreDependency

An atomic, directed dependency between two nodes.

| Property | Type | Description |
|----------|------|-------------|
| `from` | `HGNode` | The source node. Never null. |
| `to` | `HGNode` | The target node. Never null. |
| `type` | `String` | Dependency type classifier (e.g. `"USES"`, `"DEPENDS_ON"`). |
| `weight` | `Int` | Weight of this dependency. Default: 1. |
| `attributesBitmap` | `Int` | Bit flags for domain-specific attributes. Default: 0. |
| `dependencySource` | `IDependencySource` | Source metadata. Never null after construction. |

**Derived:**

| Property | Type | Description |
|----------|------|-------------|
| `rootNode` | `HGRootNode` | Shortcut for `from.rootNode`. |

**Operations:**

| Operation | Return | Description |
|-----------|--------|-------------|
| `getDependencySource<T>(clazz: Class<T>)` | `T?` | Returns the dependency source cast to `T` if it is an instance of `clazz`, null otherwise. |

### 2.8 HGAggregatedDependency

A virtual, computed dependency that summarizes all core dependencies between two subtrees.
Not directly constructed by clients — created and cached internally when
`getOutgoingDependenciesTo` or `getIncomingDependenciesFrom` is called.

| Property | Type | Description |
|----------|------|-------------|
| `from` | `HGNode` | The source node (not necessarily the direct `from` of wrapped core deps). |
| `to` | `HGNode` | The target node. |
| `coreDependencies` | `List<HGCoreDependency>` | The wrapped core dependencies. Computed lazily (see Section 3.3). |
| `aggregatedWeight` | `Int` | The total weight. Computed lazily (see Section 3.3). |

---

## 3. Computed Properties — Detailed Semantics

### 3.1 Accumulated Dependencies

For a node `N`:

```
accumulatedOutgoingCoreDependencies(N) =
    N.outgoingCoreDependencies
    + union( accumulatedOutgoingCoreDependencies(child) for child in N.children )
```

Symmetrically for `accumulatedIncomingCoreDependencies`.

These are **cached** and invalidated when the graph structure changes (see Section 4).

### 3.2 Predecessors

```
predecessors(root) = []
predecessors(node) = [node.parent] + predecessors(node.parent)
```

Cached per node.

### 3.3 Aggregated Dependency Computation

When `A.getOutgoingDependenciesTo(B)` is called:

1. If a cached `HGAggregatedDependency(from=A, to=B)` exists, return it (or null if weight is 0).
2. Otherwise, create a new `HGAggregatedDependency` with `from=A`, `to=B`.
3. **Initialize** it:
   a. Take `B.accumulatedIncomingCoreDependencies`.
   b. Filter to keep only dependencies `d` where `d.from == A` or `A.isPredecessorOf(d.from)`.
   c. This filtered list becomes `coreDependencies`.
   d. Compute `aggregatedWeight` as the sum of `weight` of all `coreDependencies`.
4. Cache the aggregated dependency on both sides:
   - In `A`'s outgoing aggregated map (keyed by `B`).
   - In `B`'s incoming aggregated map (keyed by `A`).
5. Return the aggregated dependency if `aggregatedWeight > 0`, else return null.

`getIncomingDependenciesFrom(B)` on node `A` is symmetric: it creates an aggregated
dependency with `from=B`, `to=A`.

**Invariant:** The aggregated dependency cache is **bidirectional** — when `A.getOutgoingDependenciesTo(B)` creates an aggregated dep, it is stored in both `A`'s outgoing map and `B`'s incoming map. A subsequent call to `B.getIncomingDependenciesFrom(A)` returns the same object.

---

## 4. Cache Invalidation

Each node maintains the following caches:
- `predecessors` list
- `accumulatedOutgoingCoreDependencies` list
- `accumulatedIncomingCoreDependencies` list
- Map of `HGNode -> HGAggregatedDependency` for outgoing aggregated deps
- Map of `HGNode -> HGAggregatedDependency` for incoming aggregated deps

### 4.1 `invalidateLocalCaches(node)`

Resets all five caches on `node`:
- Marks predecessors, accumulated outgoing, and accumulated incoming as uninitialized.
- For each entry in both aggregated dependency maps: invalidates the aggregated dependency
  (marks it as uninitialized so `coreDependencies` and `aggregatedWeight` are recomputed
  on next access).

Does **not** clear the aggregated dependency maps themselves — only invalidates their entries.

### 4.2 `invalidateCaches(nodes)`

For each node in `nodes`: invalidate `node` **and every ancestor** up to the root.

Specifically: collect `{node} + node.predecessors` for each node in the input, then call
`invalidateLocalCaches` on each collected node.

### 4.3 `invalidateAllCaches()`

Walk the entire tree (all descendants of root) and call `invalidateLocalCaches` on every node.

### 4.4 `initializeCaches(nodes)`

For each node in `nodes` and all their ancestors: force-compute all cached properties
(predecessors, accumulated deps, and re-initialize all aggregated dep entries).

---

## 5. Factory Functions

Graph construction happens exclusively through factory functions. Clients do not
instantiate nodes or dependencies directly.

### 5.1 `createRootNode(nodeSourceSupplier: () -> INodeSource): HGRootNode`

1. Create a new `HGRootNode`.
2. Create the node source via `nodeSourceSupplier()`.
3. Set `rootNode.nodeSource = source` and `source.node = rootNode` (bidirectional).
4. Return the root node.

### 5.2 `createNode(rootNode: HGRootNode, parent: HGNode, nodeSourceSupplier: () -> INodeSource): HGNode`

1. Create a new `HGNode`.
2. Set `node.parent = parent` and add `node` to `parent.children` (bidirectional).
3. Create the node source via `nodeSourceSupplier()`.
4. Set `node.nodeSource = source` and `source.node = node` (bidirectional).
5. Register `node` in `rootNode`'s identifier-to-node map: `map[node.identifier] = node`.
6. Return the node.

### 5.3 `createCoreDependency(source: HGNode, target: HGNode, type: String, depSourceSupplier: () -> IDependencySource, reinitializeCaches: Boolean = false): HGCoreDependency`

1. Create a new `HGCoreDependency`.
2. Set `dep.from = source`, `dep.to = target`, `dep.type = type`.
3. Create the dependency source via `depSourceSupplier()`.
4. Set `dep.dependencySource = depSource` and `depSource.dependency = dep` (bidirectional).
5. Add `dep` to `source.outgoingCoreDependencies`.
6. Add `dep` to `target.incomingCoreDependencies`.
7. Call `source.rootNode.invalidateCaches([source, target])`.
8. If `reinitializeCaches`: call `source.rootNode.initializeCaches([source, target])`.
9. Return the dependency.

### 5.4 `removeDependency(dependency: HGCoreDependency, invalidateCaches: Boolean = true)`

1. Remove `dependency` from `dependency.from.outgoingCoreDependencies`.
2. Remove `dependency` from `dependency.to.incomingCoreDependencies`.
3. If `invalidateCaches`: call `dependency.from.rootNode.invalidateCaches([dependency.from, dependency.to])`.

---

## 6. Traversal

Depth-first pre-order traversal utilities.

### 6.1 `traverse(node: HGNode, action: (HGNode) -> Unit)`

Visit every node in the subtree rooted at `node` (including `node` itself).

```
action(node)
for child in node.children:
    traverse(child, action)
```

### 6.2 `traverse(node: HGNode, action: (HGNode) -> Unit, filter: (HGNode) -> Boolean)`

Visit every node in the subtree, but only execute `action` on nodes matching `filter`.
Traversal continues into children regardless of whether the parent matched.

```
if filter(node): action(node)
for child in node.children:
    traverse(child, action, filter)
```

### 6.3 `traverseWithPruning(node: HGNode, action: (HGNode) -> Unit, descendInto: (HGNode) -> Boolean)`

Execute `action` on every visited node. If `descendInto` returns false, skip that node's
children (prune the subtree). The action is still executed on the node itself before the
descent check.

```
action(node)
if descendInto(node):
    for child in node.children:
        traverseWithPruning(child, action, descendInto)
```

---

## 7. Structural Invariants

These invariants must hold at all times after a factory function returns:

1. **Tree structure**: Every node except the root has exactly one parent. No cycles in parent chain. The root's parent is null.

2. **Bidirectional parent-children**: `node.parent.children.contains(node)` for all non-root nodes. `child.parent == node` for all `child in node.children`.

3. **Bidirectional nodeSource**: `node.nodeSource.node == node`.

4. **Bidirectional dependencySource**: `dep.dependencySource.dependency == dep`.

5. **Dependency list membership**: A core dependency `d` appears in `d.from.outgoingCoreDependencies` and `d.to.incomingCoreDependencies`.

6. **rootNode derivation**: `node.rootNode == node.parent.rootNode` for non-root nodes. For root: `rootNode == this`.

7. **identifier derivation**: `node.identifier == node.nodeSource.identifier`.

8. **Accumulated deps include self**: `node.outgoingCoreDependencies` is a subset of `node.accumulatedOutgoingCoreDependencies`.

9. **Accumulated deps include children**: For every `child` in `node.children`, `child.accumulatedOutgoingCoreDependencies` is a subset of `node.accumulatedOutgoingCoreDependencies`.

10. **Aggregated dep symmetry**: If `A.getOutgoingDependenciesTo(B)` returns `aggDep`, then `B.getIncomingDependenciesFrom(A)` returns the same `aggDep` object.

---

## 8. Test Model

The following graph structure serves as the canonical test model:

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
- `a3.predecessors == [a2, a1, root]`
- `a1.predecessors == [root]`
- `root.predecessors == []`

**isPredecessorOf:**
- `root.isPredecessorOf(a3) == true`
- `a1.isPredecessorOf(a3) == true`
- `a1.isPredecessorOf(b1) == false`

**Core dependencies:**
- `a1.outgoingCoreDependencies.size == 2` (the two a1->b1 deps)
- `a1.incomingCoreDependencies.size == 0`
- `b1.incomingCoreDependencies.size == 2`

**Accumulated dependencies:**
- `root.accumulatedOutgoingCoreDependencies.size == 4` (all four deps)
- `a1.accumulatedOutgoingCoreDependencies.size == 4` (a1's 2 + a2's 1 + a3's 1)
- `a2.accumulatedOutgoingCoreDependencies.size == 2` (a2's 1 + a3's 1)

**Aggregated dependencies:**
- `a1.getOutgoingDependenciesTo(b1)`:
  - `coreDependencies.size == 4` (all deps flow from a1's subtree to b1's subtree)
  - `aggregatedWeight == 4`
- `a2.getOutgoingDependenciesTo(b2)`:
  - `coreDependencies.size == 2` (a2->b2 + a3->b3)
  - `aggregatedWeight == 2`
- `a1.getOutgoingDependenciesTo(b2)` — null (no direct dep from a1 subtree into b2 subtree... actually: a2->b2 and a3->b3 flow from a1's subtree to b1's subtree; but b2 is in b1's subtree, so this should return the deps where target is b2 or a descendant of b2)

Correction for `a1.getOutgoingDependenciesTo(b2)`:
  - Takes `b2.accumulatedIncomingCoreDependencies` = [a2->b2, a3->b3]
  - Filters where `dep.from == a1` or `a1.isPredecessorOf(dep.from)`:
    - a2->b2: `a1.isPredecessorOf(a2)` = true -> keep
    - a3->b3: `a1.isPredecessorOf(a3)` = true -> keep
  - `coreDependencies.size == 2`, `aggregatedWeight == 2`

---

## 9. Scope Exclusions

The following are **not** part of this specification and will be addressed separately:

- **Proxy dependencies** — the EMF implementation had `HGProxyDependency` and `IProxyDependencyResolver` for lazy resolution of dependencies. This mechanism is not used and is excluded from the Kotlin reimplementation.
- **Algorithms** (DSM, topological sort, cycle detection) — separate module/spec.
- **GraphDB mapping** (populating the graph from Neo4j/Cypher) — separate concern.
- **Change observation** — the EMF implementation used EMF notifications (adapters). Not carried over. If change observation is needed later, it will be designed as a Kotlin-native mechanism.
- **Thread safety** — the model is not thread-safe, matching the EMF implementation. Concurrent access must be externally synchronized.
- **Serialization** (XMI or other) — not in initial scope.
