# Hierograph GraphQL API — Specification

`io.hierograph.server.graphql` is Hierograph's **client-driven query surface**: a
GraphQL schema over the served hierarchical graph, published at `POST /graphql` on
the Hierograph server. Where the MCP tools answer fixed questions in fixed shapes for
language models, the GraphQL API lets a program (a UI, a script, a notebook) walk
the tree, follow dependencies and request dependency structure matrices in exactly
the shape it needs, in one round trip.

This document explains why the API exists, how it maps the hierarchical graph onto
GraphQL types, how each field behaves, and how to query it.

---

## Contents

1. [Purpose](#1-purpose)
2. [Where it sits](#2-where-it-sits)
3. [The schema at a glance](#3-the-schema-at-a-glance)
4. [Identity](#4-identity)
5. [Matching nodes: subtrees and endpoints](#5-matching-nodes-subtrees-and-endpoints)
6. [Derived node sets](#6-derived-node-sets)
7. [Node properties](#7-node-properties)
8. [Pagination](#8-pagination)
9. [Errors](#9-errors)
10. [Consistency and cost](#10-consistency-and-cost)
11. [API reference](#11-api-reference)
    1. [`Query` and `HierarchicalGraph`](#111-query-and-hierarchicalgraph)
    2. [`Node`](#112-node)
    3. [`NodeSet`](#113-nodeset)
    4. [`Dependency`](#114-dependency)
    5. [`DependencySet`](#115-dependencyset)
    6. [`FilteredDependencies`](#116-filtereddependencies)
    7. [`DependencyPage` and `PageInfo`](#117-dependencypage-and-pageinfo)
    8. [`OrderedAdjacencyMatrix`](#118-orderedadjacencymatrix)
    9. [Enums, inputs and `MapEntry`](#119-enums-inputs-and-mapentry)
12. [Working with the API](#12-working-with-the-api)
13. [Guarantees](#13-guarantees)
14. [Boundaries](#14-boundaries)

---

## 1. Purpose

The hierarchical graph answers architectural questions at every level of a codebase:
what a module contains, how strongly two packages are coupled, where the cycles among
a set of modules are. The MCP tools expose these answers to language models, as
fourteen tools with fixed parameters, fixed result shapes and token-conscious
defaults. A program that renders a dependency structure matrix, drills from a module
into its packages, or lists the type-level dependencies behind one matrix cell needs
something different: it wants to choose the fields, combine several steps in one
request, and get structured data back rather than text.

The GraphQL API is that surface. It is a thin mapping of the hierarchical graph
model onto GraphQL types. Every resolver reads the graph the server serves and
computes its answer from the model and the algorithms module; the API holds no state
of its own.

It has four design goals:

**One model, two surfaces.** The GraphQL API reads the same `Hierarchy` the MCP tools
read, through the same `HierarchicalGraphService`. A node has the same handle in both,
a name search ranks identically in both (`findNodes` and `find_node` share one
implementation), and a DSM is the same DSM. A program and an assistant looking at the
same server see the same codebase.

**The client chooses the shape.** A field is computed only when a query selects it.
Expensive data (a DSM, the per-node properties read from Parquet) costs nothing in a
query that does not ask for it, and a query that asks for it on many nodes pays once:
`Node.properties` is resolved in a single batch per response (§7).

**Ids round-trip.** Every node is addressed by its handle and every dependency by an
id composed of handles (§4). Any id a response contains can be passed back as an
argument in the next query, so a client can navigate step by step without keeping
anything but ids.

**Navigation by composition.** The schema offers a small set of building blocks
(nodes, node sets, dependency sets, matrices) that nest into one another. A query
can start at the root, select a node set, take its DSM, and resolve the dependency
set behind one cell, all in one request.

### The GraphQL API and the MCP tools

Both surfaces answer questions about the same graph, so it is fair to ask why both
exist.

| | GraphQL API | MCP tools |
|---|---|---|
| Client | programs: UIs, scripts, notebooks | language models |
| Result shape | chosen by the query | fixed per tool |
| Steps per request | many, nested | one |
| Node addressing | handles only (plus `findNodes` by name) | handles, qualified names, or partial names |
| Pagination | page number and size, on dependency lists | opaque cursors bound to the graph's fingerprint |
| Member-level detail (methods, fields, call sites) | not served | the detail tools |
| Graph administration | not served | `reload_graph` |

Both are needed because:

1. **Their clients read differently.** A language model needs compact, labelled
   answers with sensible defaults and hints for the next step; a program needs
   complete, typed data it can render or compute with.
2. **Composition belongs to the client.** A UI that shows a matrix and its drill-down
   would need several tool calls and would receive text it must parse. One GraphQL
   query returns exactly the data the view binds to.
3. **The tools carry policy the API does not need.** Token budgets, cursor
   invalidation and "rebuilding, retry" states exist for an assistant's conversation.
   A GraphQL client asks again when it wants fresh data.

---

## 2. Where it sits

```
   ┌──────────────────────────────────────────────────────────┐
   │  HierarchicalGraphService      (io.hierograph.server.    │
   │   current snapshot: model,       graphservice)           │
   │   search index, Parquet dir                              │
   └───────┬────────────────────┬────────────────────┬────────┘
           │ model.hierarchy    │ searchIndex        │ parquet dir
           ▼                    ▼                    ▼
   HierarchicalGraphProvider  NodeSearch    ParquetNodePropertiesProvider
           │                    │            (NodePropertiesProvider)
           └──────────┬─────────┴────────────────────┘
                      ▼
   ┌──────────────────────────────────────────────────────────┐
   │  io.hierograph.server.graphql                            │
   │   schema (graphql/*.graphqls) + @Controller resolvers    │
   │   uses: Hierarchy (model), GraphUtils (algorithms)       │
   └───────────────────────────┬──────────────────────────────┘
                               │  POST /graphql  (Spring GraphQL)
                               ▼
              GraphQL clients · GraphiQL (/graphiql/index.html)
```

**Producers.** The API produces nothing; it reads. `GraphQlConfiguration` defines the
one bean the module contributes, a `HierarchicalGraphProvider` that returns
`graphService.model.hierarchy`, the hierarchy of the snapshot the service
serves at the moment of the call. `NodeSearch` and `NodePropertiesProvider` come from the graph-service module,
so the GraphQL API and the MCP tools share them. The hierarchy itself is defined by
the [hierarchical graph model specification](../../../hierograph-hierarchicalgraph/io.hierograph.hierarchicalgraph.model/spec/hierarchicalgraph-model.md),
and the service by the
[hierarchical graph service specification](../../io.hierograph.server.graphservice/spec/hierarchical-graph-service.md).

**Consumers.**

- **GraphQL clients** post queries to `/graphql`. Any standard client works; the
  endpoint speaks the GraphQL-over-HTTP JSON protocol of Spring GraphQL.
- **GraphiQL** is a browser IDE served as a static page by the Hierograph server at
  `/graphiql/index.html`. It points at `/graphql` on the same host and adds the
  Explorer plugin for building queries by clicking through the schema. Spring's
  built-in GraphiQL endpoint is switched off (`spring.graphql.graphiql.enabled=false`)
  in favour of this page.

The server application picks the module up by component scan of
`io.hierograph.server.graphql`; Spring GraphQL loads the schema files from
`classpath:graphql/`. The module has no configuration properties of its own.

---

## 3. The schema at a glance

The schema has one query field, `hierarchicalGraph`. Everything else is reached from
it.

```
Query.hierarchicalGraph ──► HierarchicalGraph
                              │ rootNode, node(id), findNodes(name)
                              ├──────────────► Node ◄──────────────┐
                              │                 │ children,         │ sourceNode,
                              │ nodes(ids)      │ referencedNodes…  │ targetNode
                              ├──────────────► NodeSet ──► OrderedAdjacencyMatrix
                              │                                     │
                              │ dependencySetForAggregatedDependency│
                              ├──────────────► DependencySet ──► Dependency
                              │                 │ filteredDependencies
                              │                 ▼
                              │               FilteredDependencies ──► Dependency
                              │                 │ dependencyPage
                              │                 ▼
                              └ dependency(id)  DependencyPage ──► PageInfo
```

Each GraphQL type is backed by one object on the server:

| GraphQL type | Backing object | What it stands for |
|---|---|---|
| `HierarchicalGraph` | `Hierarchy` | the served tree over the core graph |
| `Node` | `HGNode` | a module, package, type or member |
| `NodeSet` | `NodeSetModel(nodeList)` | an ordered list of nodes |
| `Dependency` | `HGCoreDependency` | one direct, weighted type-to-type dependency |
| `DependencySet` | `DependencySetModel(dependencyList)` | an ordered list of core dependencies |
| `FilteredDependencies` | `FilteredDependenciesModel(dependencyList)` | a `DependencySet` narrowed by a node selection |
| `OrderedAdjacencyMatrix` | `OrderedAdjacencyMatrixModel` | a dependency structure matrix |
| `DependencyPage` / `PageInfo` | `DependencyPageModel` / `PageInfoModel` | one page of a dependency list |

A **core dependency** is a direct fact between two nodes. In Hierograph the graph
holds one core dependency per ordered pair of types that reference each other, with
type `DEPENDS_ON` and a weight equal to the number of individual references. Modules,
packages and members are never endpoints of a core dependency; a dependency between
two packages exists only as the core dependencies between their types.

---

## 4. Identity

**Node ids.** A node's `id` is its **handle**: a number below 2⁵³ derived from the
node's stable key, the same handle the MCP tools return. GraphQL's `ID` type carries
it as a decimal string (`"2297145530186402"`). Every argument that takes a node id
parses it with `toLong()` and looks it up in the hierarchy. Handles are stable across
rescans and restarts, so an id stored by a client stays valid as long as the node
exists.

**Dependency ids.** A core dependency has no handle of its own. Its `id` is composed
of its endpoints and its type:

```
<source handle>_<target handle>_<type>
```

| Dependency | Id |
|---|---|
| `Invoice → Money` | `2297145530186402_4719020663158837_DEPENDS_ON` |
| `Ledger → Invoice` | `6043318829475510_2297145530186402_DEPENDS_ON` |

The id is unique because the graph holds at most one core dependency per source,
target and type. It is stable for the same reason handles are.

**Hierarchy identifiers.** `HierarchicalGraph.identifier` is the root node's handle.
`globalIdentifier` is the hierarchy's `name` when it has one, and the root handle
otherwise. The served hierarchy carries no name, so both return the root handle.

The examples in this document use one small codebase:

```
root                                   handle
└── billing-core          (module)     3820417756210044
    ├── com.example.billing (package)  1188503342907715
    │   ├── Invoice                    2297145530186402
    │   └── Ledger                     6043318829475510
    └── com.example.money  (package)   5501274918330269
        └── Money                      4719020663158837

core dependencies (weight):  Invoice → Money   (3)
                             Ledger  → Money   (1)
                             Ledger  → Invoice (2)
```

---

## 5. Matching nodes: subtrees and endpoints

Fields that take node ids relate them to dependencies in one of two ways. Which one a
field uses decides what ids are useful to pass.

**Subtree matching** treats a node as everything below it. A core dependency matches
a node when its endpoint is the node or one of its descendants. The **accumulated**
dependencies of a node (its subtree's outgoing or incoming core dependencies) and the
**aggregated** dependency between two nodes (every core dependency from the first
subtree into the second) are both subtree-matched; the model specification defines
them. Package and module ids are meaningful here.

**Endpoint matching** compares a dependency's `sourceNode` or `targetNode` with the
given ids directly. Since only types are endpoints (§3), only type ids can match. A
package id matches nothing.

| Field | Which side | Matching |
|---|---|---|
| `dependencySetForAggregatedDependency(sourceNodeId, targetNodeId)` | both | subtree on both sides |
| `Node.dependenciesTo(targetNodes)` / `dependenciesFrom(sourceNodes)` | the node: subtree; the ids: endpoint | mixed |
| `Node.childrenFilteredByReferencedNodes` / `…ByReferencingNodes` | each child: subtree; the ids: endpoint | mixed |
| `Node` / `NodeSet` `filterReferencedNodes` / `filterReferencingNodes` | the receiver: subtree; the ids: endpoint after expansion | mixed, see below |
| `DependencySet.filteredChildren` / `filteredChildrenIds` | endpoint | endpoint |
| `DependencySet.filteredDependencies` | endpoint | endpoint |

With the example codebase:

| Query | Result |
|---|---|
| `dependencySetForAggregatedDependency(billing, money)` | `Invoice → Money`, `Ledger → Money` |
| `node(billing) { dependenciesTo(targetNodes: [Money]) }` | `Invoice → Money`, `Ledger → Money` |
| `node(billing) { dependenciesTo(targetNodes: [money]) }` | empty: the package is no endpoint |

**`NodesToConsider`** closes this gap for the `filter*` fields. Before matching, it
expands each given id:

| Value | Expands a node to |
|---|---|
| `SELF` | the node alone |
| `SELF_AND_CHILDREN` | the node and its direct children |
| `SELF_AND_SUCCESSORS` | the node and all its descendants |

`filterReferencedNodes(nodeIds: [money], nodesToConsider: SELF_AND_SUCCESSORS)` on
`billing` therefore finds `Money`, which `SELF` would not.

---

## 6. Derived node sets

Several fields compute a set of nodes from dependencies: `referencedNodes`,
`referencingNodes`, `filterReferencedNodes`, `filterReferencingNodes` on `Node` and
`NodeSet`, and `nodes` / `referencedNodes` on `FilteredDependencies`. They share
three rules.

**Results are endpoints.** A referenced node is the `targetNode` of a core dependency,
a referencing node its `sourceNode`. Results are therefore types. Accumulation keeps
internal dependencies (§5), so the referenced nodes of a package include its own
types that other types in the package use: `billing.referencedNodes` is `Money` and
`Invoice`.

**Predecessors are optional.** With `includePredecessors: true` (or
`includePredecessorsInResult` / `includedPredecessors`, depending on the field) every
result node is followed by its ancestors, nearest first, up to and including the root.
This turns a set of types into the set of every tree node a UI needs to expand to show
them. `billing.referencedNodes(includePredecessors: true)` is `Money`,
`com.example.money`, `billing-core`, root, `Invoice`, `com.example.billing`.

**No duplicates, first-seen order.** A node appears once, at the position where it
was first reached: receivers in their order, each receiver's accumulated dependencies
in the model's accumulation order, and each result node directly followed by its
not-yet-listed ancestors.

The boolean arguments on `Node` and `NodeSet` are nullable and default to `false`.
The two arguments on `FilteredDependencies` (`nodeType`, `includedPredecessors`) are
required.

---

## 7. Node properties

`Node.properties` returns key–value facts about a node as a list of `MapEntry`. The
graph-service module supplies them through a `NodePropertiesProvider`; the Hierograph
server's provider reads them from the in-memory hierarchy and, for types, from the
Parquet snapshot.

| Node | Keys |
|---|---|
| every node with a graph-store source | `fqn`, `name`, `kind` |
| module | `artifact` (or `external: "true"` for the external module), `packageCount`, `typeCount` |
| package | `artifact`, `subPackageCount`, `directTypeCount`, `descendantTypeCount` |
| type | `artifact`, `sourceFile`, `classHash`, `visibility`, `modifiers`, `abstract`, `final`, `static`, `methodCount`, `constructorCount`, `fieldCount` |
| external type | `external: "true"` |
| root | none |

All values are strings; a value is `null` only where the underlying fact is (a node
without a kind has `kind: null`). A type's `sourceFile`, `classHash` and modifier keys
appear only when the snapshot row carries them.

**Why batched.** Type properties come from Parquet, and reading Parquet once per node
would make a query over a hundred types a hundred file scans. The resolver is a
`@BatchMapping`: Spring GraphQL collects every `Node` in the response whose
`properties` is selected and resolves them in **one** provider call, which reads each
Parquet table once, filtered to the requested types. A query that does not select
`properties` triggers no read at all.

A node the provider does not answer gets an empty list, because the field is
non-null. Without a provider bean, or without a loaded graph, every node gets an
empty list.

---

## 8. Pagination

`DependencySet` and `FilteredDependencies` can return their dependencies whole
(`dependencies`) or one page at a time (`dependencyPage(pageNumber, pageSize)`).
Pages are **1-based** slices of the list in its fixed order (§11.5):

```
5 dependencies, pageSize 2
page 1 → d0 d1      page 2 → d2 d3      page 3 → d4      page 4 → (empty)
pageInfo: maxPages 3, totalCount 5
```

| `PageInfo` field | Value |
|---|---|
| `pageNumber` | the requested page |
| `pageSize` | the requested size |
| `totalCount` | the number of dependencies in the whole set |
| `maxPages` | `ceil(totalCount / pageSize)`; `0` for an empty set |

A page past the end is empty, not an error, so a client can stop at the first empty
page or at `maxPages`. A `pageNumber` or `pageSize` below 1 is a `BAD_REQUEST` (§9).

The pages of one set are consistent with each other only while the served graph does
not change. Unlike the MCP tools' cursors, page numbers carry no fingerprint; a
client that pages across a reload can see a dependency twice or miss one.

---

## 9. Errors

GraphQL reports errors in the response's `errors` array, alongside whatever data
could be resolved. The API distinguishes two classes.

**Invalid arguments** are `BAD_REQUEST` errors with a message that names the problem.
A resolver throws `InvalidArgumentException`, and `InvalidArgumentExceptionResolver`
maps it to a GraphQL error of type `BAD_REQUEST` with the exception's message. Without
this mapping the client would see an opaque `INTERNAL_ERROR` for its own mistake.

**Anything else** is reported by Spring GraphQL's default handling as an
`INTERNAL_ERROR`, without the exception's message.

| Situation | Effect |
|---|---|
| `findNodes` with a `limit` outside 1..200 | `BAD_REQUEST`: `limit must be between 1 and 200, was …` |
| `findNodes` with a blank `name` | `BAD_REQUEST`: `name must not be blank` |
| `findNodes` with an unknown kind | `BAD_REQUEST` naming the unknown kinds and listing every valid kind and group alias |
| `findNodes` without a `NodeSearch` bean | `BAD_REQUEST`: `findNodes is not available: no search index is configured` |
| `dependencyPage` with `pageNumber` < 1 or `pageSize` < 1 | `BAD_REQUEST`: `pageNumber must be >= 1 (pages are 1-based), was …` / `pageSize must be >= 1, was …` |
| A node id that is not a number | `INTERNAL_ERROR`; the field resolves to `null`, or, for a non-null field such as `nodes(ids)`, its nearest nullable parent does |
| A node id that is a number but names no node | no error; see the per-field behaviour in §11 |
| No graph loaded yet | `INTERNAL_ERROR`; `hierarchicalGraph` is `null` |

Unknown ids are not errors because a client that holds ids across a rebuild can
legitimately ask for a node that has since disappeared. Single lookups return `null`,
list arguments drop the unknown ids, and filters ignore them.

---

## 10. Consistency and cost

**No snapshot pinning.** `hierarchicalGraph` resolves the current hierarchy once and
hands it to the `HierarchicalGraph` fields. Fields on `Node`, `NodeSet`,
`DependencySet` and `FilteredDependencies` call `HierarchicalGraphProvider.hierarchy()`
themselves, and `findNodes` reads the service's current search index. A graph reload
that completes while a query runs can therefore make the later fields of that query
read the rebuilt hierarchy. Each field is computed from one hierarchy; a whole query is
not. Clients that need a consistent picture across a reload issue the query again.

**Cost per field.** Fields that start at a node cost in proportion to its subtree;
fields that start at the whole graph cost in proportion to the graph.

| Field | Cost |
|---|---|
| `node`, `nodes`, `rootNode`, `parent`, `children`, `hasChildren` | lookups: constant per id |
| `referencedNodes`, `dependenciesTo`, `filter*`, … | the receiver's accumulated dependencies, cached per hierarchy |
| `dependencySetForAggregatedDependency` | the aggregated dependency, cached per hierarchy |
| `orderedAdjacencyMatrix` | builds a DSM over the set: SCCs, ordering and a weight matrix |
| `findNodes` | one pass over the search index |
| `dependency(id)`, `dependencies(ids)` | **a walk of the whole tree** collecting every core dependency, per call |
| `properties` | one batched Parquet read per response (§7) |

`dependency` and `dependencies` exist for clients that stored dependency ids; a
client that knows the endpoints reaches the same dependencies far more cheaply with
`dependencySetForAggregatedDependency` or `Node.dependenciesTo`.

---

## 11. API reference

The schema lives in two files under `src/main/resources/graphql/`:
`hierograph.graphqls` declares `Query` and the empty `HierarchicalGraph` type, and
`hierograph-hierarchicalgraph.graphqls` extends `HierarchicalGraph` and declares every
other type. One `@Controller` per GraphQL type holds its resolvers.

### 11.1 `Query` and `HierarchicalGraph`

```graphql
type Query {
    hierarchicalGraph: HierarchicalGraph
}
```

`hierarchicalGraph` returns the served hierarchy (`QueryController`). Its fields
(`HierarchicalGraphController`):

| Field | Returns | Description |
|---|---|---|
| `identifier` | `ID!` | The root node's handle. |
| `globalIdentifier` | `ID!` | The hierarchy's name, or the root handle when it has none (§4). |
| `rootNode` | `Node!` | The root of the tree. Its children are the modules. |
| `node(id)` | `Node` | The node with this handle, or `null`. |
| `nodes(ids)` | `NodeSet!` | The nodes with these handles, in argument order; unknown ids are dropped, duplicates kept. |
| `findNodes(name, kinds, limit = 50)` | `[Node!]!` | Name search, see below. |
| `dependency(id)` | `Dependency` | The core dependency with this id (§4), or `null`. |
| `dependencies(ids)` | `DependencySet` | The core dependencies whose ids are in `ids`, in tree order (pre-order over the sources); unknown ids are dropped. Never `null`. |
| `dependencySetForAggregatedDependency(sourceNodeId, targetNodeId)` | `DependencySet` | The core dependencies of the aggregated dependency from the first subtree to the second; `null` when either id names no node or no core dependency connects the subtrees. |

**`findNodes`** is the only name-based entry point; every other field takes ids. It
delegates to the graph service's `NodeSearch`, the implementation behind the MCP
`find_node` tool, so both match and rank identically:

- **Matching.** Case-insensitive substring match on the node's simple name and on its
  fully-qualified name.
- **Ranking.** Exact name first, then exact fully-qualified name, then name prefix,
  then substring anywhere. Ties go to the shorter fully-qualified name.
- **`kinds`.** Restricts the result to node kinds (`java.module`, `java.package`,
  `java.class`, `java.interface`, `java.enum`, `java.record`, `java.annotation`,
  `java.method`, `java.field`, `external.type`) and the group aliases `types`,
  `members` and `packages`. Without `kinds` only modules, packages and types are
  searched; methods and fields appear only when requested.
- **`limit`.** 1 to 200, default 50. The result is the best `limit` matches, not a
  page; there is no way to ask for the next ones.

`findNodes(name: "invoice")` returns `Invoice`. `findNodes(name: "billing")` returns
the package `com.example.billing` (exact name) before the module `billing-core`
(name prefix).

### 11.2 `Node`

Resolvers in `NodeController`. The receiver is an `HGNode`; structure comes from the
served hierarchy.

| Field | Returns | Description |
|---|---|---|
| `id` | `ID!` | The handle (§4). |
| `text` | `String!` | The node's qualified name: the type FQN, the package name, the artifact key for a module, `<owner fqn>#<signature>` for a member; empty for the root. |
| `type` | `String!` | The node's kind as a string (`java.class`, `java.package`, `external.type`, …); `Unknown` when the node has no kind, as the root. |
| `parent` | `Node` | The parent; `null` for the root. |
| `predecessors` | `[Node!]!` | The ancestors, nearest first, up to and including the root. |
| `hasChildren` | `Boolean!` | Whether the node has children. Lets a tree view draw an expander without loading them. |
| `children` | `NodeSet!` | The children, in insertion order. |
| `childrenFilteredByReferencedNodes(referencedNodeIds)` | `NodeSet!` | The children whose subtree depends on at least one of the given nodes (endpoint match, §5). |
| `childrenFilteredByReferencingNodes(referencingNodeIds)` | `NodeSet!` | The children whose subtree is depended on by at least one of the given nodes (endpoint match). |
| `properties` | `[MapEntry!]!` | Key–value facts about the node (§7). |
| `dependenciesTo(targetNodes)` | `[Dependency!]!` | The core dependencies from this subtree whose target is one of the given nodes. |
| `dependenciesFrom(sourceNodes)` | `[Dependency!]!` | The core dependencies into this subtree whose source is one of the given nodes. |
| `referencedNodes(includePredecessors)` | `NodeSet!` | The targets of this subtree's outgoing core dependencies (§6). |
| `referencingNodes(includePredecessors)` | `NodeSet!` | The sources of this subtree's incoming core dependencies (§6). |
| `filterReferencedNodes(nodeIds, nodesToConsider, includePredecessorsInResult)` | `NodeSet!` | The referenced nodes that lie in the expanded node set (§5). |
| `filterReferencingNodes(nodeIds, nodesToConsider, includePredecessorsInResult)` | `NodeSet!` | The referencing nodes that lie in the expanded node set. |

**`childrenFilteredBy…`** answers the drill-down question "which of my children
cause this dependency?". A UI that shows `billing → money` can ask
`billing.childrenFilteredByReferencedNodes([Money])` and get `Invoice` and `Ledger`.

**`dependenciesTo` / `dependenciesFrom`** list the dependencies in the order of the
node's accumulated dependencies, so they include internal ones when a target lies in
the node's own subtree.

### 11.3 `NodeSet`

Resolvers in `NodeSetController`. A node set is an ordered list of nodes.

| Field | Returns | Description |
|---|---|---|
| `nodes` | `[Node!]!` | The nodes, in set order. |
| `nodeIds` | `[ID!]!` | Their handles, in the same order. |
| `orderedAdjacencyMatrix` | `OrderedAdjacencyMatrix!` | The DSM over the set (§11.8). |
| `referencedNodes(includePredecessors)` | `NodeSet!` | The union of the members' referenced nodes (§6). |
| `referencingNodes(includePredecessors)` | `NodeSet!` | The union of the members' referencing nodes. |
| `filterReferencedNodes(nodeIds, nodesToConsider, includePredecessorsInResult)` | `NodeSet!` | The union of the members' filtered referenced nodes (§5). |
| `filterReferencingNodes(nodeIds, nodesToConsider, includePredecessorsInResult)` | `NodeSet!` | The union of the members' filtered referencing nodes. |

The set-level fields exist so a client can ask about a selection (the children of a
module, a user's multi-select) in one field rather than one per node, and get the
union without duplicates.

### 11.4 `Dependency`

Resolvers in `DependencyController`. The receiver is an `HGCoreDependency`.

| Field | Returns | Description |
|---|---|---|
| `id` | `ID!` | `<source handle>_<target handle>_<type>` (§4). |
| `sourceNode` | `Node!` | The depending type. |
| `targetNode` | `Node!` | The type depended upon. |
| `type` | `String!` | The dependency's type; `DEPENDS_ON` in Hierograph. |
| `weight` | `Int!` | The number of individual references from source to target. |

The schema does not expose the dependency's attribute bits (extends, implements,
annotated-by, other uses); the MCP dependency tools report them.

### 11.5 `DependencySet`

Resolvers in `DependencySetController`. A dependency set is an ordered list of core
dependencies: the aggregated dependency's order for
`dependencySetForAggregatedDependency`, tree order for `dependencies(ids)`.

| Field | Returns | Description |
|---|---|---|
| `size` | `Int!` | The number of dependencies. |
| `dependencies` | `[Dependency!]!` | All of them, in set order. |
| `dependencyPage(pageNumber, pageSize)` | `DependencyPage` | One page (§8). |
| `filteredChildren(parentNode, parentNodeType)` | `[Node!]!` | The children of `parentNode` that are a source (`SOURCE`) or a target (`TARGET`) of a dependency in the set; empty when `parentNode` names no node. |
| `filteredChildrenIds(parentNode, parentNodeType)` | `[String!]!` | Their handles. |
| `filteredDependencies(nodeSelection)` | `FilteredDependencies!` | The dependencies whose endpoints match the selection, see below. |

**`filteredChildren`** matches endpoints (§5): it returns children that are themselves
a source or a target. Asked of a package whose types are the endpoints, it returns
those types; asked of a module, whose children are packages, it returns nothing.

**`filteredDependencies`** narrows the set to the dependencies between a chosen group
of sources and a chosen group of targets. Each `NodeSelection` contributes its ids to
the source side (`SOURCE`) or the target side (`TARGET`). A dependency is kept when
its source is in the source side **and** its target is in the target side, matching
endpoints (§5); a side that ends up empty does not restrict. Ids that name no node
are dropped before this rule applies, so a selection whose ids are all unknown leaves
its side unrestricted.

```graphql
filteredDependencies(nodeSelection: [
  { selectedNodeIds: ["6043318829475510"], selectedNodesType: SOURCE }
])
# from dependencySetForAggregatedDependency(billing, money): Ledger → Money only
```

### 11.6 `FilteredDependencies`

Resolvers in `FilteredDependenciesController`. The result of
`DependencySet.filteredDependencies`; it has the same list fields, plus node
extraction.

| Field | Returns | Description |
|---|---|---|
| `size` | `Int!` | The number of dependencies. |
| `dependencies` | `[Dependency!]!` | All of them, in set order. |
| `dependencyPage(pageNumber, pageSize)` | `DependencyPage` | One page (§8). |
| `nodes(nodeType, includedPredecessors)` | `[Node!]!` | The distinct sources (`SOURCE`) or targets (`TARGET`) of the dependencies (§6). |
| `nodeIds(nodeType, includedPredecessors)` | `[ID!]!` | Their handles. |
| `referencedNodes(nodeType, includedPredecessors)` | `[Node!]!` | Every node that those sources or targets depend on, across the whole graph (§6). |
| `referencedNodeIds(nodeType, includedPredecessors)` | `[ID!]!` | Their handles. |

**`referencedNodes`** is not limited to the filtered set: it follows *all* outgoing
dependencies of the extracted nodes. It answers "what else do the types in this cell
depend on?".

### 11.7 `DependencyPage` and `PageInfo`

| Type | Field | Returns | Description |
|---|---|---|---|
| `DependencyPage` | `pageInfo` | `PageInfo` | Where the page sits (§8). Always present. |
| | `dependencies` | `[Dependency!]!` | The page's dependencies. |
| `PageInfo` | `pageNumber` | `Int!` | The requested page, 1-based. |
| | `maxPages` | `Int!` | The number of non-empty pages. |
| | `pageSize` | `Int!` | The requested size. |
| | `totalCount` | `Int!` | The size of the whole set. |

### 11.8 `OrderedAdjacencyMatrix`

`NodeSet.orderedAdjacencyMatrix` builds a dependency structure matrix with
`GraphUtils.createDependencyStructureMatrix` over the set's nodes and the served
hierarchy. The
[algorithms specification](../../../hierograph-hierarchicalgraph/io.hierograph.hierarchicalgraph.algorithms/spec/hierarchicalgraph-algorithms.md)
defines the ordering and the cycle detection; this type serialises the result.

| Type | Field | Returns | Description |
|---|---|---|---|
| `OrderedAdjacencyMatrix` | `orderedNodes` | `[Node!]!` | The set in DSM order: dependents first, each cycle a contiguous block. |
| | `cells` | `[Cell!]!` | The non-zero cells, row by row, column by column. |
| | `stronglyConnectedComponents` | `[StronglyConnectedComponent!]!` | The cycles: components of more than one node. Empty for an acyclic set. |
| `Cell` | `row` | `Int!` | Index into `orderedNodes` of the depending node. |
| | `column` | `Int!` | Index into `orderedNodes` of the node depended upon. |
| | `value` | `Int!` | The summed weight of the core dependencies from the row's subtree into the column's. |
| `StronglyConnectedComponent` | `nodes` | `[Node!]!` | The cycle's nodes, in the algorithm's order (the reverse of DSM order). |
| | `nodeIds` | `[ID!]!` | Their handles, in the same order. |
| | `nodePositions` | `[Int!]!` | The cycle's indices into `orderedNodes`, ascending. |

**Sparse cells.** A matrix over *n* nodes has *n*² cells, most of them zero. Sending
only the non-zero ones keeps a 200-package matrix small. A cell on the diagonal
(`row == column`) is the subtree-internal weight of that node.

**Positions, not pairs.** `nodePositions` is sorted and `nodes` is in the algorithm's
order, so `nodes[k]` and `nodePositions[k]` do not belong together. To mark cycle
rows, use the positions; to name the members, use `orderedNodes[position]`.

For `nodes(ids: [billing, money]) { orderedAdjacencyMatrix }`:

```
orderedNodes  billing, money
cells         (0,0) 2    Ledger → Invoice, internal to billing
              (0,1) 4    Invoice → Money 3 + Ledger → Money 1
stronglyConnectedComponents  []
```

### 11.9 Enums, inputs and `MapEntry`

| Name | Kind | Values / fields | Used by |
|---|---|---|---|
| `NodeType` | enum | `SOURCE`, `TARGET` | `filteredChildren`, `NodeSelection`, `FilteredDependencies` node fields |
| `NodesToConsider` | enum | `SELF`, `SELF_AND_CHILDREN`, `SELF_AND_SUCCESSORS` | the `filter*` fields (§5) |
| `NodeSelection` | input | `selectedNodeIds: [ID!]!`, `selectedNodesType: NodeType!` | `filteredDependencies` |
| `MapEntry` | type | `key: String!`, `value: String` | `Node.properties` |

`NodeType` names a side of a dependency, not a node kind: `SOURCE` is the depending
end, `TARGET` the end depended upon.

---

## 12. Working with the API

**Open the explorer.** With the server running, open
`http://localhost:8080/graphiql/index.html`. The Explorer sidebar lists every field
of the schema; the editor validates queries as you type.

**Query from the command line.**

```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ hierarchicalGraph { rootNode { children { nodes { id text type } } } } }"}'
```

**Find a node by name, then navigate by id.** Start with `findNodes`, keep the ids.

```graphql
{
  hierarchicalGraph {
    findNodes(name: "billing", kinds: ["packages"], limit: 5) { id text }
  }
}
```

```graphql
{
  hierarchicalGraph {
    node(id: "1188503342907715") {
      text
      predecessors { id text }
      children { nodes { id text type hasChildren } }
    }
  }
}
```

**Render a DSM and drill into a cell.** Take a node set's matrix; for a cell
`(row, column)`, pass `orderedNodes[row].id` and `orderedNodes[column].id` to
`dependencySetForAggregatedDependency`.

```graphql
{
  hierarchicalGraph {
    nodes(ids: ["1188503342907715", "5501274918330269"]) {
      orderedAdjacencyMatrix {
        orderedNodes { id text }
        cells { row column value }
        stronglyConnectedComponents { nodePositions }
      }
    }
    dependencySetForAggregatedDependency(
      sourceNodeId: "1188503342907715", targetNodeId: "5501274918330269"
    ) {
      size
      dependencyPage(pageNumber: 1, pageSize: 20) {
        pageInfo { maxPages totalCount }
        dependencies { sourceNode { text } targetNode { text } weight }
      }
    }
  }
}
```

**Explain a dependency from the source side.** Which children of `billing` depend on
anything in `com.example.money`?

```graphql
{
  hierarchicalGraph {
    node(id: "1188503342907715") {
      filterReferencedNodes(
        nodeIds: ["5501274918330269"], nodesToConsider: SELF_AND_SUCCESSORS
      ) { nodes { text } }
      childrenFilteredByReferencedNodes(referencedNodeIds: ["4719020663158837"]) {
        nodes { text }
      }
    }
  }
}
```

**Load properties for a list.** Selecting `properties` on many nodes costs one
batched read (§7).

```graphql
{
  hierarchicalGraph {
    node(id: "1188503342907715") {
      children { nodes { text properties { key value } } }
    }
  }
}
```

**Testing a resolver.** Controllers are plain classes. Build a hierarchy with
`HGGraphFactory` and `HierarchyFactory`, wrap it in a `HierarchicalGraphProvider`, and
call the resolver method directly:

```kotlin
val graph = HGGraphFactory.createHGGraph()
val root = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = 1L) }
val hierarchy = HierarchyFactory.createHierarchy(graph, root)

val controller = NodeController(HierarchicalGraphProvider { hierarchy })
val children = controller.children(root)
```

For `findNodes`, seed a `HierarchicalGraphService` with the model and pass a
`NodeSearch` over it to `HierarchicalGraphController`.

---

## 13. Guarantees

1. **Read-only.** No query changes the graph, the hierarchy or any file. The schema
   has no mutations.
2. **Ids round-trip.** Every `id`, `nodeIds` entry and `filteredChildrenIds` entry a
   response contains is accepted by the id arguments of the next query, and resolves
   to the same node or dependency while the node still exists.
3. **Shared semantics.** A node's handle, its kind string and the ranking of a name
   search are the same as in the MCP tools.
4. **Deterministic for a given graph.** The same query against the same served graph
   returns the same lists in the same order.
5. **No duplicates in derived sets.** The node lists of §6 contain each node once.
6. **Non-null where declared.** `Node.properties` is an empty list, never `null`;
   `nodes(ids)` is a node set, never `null`; list fields are empty rather than absent.
7. **Client mistakes are `BAD_REQUEST`.** Every argument the API validates (§9)
   produces a `BAD_REQUEST` with a message, not an `INTERNAL_ERROR`.

---

## 14. Boundaries

| Not in the GraphQL API | Where it lives |
|---|---|
| Building, holding and reloading the graph | `HierarchicalGraphService` (`io.hierograph.server.graphservice`) |
| Name search matching and ranking | `NodeSearch` (graph service) |
| Reading node properties from Parquet | `ParquetNodePropertiesProvider` (graph service) |
| Tree structure, accumulation, aggregation | `Hierarchy` (`io.hierograph.hierarchicalgraph.model`) |
| DSM ordering and cycle detection | `GraphUtils` (`io.hierograph.hierarchicalgraph.algorithms`) |
| Member-level detail (signatures, call sites, field access) | the MCP detail tools (`io.hierograph.mcp.server`) |
| Dependency attribute bits (extends, implements, …) | the MCP dependency tools |
| Graph reload | the MCP `reload_graph` tool |
| HTTP transport, query parsing, batching | Spring GraphQL |
| The GraphiQL page | the server application (`static/graphiql/index.html`) |

Keeping these out leaves the module a mapping layer: it adds no model, no cache and
no I/O of its own, so whatever the graph service serves, the API serves the same.
