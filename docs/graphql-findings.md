# GraphQL API Review — Findings

These findings come from reviewing the GraphQL API in
`hierograph-server/io.hierograph.server.graphql` while writing its specification. Each
one is code behaviour that looks unintended or inconsistent. The specification
`hierograph-server/io.hierograph.server.graphql/spec/hierograph-graphql.md` documents
the behaviour as it is; this document lists what the maintainer should decide on:
change the code, or keep the behaviour as specified.

Paths are relative to
`hierograph-server/io.hierograph.server.graphql/src/main/` unless they start with a
module name or `docs/`.

Severity:

- **High:** wrong results, or a documented feature that does nothing.
- **Medium:** inconsistent or misleading behaviour.
- **Low:** cosmetic issues or edge cases.

---

## Contents

1. [Consistency and errors](#1-consistency-and-errors)
2. [Matching semantics](#2-matching-semantics)
3. [Dependency structure matrix](#3-dependency-structure-matrix)
4. [Cost](#4-cost)
5. [Schema and documentation](#5-schema-and-documentation)
6. [Text corrections made during the review](#6-text-corrections-made-during-the-review)

---

## 1. Consistency and errors

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 1.1 | Medium | One query can mix two graphs. `hierarchicalGraph` resolves the hierarchy once, but the resolvers of `Node`, `NodeSet`, `DependencySet` and `FilteredDependencies` call `provider.hierarchy()` again, and `findNodes` reads the service's live search index. A reload that completes mid-query makes later fields read the rebuilt graph. The MCP tools avoid this with `HierarchicalGraphService.withPinnedSnapshot`; GraphQL requests are not pinned. Options: pin per request (e.g. a `WebGraphQlInterceptor`), or resolve everything from the `Hierarchy` passed down from `hierarchicalGraph`. | `kotlin/.../controller/*Controller.kt`, `kotlin/.../GraphQlConfiguration.kt` |
| 1.2 | Medium | Without a loaded graph, `hierarchicalGraph` fails with an opaque `INTERNAL_ERROR`: `service.model` throws `IllegalStateException("Hierarchical graph has not been loaded yet")`. The field is nullable, so returning `null` (or a `BAD_REQUEST`/`NOT_FOUND` with a message like the MCP tools' no-graph text) would be expected. | `kotlin/.../controller/QueryController.kt:27` |
| 1.3 | Medium | A node id that is not a number gives an `INTERNAL_ERROR`. Every id argument is parsed with `toLong()`, whose `NumberFormatException` is not mapped. For a non-null field such as `nodes(ids)` the `null` propagates to `hierarchicalGraph`, so the whole response is lost. Parsing through a helper that throws `InvalidArgumentException` would yield a `BAD_REQUEST` naming the bad id. | `HierarchicalGraphController.kt:69,73,95–96`, `NodeController.kt:70,83,104,110,196`, `DependencySetController.kt:58,86` |
| 1.4 | Low | Unknown-but-numeric ids are handled three different ways: single lookups return `null`, `nodes(ids)` drops them, `dependenciesTo` and the filters silently ignore them. A client cannot tell a stale id from an empty result. | `HierarchicalGraphController.kt`, `NodeController.kt` |

---

## 2. Matching semantics

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 2.1 | Fixed | Several fields matched ids against the exact endpoints of core dependencies. Only types are endpoints, so a package or module id matched nothing: `Node.dependenciesTo` / `dependenciesFrom`, `childrenFilteredByReferencedNodes` / `…ByReferencingNodes`, `DependencySet.filteredDependencies`. They now match subtrees (`NodeController.isWithin`): a dependency matches a node when its endpoint is the node or one of its descendants, as `dependencySetForAggregatedDependency` does. Type ids match as before. `SubtreeMatchingTest` covers each field. | `NodeController.kt`, `DependencySetController.kt` |
| 2.2 | Fixed | `DependencySet.filteredChildren` kept only children that were *themselves* endpoints, so for a module (children are packages) it always returned nothing. It now returns each child whose subtree contains a source or target (`NodeController.childContaining`), in child order. | `DependencySetController.kt` |
| 2.3 | Medium | `filteredDependencies` drops unknown ids before filtering, and an empty side means "no restriction". A selection whose ids are all unknown therefore returns *every* dependency of that side instead of none. | `DependencySetController.kt:85–96` |
| 2.4 | Low | `referencedNodes` / `referencingNodes` and `filter*` on a package or module include nodes inside the receiver's own subtree, because accumulated dependencies keep internal edges (`billing.referencedNodes` contains `Invoice`). Probably expected to list only nodes outside the receiver. | `NodeController.kt:115–160`, `NodeSetController.kt:65–117` |
| 2.5 | Low | `FilteredDependencies.referencedNodes` follows *all* outgoing dependencies of the extracted nodes, not only those in the filtered set. Fine if intended ("what else do these types use?"), but the name reads as "the nodes these dependencies reference", which `nodes(nodeType: TARGET)` already answers. | `FilteredDependenciesController.kt:66–81` |

---

## 3. Dependency structure matrix

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 3.1 | Medium | `StronglyConnectedComponent.nodes` and `nodePositions` are not index-aligned. `nodes` is in the algorithm's order (the reverse of DSM order), `nodePositions` is sorted ascending, so `nodes[k]` does not sit at `nodePositions[k]`. A client zipping the two lists mislabels cycle members. Sort `nodes` by position, or keep positions in node order. | `NodeSetController.kt:56–59` |

---

## 4. Cost

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 4.1 | Medium | `dependency(id)` and `dependencies(ids)` walk the whole tree and collect every core dependency on each call, then scan for the id. The id already names both endpoint handles, so parsing it and looking at `lookupNode(from).outgoingCoreDependencies` is constant-cost. | `HierarchicalGraphController.kt:78–87,108–120` |

---

## 5. Schema and documentation

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 5.1 | Low | Inconsistent argument names and nullability: `includePredecessors` (nullable, default `false`) on `Node` / `NodeSet`, `includePredecessorsInResult` on the `filter*` fields, `includedPredecessors` (required) on `FilteredDependencies`. | `resources/graphql/hierograph-hierarchicalgraph.graphqls` |
| 5.2 | Low | Nullability does not match the resolvers: `dependencies(ids)` is declared nullable but never returns `null`; `DependencyPage.pageInfo` is nullable but always present; `dependencyPage` is nullable but never `null`. | `hierograph-hierarchicalgraph.graphqls` |
| 5.3 | Low | `DependencySet.filteredChildrenIds` returns `[String!]!` while every other id list is `[ID!]!`. | `hierograph-hierarchicalgraph.graphqls` |
| 5.4 | Low | `globalIdentifier` always equals `identifier`: the served hierarchy has no `name`. Either give the served hierarchy a name or drop the field. | `HierarchicalGraphController.kt:61–63` |
| 5.5 | Low | Most schema fields carry an empty `#` comment or none, so GraphiQL's docs pane shows nothing for them. Only `findNodes` has a real description. The schema could use `"""` descriptions taken from the spec's reference tables. | `hierograph-hierarchicalgraph.graphqls` |
| 5.6 | Low | `docs/graphql.md` does not mention `findNodes`. | `docs/graphql.md` |

---

## 6. Text corrections made during the review

These changes affect only KDoc and schema comments. No logic changed.

- **Schema comments.** `dependency(id)` and `dependencies(ids)` were commented "Returns the node with the given id"; they now say they return dependencies, and name the id format.
- **History.** "the unit that now owns" in the `HierarchicalGraphController` KDoc lost its "now".
- **Retired technology.** The `NodePropertiesProvider` KDoc no longer mentions a Neo4j `GraphDbNodeSource`; the `ParquetNodePropertiesProvider` KDoc no longer mentions a `jqa` mode and states instead that it answers nothing without a loaded graph (both in `io.hierograph.server.graphservice`).
