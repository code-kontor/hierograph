# Hierograph Hierarchical Graph Model — Specification

`io.hierograph.hierarchicalgraph.model` is Hierograph's **analysis model**: a graph
of nodes and weighted dependencies, with one or more trees laid over those nodes. It
is the model every architectural question is answered against. Which modules depend
on which, how strongly two packages are coupled, what a subtree depends on in total,
what would change if a package moved: all of these are questions about this model.

This document explains why the model exists, how it is structured, how it behaves,
and how to work with it.

---

## Contents

1. [Purpose](#1-purpose)
2. [Where it sits](#2-where-it-sits)
3. [The model at a glance](#3-the-model-at-a-glance)
4. [The core graph](#4-the-core-graph)
5. [Hierarchies](#5-hierarchies)
6. [Derived dependencies](#6-derived-dependencies)
7. [Scenarios: forking and restructuring](#7-scenarios-forking-and-restructuring)
8. [Node handles](#8-node-handles)
9. [Caching](#9-caching)
10. [API reference](#10-api-reference)
11. [Working with the model](#11-working-with-the-model)
12. [Invariants and caller obligations](#12-invariants-and-caller-obligations)
13. [Boundaries](#13-boundaries)

---

## 1. Purpose

Architectural analysis looks at a codebase at many levels at once. A dependency
between two classes is also a dependency between their packages, between those
packages' parents, and between the modules that contain them. A useful model
therefore needs two things: the **facts** (which element depends on which, and how
strongly), and a **structure** (how elements group into larger ones) along which
those facts can be rolled up.

The hierarchical graph provides both, and keeps them apart:

- The **core graph** holds the facts: a flat set of nodes and the direct, weighted
  dependencies between them. It has no notion of parent or child.
- A **hierarchy** holds a structure: a tree over the core graph's nodes. All
  navigation, and every dependency that only makes sense in terms of a tree, belongs
  to the hierarchy.

Keeping the two apart has a concrete payoff: **several hierarchies can share one
core graph**. The same nodes and the same dependencies can be arranged as the code
is today and, at the same time, as it might be after a refactoring. Each
arrangement answers questions independently, and no data is copied. That is the
foundation for evaluating a proposed restructuring virtually, before touching any
code.

The model is **generic**. Its nodes have opaque identities and an application-defined
`kind`; its dependencies carry a type string, a weight and an application-defined
attribute bitmap. It knows nothing about Java, class files or build tools. Whatever
can describe itself as nodes, a tree and weighted edges can be analysed with it.

The model has three design goals:

**Facts are intrinsic, structure is a view.** A dependency between two types exists
however they are grouped, so it belongs to the nodes. Everything that depends on
grouping is computed by a hierarchy, and therefore differs between hierarchies.

**Cheap views.** A hierarchy is a pair of maps over node identifiers. Creating an
alternative view (a *fork*) copies those maps and nothing else.

**Lazy, cached derivation.** Rolled-up dependencies are computed on first request and
cached per hierarchy, so repeated analysis of a stable structure is fast, and a
restructured view recomputes only what is asked of it.

---

## 2. Where it sits

```
        GraphStore (structure + type-level refs, JVM-aware)
                          │
                          │  bridge: one node per store node,
                          │  one weighted edge per resolved type dependency
                          ▼
   ┌─────────────────────────────────────────────────────┐
   │  HGModel                                            │
   │   ├── HGGraph      nodes + core dependencies        │
   │   └── Hierarchy    tree, rollups, aggregation       │
   └──────────────────────────┬──────────────────────────┘
          ┌───────────────────┼────────────────────┐
          ▼                   ▼                    ▼
     algorithms          tool surface          serialization
   (DSM, SCC, FAS)    (MCP tools, GraphQL)     (JSON snapshot)
```

**Producers** build the model through its factories. In Hierograph, a bridge builds
it from the GraphStore: one node for each GraphStore node, the GraphStore's tree as
the hierarchy, and one core dependency for each resolved type-to-type dependency,
carrying its weight and flags. When the code changes, a fresh model is built.

**Consumers** read it:

- The **algorithms** module builds dependency structure matrices, finds strongly
  connected components (cycles), and orders nodes by feedback-arc-set heuristics.
- The **tool surface** navigates the tree, resolves node handles, and reports
  accumulated and aggregated dependencies.
- The **serialization** module writes the model to a JSON snapshot and reads it back.

---

## 3. The model at a glance

```
            HGModel ──────────────┐
               │                  │
               ▼                  ▼
          Hierarchy ──────────► HGGraph
     (parentMap, childrenMap,   (nodes by identifier,
      local nodes, caches)       extension registry)
               │                  │
               │ arranges         │ owns
               ▼                  ▼
            HGNode ◄─────────── HGCoreDependency
     (identifier, kind,         (from, to, type,
      node source,               weight, attributes,
      in/out dependencies)       dependency source)
```

| Concept | Owner | Why it lives there |
|---------|-------|--------------------|
| Node identity, `kind`, node source | `HGNode` | intrinsic to the node; the same in every hierarchy |
| Direct (core) dependencies | `HGNode` / `HGCoreDependency` | a dependency exists however nodes are grouped |
| Node set, lookup by identifier, extensions | `HGGraph` | the shared, flat universe of nodes |
| Parent, children, ancestors, root | `Hierarchy` | structure is a view |
| Accumulated dependencies | `Hierarchy` | defined by subtrees, so they depend on the tree |
| Aggregated dependencies | `Hierarchy` | defined by subtrees, so they depend on the tree |
| Traversal, descendants | `Hierarchy` | defined by the tree |
| Local nodes | `Hierarchy` | exist only in one view |
| A (graph, hierarchy) pair | `HGModel` | what most callers hold and pass around |

The consequence that shapes the whole API: **an `HGNode` has no parent and no
children.** Anything that depends on where a node sits is a method of a `Hierarchy`:

```kotlin
node.outgoingCoreDependencies               // intrinsic: on the node
hierarchy.childrenOf(node)                  // structural: on the hierarchy
hierarchy.accumulatedOutgoing(node)         // derived from structure: on the hierarchy
hierarchy.getAggregatedDependency(a, b)     // derived from structure: on the hierarchy
```

---

## 4. The core graph

### 4.1 `HGNode`

A node is an element of the analysed system: in Hierograph, a module, package, type
or member.

| Property | Type | Description |
|----------|------|-------------|
| `identifier` | `Any` | The node's identity, taken from its node source. Unique within the graph. |
| `kind` | `Any?` | Application-defined classifier, for example `java.class` or `java.package`. Mutable. |
| `nodeSource` | `INodeSource` | The object that represents the node in the application (§4.3). |
| `outgoingCoreDependencies` | `List<HGCoreDependency>` | The direct dependencies that start at this node, in creation order. |
| `incomingCoreDependencies` | `List<HGCoreDependency>` | The direct dependencies that end at this node, in creation order. |
| `labels` | `List<String>` | Classification tags, delegated from the node source; empty when the source has none. |
| `getNodeSource(Class<T>)` | `T?` | The node source, cast to `T`, or `null` if it is not a `T`. |

**`identifier`** is what hierarchies store, what lookups resolve, and what the
graph indexes. It comes from the node source, so the application decides what a
node's identity is. In Hierograph it is the node's handle (§8).

**`kind`** lets algorithms and tools filter without knowing the application's types:
`descendantsOf(module, JavaNodeKind.CLASS)` compares kinds by equality and never looks
inside them. It is mutable so a producer can set it after the node is created. Since
nodes are shared between hierarchies, a change to `kind` is visible in every view.

**`outgoingCoreDependencies` / `incomingCoreDependencies`** are maintained by the
factory. Every core dependency appears in exactly one outgoing and one incoming list.
Holding both directions on the node makes "what does this depend on?" and "what
depends on this?" equally cheap.

### 4.2 `HGCoreDependency`

A core dependency is a **direct, weighted fact**: `from` depends on `to`.

| Property | Type | Description |
|----------|------|-------------|
| `from` | `HGNode` | The depending node. |
| `to` | `HGNode` | The node depended upon. |
| `type` | `String` | Application-defined category of the dependency, for example `DEPENDS_ON`. |
| `weight` | `Int` | Strength of the dependency. Defaults to `1`. Mutable. |
| `attributesBitmap` | `Int` | Application-defined flags, packed into a bit set. Defaults to `0`. Mutable. |
| `dependencySource` | `IDependencySource` | The object that represents the dependency in the application (§4.3). |
| `getDependencySource(Class<T>)` | `T?` | The dependency source, cast to `T`, or `null`. |

**`weight`** is what makes coupling measurable rather than binary. In Hierograph it
is the number of individual references between two types. Aggregation sums it.

**`attributesBitmap`** carries qualitative distinctions without the model having to
know them. Hierograph uses it for four flags: extends, implements, annotated-by, and
other uses. The model stores and returns the bits but never interprets them.

**`type`** separates kinds of edges, should an application need more than one. All
edges are equal to the model's own computations; consumers may filter on `type`.

### 4.3 Node and dependency sources

Each node and each core dependency is backed by a **source**: an application object
that supplies its identity and display data, and that the application can retrieve
again with `getNodeSource` / `getDependencySource`. Sources are the extension point
that keeps the model generic.

```kotlin
interface INodeSource {
    val identifier: Any
    var node: HGNode?                       // set by the factory: source ↔ node
    val labels: List<String>                // default: empty
    val name: String                        // default: ""
    val qualifiedName: String               // default: ""
}

interface IDependencySource {
    val identifier: Any
    var dependency: HGCoreDependency?       // set by the factory: source ↔ dependency
}
```

The factory links source and element in both directions. An application can start
from its own object and find the node, or start from the node and find its object.

| Implementation | Use |
|----------------|-----|
| `DefaultNodeSource(identifier, properties)` | a minimal node source with an open string property map |
| `SyntheticNodeSource(identifier, name, qualifiedName)` | a node that exists only for the analysis, such as a virtual package in a scenario; carries its own display names |
| `DefaultDependencySource(identifier, properties)` | a minimal dependency source with an open string property map |

Applications define their own sources to attach domain data. Hierograph's node
source carries the node's name, qualified name and its GraphStore key.

`NodeMetadata` reads display data off any node without knowing its source type:
`getName(node)` and `getQualifiedName(node)` come from the source, and
`getKind(node)` is `node.kind` as a string (`"Unknown"` when it is `null`).

### 4.4 `HGGraph`

The graph is the flat universe of nodes.

| Member | Description |
|--------|-------------|
| `nodes` | every node created in this graph |
| `lookupNode(identifier)` | the node with this identifier, or `null` |
| `registerExtension(Class<T>, extension)` | attaches an object to the graph under its class |
| `registerExtension(key, extension)` | attaches an object to the graph under a string key |
| `getExtension(Class<T>)` | the object registered under the class, or `null` |
| `getExtension(key, Class<T>)` | the object registered under the key, or `null`; fails if it is not a `T` |
| `hasExtension(Class<T>)` | whether an object is registered under the class |

**Extensions** let applications and algorithms attach graph-wide data, such as an
index or a precomputed result, to the graph itself rather than keeping a parallel
map keyed by graph. A class-keyed and a string-keyed extension live in the same
registry, and the class key is the class's fully-qualified name.

---

## 5. Hierarchies

### 5.1 Structure

A hierarchy arranges the core graph's nodes into a tree under a **root node**. The
root is a node of the graph, usually a synthetic one that stands for "the whole
system", and the only node without a parent.

| Method | Returns |
|--------|---------|
| `rootNode` | the tree's root |
| `parentOf(node)` | the node's parent, or `null` for the root and for nodes not in the tree |
| `childrenOf(node)` | the node's children, in insertion order |
| `predecessorsOf(node)` | the node's ancestors, nearest first: parent, grandparent, …, root |
| `isPredecessorOf(ancestor, descendant)` | whether `ancestor` is a proper ancestor of `descendant` |
| `isSuccessorOf(descendant, ancestor)` | the same relation, read the other way |
| `name` | an optional label for the view, such as `"as-is"` or `"proposal"` |

A hierarchy stores structure as two maps over node **identifiers**: child → parent,
and parent → ordered children. Because it stores identifiers rather than node
objects, a fork is a cheap copy of two maps (§7).

A node is "in" a hierarchy when it is reachable from the root. Core-graph nodes that
a hierarchy does not place are simply outside that view: they have no parent there
and appear in none of its traversals.

### 5.2 Traversal and descendants

| Method | Visits | Order | Filtering |
|--------|--------|-------|-----------|
| `traverse(node, action)` | every descendant of `node` | pre-order | — |
| `traverse(node, action, filter)` | descendants that pass `filter` | pre-order | a rejected node's **subtree is pruned** |
| `descendantsOf(node, *kinds)` | every descendant of `node` | pre-order | by `kind`; the **whole subtree is still searched** |
| `descendantsOf(nodes, *kinds)` | descendants of each seed, without duplicates | pre-order per seed, seeds in order | as above |

None of these includes the starting node. Pre-order means parent before children,
and children in their insertion order.

The two filtering styles differ on purpose:

- **`traverse` with a filter prunes.** Use it to stop descending, for example "walk
  the tree but do not enter external modules".
- **`descendantsOf` with kinds selects.** Use it to collect, for example "all
  classes under this module". A package that is not itself selected does not hide
  the classes inside it. An empty `kinds` selects everything.

---

## 6. Derived dependencies

Core dependencies connect individual nodes. Architectural questions are usually
asked about groups: a package, a module, a layer. A hierarchy derives two kinds of
group-level dependencies from the core dependencies, both defined by its subtrees.

The examples in this section use this hierarchy:

```
root
├── billing                       core dependencies (weight)
│   ├── Invoice      ──────────►  Money      (3)
│   └── Ledger       ──────────►  Money      (1)
│                    ──────────►  Invoice    (2)
└── money
    └── Money
```

### 6.1 Accumulated dependencies

The **accumulated** dependencies of a node are its own core dependencies plus
those of every descendant: everything that starts (or ends) anywhere in its
subtree.

| Method | Returns |
|--------|---------|
| `accumulatedOutgoing(node)` | core dependencies whose `from` is in `node`'s subtree |
| `accumulatedIncoming(node)` | core dependencies whose `to` is in `node`'s subtree |

In the example, `accumulatedOutgoing(billing)` is all three edges:
`Invoice → Money`, `Ledger → Money` and `Ledger → Invoice`. The last one is
**internal**: both its ends lie inside `billing`. Accumulation does not filter
internal edges out. A caller who wants only the edges that leave the subtree filters
on the target.

Each core dependency appears once in a node's accumulated list. The list is the
node's own dependencies, then each child's accumulated list, in child order.

### 6.2 Aggregated dependencies

An **aggregated** dependency is a virtual edge between two nodes that summarizes
every core dependency **from somewhere in the first subtree to somewhere in the
second**.

```kotlin
interface AggregatedDependency {
    val from: HGNode
    val to: HGNode
    val coreDependencies: List<HGCoreDependency>   // the edges summarized
    val aggregatedWeight: Int                      // sum of their weights
}
```

| Method | Returns |
|--------|---------|
| `getAggregatedDependency(from, to)` | the aggregated dependency, or `null` when no core dependency connects the two subtrees |
| `getAggregatedDependencies(from, targets)` | the non-null aggregated dependencies from `from` to each of `targets`, in order |
| `getAggregatedDependenciesFrom(to, sources)` | the non-null aggregated dependencies from each of `sources` to `to`, in order |

A core dependency `d` belongs to the aggregated dependency from `A` to `B` when
`d.to` is `B` or a descendant of `B`, **and** `d.from` is `A` or a descendant of `A`.

In the example:

| Query | Core dependencies | Weight |
|-------|-------------------|-------:|
| `getAggregatedDependency(billing, money)` | `Invoice → Money`, `Ledger → Money` | 4 |
| `getAggregatedDependency(Ledger, money)` | `Ledger → Money` | 1 |
| `getAggregatedDependency(money, billing)` | — (`null`) | |

Aggregated dependencies are the cells of a dependency structure matrix: for a chosen
set of nodes, the aggregated dependency of each ordered pair is one cell, and its
weight is the number shown. Because they are computed by a hierarchy, the same
core graph yields different matrices for different hierarchies, and that is how a
proposed restructuring is evaluated.

The definition is literal for overlapping nodes too. The aggregated dependency from
a node to itself is its internal edges, and from a node to one of its own descendants
it is the edges from the node's subtree into the descendant's. Matrices are normally
built over disjoint nodes, where no such overlap arises.

---

## 7. Scenarios: forking and restructuring

A **scenario** is an alternative arrangement of the same system: a package moved to
another module, a new module carved out, a layer split. The model supports scenarios
directly.

### 7.1 Fork

`hierarchy.fork()` (or `model.fork()`) returns a new, independent hierarchy over the
**same** core graph:

| Copied | Shared |
|--------|--------|
| the structure (both maps), so the fork starts identical | the core graph: every node and every core dependency |
| the local nodes (§7.3) | the root node |
| the `name` | |
| — the caches start empty and refill on demand | |

Restructuring a fork leaves the original hierarchy, and every other fork,
unchanged. Changing a node's or a dependency's own state (`kind`, `weight`,
`attributesBitmap`) changes it in every hierarchy, because those objects are shared.
Scenarios restructure; they do not edit facts.

### 7.2 Restructuring

| Method | Effect |
|--------|--------|
| `move(node, newParent)` | detaches `node` from its current parent and appends it to `newParent`'s children, with its whole subtree |
| `addChild(parent, child)` | appends `child` to `parent`'s children; for a node that is not yet in the tree |

Both clear the hierarchy's caches (§9). Use `move` to re-parent a node that is
already placed, and `addChild` only for a node that has no parent in this
hierarchy yet.

Both keep the hierarchy a tree and fail with `IllegalArgumentException`, leaving it
unchanged, when a call would break it:

| Call | Rejected when |
|------|---------------|
| `move(node, newParent)` | `node` is the root; `newParent` is `node` or lies in its subtree |
| `addChild(parent, child)` | `child` already has a parent (use `move`); `child` is the root; `parent` lies in `child`'s subtree |

The subtree check walks `newParent`'s (or `parent`'s) parent chain, so it costs the
depth of the target, not the size of the subtree.

### 7.3 Local nodes

A scenario often needs nodes that do not exist in the code, such as the new module
a package is moved into. **Local nodes** belong to one hierarchy only:

| Method | Description |
|--------|-------------|
| `createLocalNode(kind, sourceSupplier)` | creates a node that is known to this hierarchy (and its later forks), but not to the core graph; fails with `IllegalArgumentException` when a core node or a local node of this hierarchy already has the source's identifier |
| `localNodes` | the hierarchy's local nodes |
| `lookupNode(identifier)` | finds a node among the local nodes first, then in the core graph |

A local node is placed with `addChild` like any other node and works everywhere a
node does in that hierarchy: traversal, accumulation, aggregation. It has no core
dependencies of its own. Its accumulated and aggregated dependencies are those of
the real nodes moved underneath it, which is exactly what makes "what would this new
module depend on?" answerable. `SyntheticNodeSource` is the natural source for a
local node.

Because `lookupNode` searches the local nodes first, a local node with a taken
identifier would hide the other node; `createLocalNode` therefore rejects it. The
check covers the local nodes a fork inherits. Two separate forks of one hierarchy may
each create a local node with the same identifier, since neither sees the other's.

---

## 8. Node handles

Tools that talk to Hierograph refer to nodes by a **handle**: a compact number that
is handed out in one call and passed back in the next. `NodeHandle` derives it:

```kotlin
NodeHandle.of(nodeKey: String): Long
```

The handle is the first 64 bits of the SHA-256 of the node's **stable key**,
truncated to 53 bits. Three properties follow:

- **Stable.** The handle is a pure function of the key. It has no counter, no
  allocation order and no stored state, so the same node gets the same handle across
  rescans, restarts and machines. A rename or a move changes the key, and therefore
  the handle. That is correct, because it is then a different node.
- **JSON-safe.** 2⁵³ − 1 is the largest integer an IEEE-754 double represents
  exactly, and so the largest that survives a JSON or JavaScript `number` unchanged.
  A wider handle could be silently corrupted by a client.
- **Collision-checked.** Collisions in a 2⁵³ space are very rare: well under one in
  10,000 for a graph of a million nodes. They are still possible, so a producer that
  assigns handles to a whole node set checks each one with
  `NodeHandle.assertNoCollision(key, handle, seen)`, which fails at once rather than
  serving an ambiguous handle.

In Hierograph the key is the node's GraphStore key, which already includes its
artifact. Two types with the same name in different artifacts therefore get distinct
handles. The handle becomes the node's `identifier`.

---

## 9. Caching

Derived results are cached per hierarchy, keyed by node identifier:

| Cache | Filled by |
|-------|-----------|
| ancestors | `predecessorsOf`, `isPredecessorOf`, `isSuccessorOf` |
| accumulated outgoing | `accumulatedOutgoing` |
| accumulated incoming | `accumulatedIncoming`, `getAggregatedDependency` |
| aggregated dependencies (including "none") | `getAggregatedDependency` and its list forms |

The caches are filled one entry per request. Accumulated results are computed
recursively, so asking for a module's accumulated dependencies also caches those of
every node beneath it.

**Concurrent reads.** The caches are concurrent maps. An entry is computed outside
the map and then published only if no other thread published one first, so two
threads asking for the same entry at the same time may both compute it, but every
reader sees one complete value, and all values for a key are equal. Entries cannot
be computed inside the map, because the computations recurse into the same cache.

**Invalidation.** `move` and `addChild` on the hierarchy clear all its caches. Nothing
else does. The caches assume the core graph is complete and its weights final before
the first query (§12): a core dependency created afterwards, or a weight changed
afterwards, is not reflected in results already cached. An aggregated dependency's
`aggregatedWeight` is computed when the aggregate is created.

A fork starts with empty caches, so it never sees results computed for a different
structure.

---

## 10. API reference

### 10.1 `HGGraphFactory`

Nodes and core dependencies are created only through the factory, which maintains
the links between nodes, dependencies, sources and the graph.

| Function | Description |
|----------|-------------|
| `createHGGraph()` | creates an empty graph |
| `createNode(graph, sourceSupplier)` | creates a node from the supplied source, links source and node, and registers the node in the graph under the source's identifier |
| `createCoreDependency(from, to, type, sourceSupplier)` | creates a core dependency (weight `1`, attributes `0`), links it with its source, and adds it to `from`'s outgoing and `to`'s incoming lists |
| `removeNode(graph, node)` | removes a node from the graph's index; for pruning, during construction, nodes that were created but never placed in the tree; the node must have no dependencies |

### 10.2 `HierarchyFactory`

| Function | Description |
|----------|-------------|
| `createHierarchy(graph, rootNode)` | creates an empty hierarchy over `graph` with the given root |
| `addChild(hierarchy, parent, child)` | places `child` under `parent`, **without clearing caches**: the fast path for building a hierarchy before it is first queried; rejects the same calls as `Hierarchy.addChild` (§7.2) |

### 10.3 `HGModel`

The pair most callers hold: a core graph and one hierarchy over it.

| Member | Description |
|--------|-------------|
| `coreGraph` | the core graph |
| `hierarchy` | the hierarchy |
| `lookupNode(identifier)` | `hierarchy.lookupNode(identifier)`, so local nodes resolve too |
| `fork()` | a new model over the same core graph with a forked hierarchy |
| `scoped { … }` | runs a block in a `HierarchyScope` of the hierarchy |
| `withScope { … }` | the same, returning the block's result |

### 10.4 `HierarchyScope`

`HierarchyScope` gives node-centric syntax for one hierarchy. Inside a scope, nodes
gain extension properties that delegate to the scope's hierarchy:

| Scope syntax | Delegates to |
|--------------|--------------|
| `node.parent`, `node.children`, `node.hasChildren` | `parentOf`, `childrenOf` |
| `node.predecessors` | `predecessorsOf` |
| `a.isPredecessorOf(b)`, `a.isSuccessorOf(b)` | the same methods |
| `node.accumulatedOutgoingCoreDependencies`, `node.accumulatedIncomingCoreDependencies` | `accumulatedOutgoing`, `accumulatedIncoming` |
| `a.outgoingTo(b)`, `a.outgoingTo(list)` | `getAggregatedDependency(a, b)`, `getAggregatedDependencies(a, list)` |
| `b.incomingFrom(a)`, `b.incomingFrom(list)` | `getAggregatedDependency(a, b)`, `getAggregatedDependenciesFrom(b, list)` |
| `node.traverse { … }` | `traverse` |
| `node.descendants(*kinds)`, `nodes.descendants(*kinds)` | `descendantsOf` |

The scope makes the view explicit: the same expression `node.children` means
different things in a scope over the original and in a scope over a fork. The class
is `open`, so analyses can subclass it to add vocabulary of their own.

---

## 11. Working with the model

### 11.1 Build

Build in a fixed order: **graph → nodes → hierarchy → dependencies**. Finish all of
it before the first query.

```kotlin
val graph = HGGraphFactory.createHGGraph()
fun node(id: String, kind: Any) =
    HGGraphFactory.createNode(graph) { SyntheticNodeSource(id, id) }.also { it.kind = kind }

val root    = node("root", "root")
val billing = node("billing", "package")
val money   = node("money", "package")
val invoice = node("Invoice", "class")
val ledger  = node("Ledger", "class")
val amount  = node("Money", "class")

val hierarchy = HierarchyFactory.createHierarchy(graph, root)
HierarchyFactory.addChild(hierarchy, root, billing)
HierarchyFactory.addChild(hierarchy, root, money)
HierarchyFactory.addChild(hierarchy, billing, invoice)
HierarchyFactory.addChild(hierarchy, billing, ledger)
HierarchyFactory.addChild(hierarchy, money, amount)

fun dep(from: HGNode, to: HGNode, weight: Int) =
    HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON") { DefaultDependencySource("${from.identifier}->${to.identifier}") }
        .also { it.weight = weight }

dep(invoice, amount, 3)
dep(ledger, amount, 1)
dep(ledger, invoice, 2)

val model = HGModel(graph, hierarchy)
```

### 11.2 Query

```kotlin
model.withScope {
    billing.children                              // [Invoice, Ledger]
    amount.predecessors                           // [money, root]
    billing.outgoingTo(money)?.aggregatedWeight   // 4
    money.outgoingTo(billing)                     // null
    root.descendants("class")                     // [Invoice, Ledger, Money]
}
```

### 11.3 Evaluate a scenario

Move `Ledger` into a new module and ask how the coupling changes, without touching
the original model:

```kotlin
val proposal = model.fork().also { it.hierarchy.name = "proposal" }
val accounting = proposal.hierarchy.createLocalNode("package") { SyntheticNodeSource("accounting", "accounting") }
proposal.hierarchy.addChild(proposal.hierarchy.rootNode, accounting)
proposal.hierarchy.move(ledger, accounting)

proposal.withScope {
    accounting.outgoingTo(billing)?.aggregatedWeight   // 2: Ledger → Invoice
    accounting.outgoingTo(money)?.aggregatedWeight     // 1: Ledger → Money
    billing.outgoingTo(money)?.aggregatedWeight        // 3: Invoice → Money only
}
model.withScope { billing.outgoingTo(money)?.aggregatedWeight }   // still 4
```

### 11.4 Concurrency

**Reads are thread-safe; changes are not.**

- **Reads.** Any number of threads may query the same hierarchy at the same time —
  structure, traversal, accumulation, aggregation — also while its caches are still
  empty (§9).
- **Changes.** `move`, `addChild` and `createLocalNode` change the hierarchy's maps
  and clear its caches. A change while another thread reads the hierarchy, or two
  changes at the same time, is never safe.
- **Build, then publish.** Build or restructure a hierarchy on one thread, then hand
  it to readers through a safe publication — a volatile field, an atomic reference,
  a concurrent collection — and do not change it afterwards. A scenario that needs
  changes is a fork, built the same way.

The core graph follows the same rule: it is built before the first query and is
read-only afterwards (§12).

---

## 12. Invariants and caller obligations

### 12.1 Invariants

The factories and the hierarchy methods maintain these at all times. Structural
invariants are stated for a hierarchy `H`.

1. **Source links.** `node.nodeSource.node == node`, and
   `dep.dependencySource.dependency == dep`.
2. **Identity.** `node.identifier == node.nodeSource.identifier`.
3. **Graph membership.** A node created by `HGGraphFactory.createNode` is found by
   `graph.lookupNode(node.identifier)`. A local node is found by `H.lookupNode`, not
   by the graph.
4. **Dependency membership.** Every core dependency `d` is in
   `d.from.outgoingCoreDependencies` and in `d.to.incomingCoreDependencies`.
5. **Root.** `H.parentOf(H.rootNode) == null`.
6. **Accumulation.** `node.outgoingCoreDependencies ⊆ H.accumulatedOutgoing(node)`, and
   `H.accumulatedOutgoing(child) ⊆ H.accumulatedOutgoing(node)` for every child. The
   same holds for incoming.
7. **Aggregated weight.** For every aggregate `a`,
   `a.aggregatedWeight == a.coreDependencies.sumOf { it.weight }` at the time it was
   created.
8. **View independence.** Restructuring `H` never changes the structure or the derived
   results of any other hierarchy.
9. **Tree.** Every node of `H` except the root has exactly one parent, appears once
   among its parent's children, and is not its own ancestor. `move`, `addChild`,
   `HierarchyFactory.addChild` and `createLocalNode` reject every call that would break
   this, or make `H.lookupNode` ambiguous (§7.2, §7.3).

### 12.2 Caller obligations

The model checks what keeps a hierarchy a tree (§12.1, invariant 9) and otherwise
favours speed over defensive checks. These rules are the caller's responsibility;
breaking them leaves the hierarchy inconsistent:

| Rule | Why |
|------|-----|
| Give every core node a **unique identifier**. | The graph indexes nodes by identifier; a second node with the same identifier replaces the first in the index. (Local nodes are checked, §7.3.) |
| **Finish the core graph** (dependencies and weights) before the first query. | Caches are cleared only by restructuring (§9). |
| Use `HierarchyFactory.addChild` **only before the first query**; afterwards use `Hierarchy.addChild`. | The factory variant does not clear caches. |
| Remove only nodes **without dependencies**. | `removeNode` drops the node from the index, but dependency lists still point at it. |
| Treat **shared state as facts**: change `kind`, `weight` or attributes only while building. | Nodes and dependencies are shared by every fork. |

---

## 13. Boundaries

What the model deliberately does **not** do, and where it happens instead:

| Not in the model | Where it lives |
|------------------|----------------|
| Domain knowledge: languages, class files, artifacts, name resolution | the producer; in Hierograph, the GraphStore and its bridge |
| Incremental update of the core graph | the producer, which rebuilds the model from an updated source |
| Dependency matrices, cycle detection, feedback-arc ordering | the algorithms module, built on this model's hierarchies and aggregates |
| Persistence | the serialization module |
| Interpretation of `kind`, `type` and `attributesBitmap` | the application that sets them |
| Change notification | none: restructuring clears caches, and everything else is built before use |
| Thread safety of changes | the caller; reads are thread-safe (§11.4) |

Keeping the model to nodes, trees and weighted edges is what lets one model serve
every view of the system, the real one and any number of proposed ones, at the cost
of two small maps per view.
