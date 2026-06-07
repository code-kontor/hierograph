# The Multiple-Hierarchies Model

This document explains the design that lets several independent tree views share one set of
nodes and edges: the separation of the **core graph** (identity and dependency facts) from
the **hierarchy** (a tree layered over those nodes), and the forking/scoping facilities built
on top. It is the conceptual companion to `hierarchicalgraph-kotlin-spec.md`, which is the
member-by-member reference for the same types.

## 1. Why structure was moved off the node

Earlier, a node was self-navigating: it carried `parent`, `children`, `predecessors`,
accumulated dependencies, and a back-reference to its root. That made a node implicitly bound
to exactly one tree — there was no way for the same node to appear in two different
arrangements, and any "what-if" restructuring meant cloning nodes.

The model now separates two concerns:

- **What a node *is*** — its identity and its own (direct) dependency edges. This is intrinsic
  and shared.
- **How nodes are *arranged*** — parent/child structure and everything derived from it
  (ancestors, accumulated and aggregated dependencies). This is a *view*, and there can be
  many of them over the same nodes.

The first lives in the **core graph** (`HGGraph`), the second in a **hierarchy**
(`Hierarchy`). The same `HGNode` can participate in any number of `Hierarchy` instances at
once, each imposing its own tree.

## 2. What lives where

| Concept | Owner | Notes |
|---|---|---|
| Node identity (`identifier`, `nodeSource`, `kind`) | `HGNode` | Intrinsic, shared across all hierarchies. |
| Direct core dependencies (`outgoing/incomingCoreDependencies`) | `HGNode` | Edge facts are intrinsic — the dependency between two types exists regardless of how they are grouped. |
| The node set + lookup + extension registry | `HGGraph` | Flat; no notion of tree or root. |
| Parent/child, predecessors, root | `Hierarchy` | A tree layered over the graph's nodes. |
| Accumulated dependencies (subtree rollups) | `Hierarchy` | Derived from structure → hierarchy-dependent. |
| Aggregated dependencies (pairwise subtree edges) | `Hierarchy` | Derived from structure → hierarchy-dependent. |
| Traversal, node lookup-by-id | `Hierarchy` | Lookup also resolves hierarchy-local nodes (see §5). |
| A convenient (graph, hierarchy) pair | `HGModel` | What most callers hold and pass around. |

The key consequence: **`HGNode` carries no structural information.** Anything that depends on
*where* a node sits in a tree is a method on `Hierarchy`, not on the node:

```
node.outgoingCoreDependencies          // intrinsic — on the node
hierarchy.childrenOf(node)             // structural — on the hierarchy
hierarchy.accumulatedOutgoing(node)    // derived from structure — on the hierarchy
hierarchy.getAggregatedDependency(a,b) // derived from structure — on the hierarchy
```

## 3. The core graph as the shared substrate

`HGGraph` holds every node once, keyed by identifier (`lookupNode(id)`), plus an extension
registry for plugins (e.g. the bolt client used for lazy property loading). It has no root
and no parent/child links. Two hierarchies built over the same `HGGraph` reference the **same
`HGNode` instances** — they differ only in how those nodes are wired into a tree.

Because dependency *edges* (`HGCoreDependency`) are intrinsic to the nodes, they are also
shared: both hierarchies see the same `node.outgoingCoreDependencies`. What differs is how
those edges *roll up*, since accumulation and aggregation follow each hierarchy's own
parent/child structure.

## 4. Hierarchy-dependent derivations and per-hierarchy caching

`Hierarchy` stores structure as two maps — `parentMap` (`childId -> parentId`) and
`childrenMap` (`parentId -> [childIds]`) — and derives everything else from them:

- `predecessorsOf(node)` walks `parentMap` to the root.
- `accumulatedOutgoing/Incoming(node)` unions the node's own edges with those of all
  descendants.
- `getAggregatedDependency(from, to)` summarizes the core edges flowing from `from`'s subtree
  into `to`'s subtree.

These derivations are **cached on the hierarchy** (`predecessorCache`, `accOutCache`,
`accInCache`, `aggDepCache`). Each hierarchy has its own caches, so two views over the same
graph never share derived results — which is correct, because the same node can have a
different ancestor chain and different rollups in each view. Any structural mutation
(`addChild`, `move`) clears that hierarchy's caches (`clearCaches()`); the other hierarchies
and the shared graph are untouched.

## 5. Forking and scenarios

Two facilities make "what-if" restructuring cheap and isolated:

**`Hierarchy.fork()` / `HGModel.fork()`** — produce an independent hierarchy over the **same**
core graph. The fork deep-copies the structural maps (`parentMap`, `childrenMap`) and the
local-node map, and shares the `coreGraph` and `rootNode` identity; its derived caches start
empty and rebuild lazily. Restructuring the fork (`move`, `addChild`) leaves the original
hierarchy — and every other view — unchanged. This is the mechanism for evaluating a proposed
re-modularization without disturbing the loaded model.

**Hierarchy-local nodes** — `createLocalNode(kind) { … }` adds a synthetic node that exists
**only in that hierarchy** (kept in `localNodeMap`, not registered in the shared `HGGraph`).
`lookupNode(id)` on a hierarchy resolves local nodes first, then the shared graph
(`localNodeMap[id] ?: coreGraph.lookupNode(id)`), so a scenario can introduce hypothetical
nodes (e.g. a not-yet-existing module) that are invisible to every other view and to the graph
itself.

Together: fork to get an isolated view, then `move` existing nodes and/or `createLocalNode`
to model the hypothetical, and read the derived dependencies off the fork.

## 6. `HierarchyScope` — node-centric ergonomics, scoped to one view

Routing every structural call through `hierarchy.…(node)` is explicit but verbose. When a
block of code operates entirely within one chosen hierarchy, `HierarchyScope` restores the
old node-centric *syntax* without reintroducing the old node-centric *coupling*. It exposes
extension members on `HGNode` that delegate to the wrapped hierarchy:

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

`HGModel.scoped { … }` runs such a block for side effects; `HGModel.withScope { … }` returns a
value. The `node.parent`-style accessors are valid only inside the scope, and always mean
"within *this* hierarchy" — there is no ambiguity about which view they refer to.

## 7. Consequences for callers and algorithms

- Code that used to read `node.children` / `node.parent` / `node.accumulated…` now either
  calls the `Hierarchy` method directly or runs inside a `HierarchyScope`.
- Algorithms that compute over structure take the hierarchy explicitly. For example
  `GraphUtils.createDependencyStructureMatrix(nodes, hierarchy)` and
  `GraphUtils.computePairwiseAggregation(nodes, hierarchy)` require the hierarchy, because the
  same node set produces a different DSM under a different arrangement. See
  `hierarchicalgraph-algorithms-spec.md`.
- Anything holding an `HGNode` and wanting structure must also have access to the relevant
  `Hierarchy` (typically via the `HGModel`). A bare node is intentionally not enough.

## 8. Invariants and gotchas

- **A node belongs to the graph; structure belongs to the hierarchy.** Never assume a node
  "knows" its parent — ask a hierarchy.
- **The same node, different rollups.** `accumulatedOutgoing(node)` and aggregated edges can
  differ between two hierarchies over the same graph. That is by design, not a bug.
- **Caches are per-hierarchy and invalidated on mutation.** `addChild`/`move` clear only the
  mutating hierarchy's caches. A fork's caches are independent of its source's.
- **Local nodes are view-private.** A node created via `createLocalNode` is resolvable only
  through the hierarchy that created it (and its forks, which copy the local-node map); it is
  not in `HGGraph.nodes`.
- **`fork()` shares the graph, not the structure.** Mutating a fork's tree is safe; mutating
  shared nodes' intrinsic state (e.g. `kind`) would be visible everywhere — restructure, don't
  rewrite node state, in scenarios.

## 9. End-to-end example

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

The baseline and the scenario coexist over the same nodes; each computes its own derived
dependencies; neither can corrupt the other.
