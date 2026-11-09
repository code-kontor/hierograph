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
| 1.1 | Fixed | One query could mix two graphs: resolvers below `hierarchicalGraph` re-read the service, and `findNodes` read the live search index. `GraphSnapshotPinning` captures the live snapshot into the request's GraphQL context (`WebGraphQlInterceptor`) and pins it around every non-trivial data fetcher (`Instrumentation`) and the properties batch, through the new `HierarchicalGraphService.withPinnedSnapshot(snapshot, block)` / `liveSnapshot()`. A response is computed from the graph that was live when the request arrived. | `GraphSnapshotPinning.kt`, `controller/NodeController.kt`, `io.hierograph.server.graphservice/.../HierarchicalGraphService.kt` |
| 1.2 | Fixed | Without a loaded graph, `hierarchicalGraph` failed with an opaque `INTERNAL_ERROR`. The provider now throws `GraphUnavailableException`, which `GraphUnavailableExceptionResolver` reports with the classification `GRAPH_UNAVAILABLE` and a message saying how to make a graph available; `hierarchicalGraph` is `null`. | `GraphQlConfiguration.kt`, `controller/GraphUnavailableException.kt` |
| 1.3 | Fixed | A node id that was not a number gave an `INTERNAL_ERROR`. Every id argument is parsed by `NodeController.nodeId`, which throws `InvalidArgumentException`: a `BAD_REQUEST` naming the id. Malformed dependency ids are a `BAD_REQUEST` too (4.1). | `controller/*Controller.kt` |
| 1.4 | Fixed | Unknown-but-numeric ids were silently dropped, ignored or turned into `null`, so a client could not tell a stale id from an empty result. Every field still answers without them, but now reports them: resolvers report to `UnknownIds`, and the `UnknownIdReporting` instrumentation attaches one non-fatal `NOT_FOUND` error per field, listing `unknownNodeIds` / `unknownDependencyIds` in its extensions. | `UnknownIds.kt`, `controller/*Controller.kt` |

---

## 2. Matching semantics

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 2.1 | Fixed | Several fields matched ids against the exact endpoints of core dependencies. Only types are endpoints, so a package or module id matched nothing: `Node.dependenciesTo` / `dependenciesFrom`, `childrenFilteredByReferencedNodes` / `…ByReferencingNodes`, `DependencySet.filteredDependencies`. They now match subtrees (`NodeController.isWithin`): a dependency matches a node when its endpoint is the node or one of its descendants, as `dependencySetForAggregatedDependency` does. Type ids match as before. `SubtreeMatchingTest` covers each field. | `NodeController.kt`, `DependencySetController.kt` |
| 2.2 | Fixed | `DependencySet.filteredChildren` kept only children that were *themselves* endpoints, so for a module (children are packages) it always returned nothing. It now returns each child whose subtree contains a source or target (`NodeController.childContaining`), in child order. | `DependencySetController.kt` |
| 2.3 | Fixed | A selection whose ids were all unknown left its side of `filteredDependencies` unrestricted, so it returned every dependency. A side now restricts as soon as a selection names an id for it; unknown ids match nothing, so such a selection returns no dependency. An empty id list still does not restrict. | `controller/DependencySetController.kt` |
| 2.4 | Fixed | `referencedNodes` / `referencingNodes` and `filter*` on a package or module included nodes inside the receiver's own subtree. They now leave out results inside the subtree of the node they were reached from; in a `NodeSet` the rule is per member, so references between members still count. | `controller/NodeController.kt`, `controller/NodeSetController.kt` |
| 2.5 | Fixed | `FilteredDependencies.referencedNodes` follows all outgoing dependencies of the extracted nodes. The behaviour is kept, since it answers a question no other field does ("what else do the types in this cell use?"), and the schema description and the spec now say so; `nodes(nodeType: TARGET)` answers "what do these dependencies reference". | `hierograph-hierarchicalgraph.graphqls` |

---

## 3. Dependency structure matrix

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 3.1 | Fixed | `StronglyConnectedComponent.nodes` was in the algorithm's order while `nodePositions` was ascending, so the lists did not line up. The members are now sorted by DSM position: `orderedNodes[nodePositions[k]]` is `nodes[k]`. | `controller/NodeSetController.kt` |

---

## 4. Cost

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 4.1 | Fixed | `dependency(id)` and `dependencies(ids)` walked the whole tree on every call. The id is now parsed into source handle, target handle and type, and only the source's outgoing dependencies are searched. `dependencies(ids)` returns argument order without duplicates; a malformed id is a `BAD_REQUEST`. | `controller/HierarchicalGraphController.kt` |

---

## 5. Schema and documentation

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 5.1 | Fixed | The predecessor flag had three names and two nullabilities. It is `includePredecessors: Boolean! = false` on every field. | `hierograph-hierarchicalgraph.graphqls`, `controller/*Controller.kt` |
| 5.2 | Fixed | Nullability did not match the resolvers. The rule is now: a field that can reject its arguments is nullable, so a rejection nulls only that field. `DependencyPage.pageInfo` became `PageInfo!`; `nodes(ids)` became nullable like the other top-level id lookups; `dependencies(ids)` and `dependencyPage` stay nullable because they reject malformed arguments. | `hierograph-hierarchicalgraph.graphqls` |
| 5.3 | Fixed | `DependencySet.filteredChildrenIds` returned `[String!]!`; it is `[ID!]!` like every other id list. | `hierograph-hierarchicalgraph.graphqls` |
| 5.4 | Fixed | `globalIdentifier` always equalled `identifier`. The field is removed; `identifier` is the root handle. | `HierarchicalGraphController.kt`, `hierograph-hierarchicalgraph.graphqls` |
| 5.5 | Fixed | Most schema fields had no description. Every type, field and enum value now carries a `"""` / `"` description taken from the spec, shown in GraphiQL's documentation pane. | `resources/graphql/*.graphqls` |
| 5.6 | Fixed | `docs/graphql.md` did not mention `findNodes`. It now has a `findNodes` example and schema row, notes subtree matching, drops `globalIdentifier`, and has an *Errors* section listing the three classifications. | `docs/graphql.md` |

---

## 6. Text corrections made during the review

These changes affect only KDoc and schema comments. No logic changed.

- **Schema comments.** `dependency(id)` and `dependencies(ids)` were commented "Returns the node with the given id"; they now say they return dependencies, and name the id format.
- **History.** "the unit that now owns" in the `HierarchicalGraphController` KDoc lost its "now".
- **Retired technology.** The `NodePropertiesProvider` KDoc no longer mentions a Neo4j `GraphDbNodeSource`; the `ParquetNodePropertiesProvider` KDoc no longer mentions a `jqa` mode and states instead that it answers nothing without a loaded graph (both in `io.hierograph.server.graphservice`).
