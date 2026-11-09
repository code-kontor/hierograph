# MCP Tool Review — Findings

These findings come from reviewing the MCP tool implementations in
`hierograph-server/io.hierograph.server` against their specifications. Each one is code
behaviour that looks unintended or inconsistent. The specifications in
`hierograph-server/io.hierograph.server/spec/` document the behaviour as it is; this
document lists what the maintainer should decide on: change the code, or keep the
behaviour as specified.

Paths are relative to
`hierograph-server/io.hierograph.server/src/main/kotlin/io/hierograph/mcp/server/`
unless they start with a module name. Line numbers are approximate, because the
description and KDoc text in these files was corrected during the review.

Severity:

- **High:** wrong results, or a documented feature that does nothing.
- **Medium:** inconsistent or misleading behaviour.
- **Low:** cosmetic issues or edge cases.

---

## Contents

1. [Cross-cutting](#1-cross-cutting)
2. [Navigation](#2-navigation)
3. [Dependency analysis](#3-dependency-analysis)
4. [Reachability](#4-reachability)
5. [Entity detail](#5-entity-detail)
6. [Maintenance](#6-maintenance)
7. [Text corrections made during the review](#7-text-corrections-made-during-the-review)

---

## 1. Cross-cutting

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 1.1 | Fixed | Cursors did not survive a server restart: `DataHash` hashed `node.kind?.hashCode()`, which for a `JavaNodeKind` enum differs from one JVM process to the next. It now hashes the kind's text; `DataHashTest` pins the fingerprint of a graph with enum kinds. | `io.hierograph.server.graphservice/.../pagination/DataHash.kt` |
| 1.2 | Fixed | There were three error shapes. Every tool now returns the nested `{error:{code,message,…,recovery}}`: the detail-level engine, `method_details`, `field_details` and `DETAIL_REBUILDING` use `AbstractDetailTool.errorResponse`, and each error got a `recovery`. | `tools/detail/AbstractDetailTool.kt` |
| 1.3 | Fixed | `INVALID_KIND` had two shapes, and `affected_by` did not reject unknown kinds. `find_node`, `list_children`, `list_descendants` and `affected_by` now share one parser and one error (`KindFilter`): `invalid_values`, `valid_kinds`, `valid_aliases` and a `recovery`. | `tools/KindFilter.kt` |
| 1.4 | Fixed | One tool call could mix two graphs, because tools read the service several times per call. Every MCP tool call now runs inside `HierarchicalGraphService.withPinnedSnapshot`, which pins the live snapshot to the calling thread; while it is no longer live, detail data reads as not ready. GraphQL requests are not pinned. | `io.hierograph.server.graphservice/.../HierarchicalGraphService.kt`, `core/GraphAvailabilityToolCallbackProvider.kt` |
| 1.5 | Fixed | The enriched NodeRef carried only counts while KDoc and descriptions promised modifiers and flags. Type nodes now keep their access flags in memory like members (2.1), so enriched NodeRefs carry `modifiers` and `is_abstract` for types and `modifiers` for methods and fields. The unimplemented promises (annotation and interface counts, `is_generic`, …) are gone from KDoc and descriptions; those facts stay with the entity-detail tools. | `tools/NodeRefFactory.kt` |
| 1.6 | Fixed | `external.type` could not be selected by kind. It is now a valid `kindFilter` value in every tool (and in GraphQL `findNodes`); the `types` alias still means the scanned types. | `tools/KindFilter.kt`, `NodeSearch.kt` |
| 1.7 | Fixed | A member's kind and name differed between tools: `java.constructor` and the bare name in the detail tools, `java.method` and the signature in the tree. Every tool now reports a member as the tree does: by signature, a constructor as `java.method` (with `is_constructor` where it matters). | `ParquetMethodDetails.kt`, `ParquetFieldDetails.kt`, `DetailEnricher.kt` |

---

## 2. Navigation

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 2.1 | Fixed | `modifierFilter` in `list_children` and `list_descendants` was accepted but ignored. Member nodes now keep their packed access flags in memory (`Node.modifiers`, `GraphStoreNodeSource.modifiers`), and both tools keep the methods and fields that have every listed modifier. Unknown values give `INVALID_PARAMETER`. | `tools/navigation/ModifierFilter.kt` |
| 2.2 | Fixed | `find_node` never returned modules: `NodeSearchIndex.build` traversed from each module, and `traverse` skips its start node. The index now traverses from the root, so modules are indexed and `kindFilter ["java.module"]` works, in GraphQL `findNodes` as well. | `io.hierograph.server.graphservice/.../NodeSearchIndex.kt` |
| 2.3 | Fixed | An empty `kindFilter: []` in `list_children` and `list_descendants` returned `INVALID_KIND`. With the shared `KindFilter` (1.3), `[]` is no filter in every tool. | `tools/KindFilter.kt` |
| 2.4 | Fixed | `find_node` hid truncation: `summary.total` was the capped count. `NodeSearch.find` now counts all matches, `summary.total` reports them, and `summary.truncated` is `true` when more than the 50 returned exist. | `tools/navigation/FindNodeTool.kt`, `NodeSearch.kt` |
| 2.5 | Fixed | The `kinds` vocabulary of `graph_overview` omitted `external.type`, and `nodes_by_kind` / `total_nodes` left out the modules. The vocabulary now defines `external.type`, and the counts start at the root, so they cover every node but the root. | `tools/navigation/GraphOverviewTool.kt` |
| 2.6 | Fixed | The detail vocabulary of `graph_overview` listed `parameter_annotated_by`, which the snapshot never holds, and omitted `raises`. It now lists `raises` and drops `parameter_annotated_by`; the detail filter still accepts the latter (3.9). | `tools/navigation/GraphOverviewTool.kt` |
| 2.7 | Fixed | `outgoing_dep_count` / `incoming_dep_count` in `graph_overview` included dependencies inside the module. They now count only dependencies that cross the module boundary. | `GraphOverviewTool.kt` |
| 2.8 | Fixed | The `model` text of `graph_overview` named snake_case parameters. It now names `sourceIds`, `targetIds` and `detailLevel`. | `GraphOverviewTool.kt` |
| 2.9 | Fixed | `list_children` allowed 1000 results and listed children unsorted; `list_descendants` sorted case-sensitively with ids compared as text. `list_children` is capped at 200 (the response budget), and both tools sort by qualified name ignoring case, then by name, then by numeric id (`CHILD_ORDER`). | `ListChildrenTool.kt`, `ListDescendantsTool.kt`, `ChildOrder.kt` |
| 2.10 | Fixed | The `list_descendants` query hash used the raw `namePattern` and unexpanded aliases. It now hashes the lower-cased pattern and the expanded, sorted kinds, so equivalent spellings keep a cursor valid. | `ListDescendantsTool.kt` |
| 2.11 | Fixed | `list_descendants` advertised a cap of 500 while the response budget limited a page to 200. The cap is now 200. | `ListDescendantsTool.kt` |

---

## 3. Dependency analysis

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 3.1 | Fixed | `by_source_nodes` and `by_target_nodes` descended through a single-child node into a type's members, which have no type-level weight, so a package holding one type gave an empty drill-down. The descent now stops at types and lists only packages and types. A type scope has no narrower type-level scope; its drill-down is empty by definition. | `tools/detail/ParquetDetailDependencies.kt` (`drillDown`) |
| 3.2 | Fixed | `direction` in `pairwise_dependencies` filtered nothing: `outgoing` and `both` were identical and `incoming` only swapped `from` and `to`. A matrix places every edge in exactly one cell, so a direction filter cannot remove anything. The parameter is removed (MCP and REST); every edge reads depender → depended-upon. Callers that still send it are unaffected, because unknown arguments are ignored. | `tools/dependencyanalysis/PairwiseDependenciesTool.kt` |
| 3.3 | Fixed | Member ids gave an empty result at detail level, and the type-level recovery sent callers there. The detail level now accepts a method or field id and narrows the declaring type's scope to that member's rows; the type-level recovery says so. | `tools/detail/ParquetDetailDependencies.kt` |
| 3.4 | Fixed | Nested or duplicate `nodeIds` in `pairwise_dependencies` were accepted, so results depended on input order and could show spurious cycles. Such a set is now rejected with `INVALID_PARAMETER`, naming the `duplicates` and the `nested` pairs. | `tools/dependencyanalysis/PairwiseDependenciesTool.kt` |
| 3.5 | Fixed | The incoming detail level checked `toId` first and called it the source. Both dependency tools now check `fromId`, then `toId`, before the detail engine runs, with the type level's message "No node with id … exists in the graph." | `OutgoingDependenciesTool.missingDetailScope` |
| 3.6 | Fixed | The detail scope includes the transitive supertypes of both sides, so rows from outside the requested subtrees appeared unmarked. The closure is kept (a type's behaviour includes what it inherits, and the integration tests rely on it), but every row it alone brings in carries `"inherited": true`, and `summary.inherited` counts them. | `tools/detail/ParquetDetailDependencies.kt` |
| 3.7 | Fixed | Load-derived detail rows were appended after the engine-sorted rows. The combined rows are now sorted as a whole, in the engine's order. | `tools/detail/ParquetDetailDependencies.kt` (`ROW_ORDER`) |
| 3.8 | Fixed | `pairwise_dependencies` had no page counts and the detail level named its count `total_edges`. Every page of every paginated tool now carries `summary.total`, `returned` and `truncated`: pairwise replaces `total_matching_edges` with them and keeps its matrix analysis on the first page only; the detail level renames `total_edges` to `total`. | `PairwiseDependenciesTool.kt`, `ParquetDetailDependencies.kt` |
| 3.9 | Fixed | `parameter_annotated_by` was accepted but never stored. The scanner now emits a `parameter_annotated_by` detail row per distinct parameter annotation, and `graph_overview` lists the relationship again. | `ClassScanner.kt` |
| 3.10 | Fixed | At type level the `nodes` map omitted ids that appear only in `by_target` / `by_source_type`. It now names them. | `OutgoingDependenciesTool.kt` |
| 3.11 | Fixed | The detail level returned its empty result before pagination, ignoring a malformed or stale cursor. The cursor is now checked first. | `ParquetDetailDependencies.kt` |
| 3.12 | Fixed | Detail rows resolved an FQN to its first defining artifact. The reader tags each row with the artifact that holds it; the engine resolves the depending type in that artifact and the target through its classpath, and keeps only rows whose types are the scopes' own nodes. Unpacked snapshots and load-derived rows, whose artifact is unknown, still resolve to the first definer. | `ParquetReader.readDetailRefs`, `ParquetDetailDependencies.kt`, `GraphStore.resolveType` |
| 3.13 | Fixed | `aggregated_dependencies` accepted empty lists and turned duplicate ids into duplicate edges. An empty list now fails with `INPUT_TOO_SMALL`, and repeated ids are ignored after the first. | `AggregatedDependenciesTool.kt` |
| 3.14 | Fixed | The advertised page caps exceeded what the response budget allowed, so large limits failed with `RESULT_TOO_LARGE`. Every cap is now the largest page that fits the budget (142 / 90 / 111 / 200), `PaginationSpec` rejects a cap that would not, larger limits are clamped, and `RESULT_TOO_LARGE`, now unreachable, is gone. `list_children` is capped at 200 for the same budget. | `pagination/Paginator.kt`, tool `PaginationSpec`s |

---

## 4. Reachability

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 4.1 | Fixed | `source_count` in `affected_by` only grew while direct neighbours were seeded, so every result at distance 2 or more reported 1. It is now the number of source types that reach the result within `maxDepth`, along paths outside the source subtree, computed by one bounded walk per source type over the types already found. | `tools/reachability/AffectedByTool.kt` (`countSources`) |
| 4.2 | Fixed | `affected_by` accepted the `packages` and `members` aliases, which always gave an empty result. Its kind filter now accepts only what can be affected (type kinds, `external.type`, `types`) and rejects the rest with `INVALID_KIND`. | `AffectedByTool.kt`, `KindFilter.kt` |
| 4.3 | Fixed | `summary.from` / `to` of `find_dependency_path` echoed the input ids under names that suggested endpoints. They are now `from_scope` / `to_scope`, as at the detail level, and each path names its endpoint types as `from` / `to`. | `tools/reachability/FindDependencyPathTool.kt` |
| 4.4 | Fixed | `find_dependency_path` kept paths in discovery order and sorted afterwards, so the heaviest equal-length paths were not guaranteed. The search now ranks each length's candidates by weight before keeping any. | `tools/reachability/FindDependencyPathTool.kt` |
| 4.5 | Fixed | The path search carried one queue entry per partial path, which grew combinatorially on dense graphs (a 10-layer test graph ran the JVM out of heap). Each type now keeps at most `maxPaths` partial paths, merged per layer, so the work is bounded by dependencies × `maxPaths`. | `tools/reachability/FindDependencyPathTool.kt` |
| 4.6 | Fixed | A type's scope in `affected_by` and `find_dependency_path` left out its nested types, so `affected_by(Invoice)` could report `Invoice$Line`. Both tools now include every type of the package named `<type>$…` (`NestedTypes.allNested`). The dependency tools keep the tree's scope, in which a type covers its members only. | `tools/reachability/*.kt`, `tools/detail/NestedTypes.kt` |
| 4.7 | Fixed | `maxDepth` ≤ 0 behaved like 1 and `maxLength` ≤ 0 returned `exists: false`. Both are now rejected with `INVALID_PARAMETER`. | `AffectedByTool.kt`, `FindDependencyPathTool.kt` |
| 4.8 | Fixed | The "same source and target" exit fired only for one single type. It is gone: overlapping scopes are searched like any others, and a scope against itself yields its internal dependencies and cycles. | `FindDependencyPathTool.kt` |
| 4.9 | Fixed | The no-search exit returned `nodes: {}`. Every response now names both scopes in `nodes`. | `FindDependencyPathTool.kt` |
| 4.10 | Fixed | Modules with equal counts in `by_parent_module` came in `HashMap` order. They are now ordered by name, then id. | `AffectedByTool.kt` |
| 4.11 | Fixed | `affected_by` checked `direction` before the node, and `find_dependency_path` checked both ids' existence before either kind. Both now check the nodes first, `fromId` completely before `toId`, then the other parameters. | `AffectedByTool.kt`, `FindDependencyPathTool.kt` |

---

## 5. Entity detail

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 5.1 | Fixed | `inner_types` in `type_details` was always empty and `parent_container` never the enclosing type, because nested types are package siblings. Nesting is now derived from binary names (`NestedTypes`): the enclosing type of `Outer$Inner` is the sibling `Outer`, and `inner_types` lists the direct member types, without anonymous and local classes. | `tools/detail/NestedTypes.kt` |
| 5.2 | Fixed | `type_details` rejected `external.type` ids, which it returns itself as supertypes and annotations. It now accepts them and describes what the graph knows: identity and package; the facts only a scan provides are `null` or empty. | `tools/detail/ParquetTypeDetails.kt` (`externalTypeDetails`) |
| 5.3 | Fixed | `is_generic` was hard-coded `null`. The scanner now records a `generic` bit (bit 13 of `types.modifiers`) when the class signature declares type parameters, and `type_details` reports it. | `ClassScanner.kt`, `TypeModifiers.kt`, `ParquetTypeDetails.kt` |
| 5.4 | Fixed | `method_details` and `field_details` reported a type, package or module id as `NODE_NOT_FOUND`. Such an id now gets `WRONG_NODE_KIND` with `actual_kind` and a recovery naming the right tool. | `AbstractDetailTool.notAMember` |
| 5.5 | Fixed | Field annotations whose type has no node were dropped. Each annotation now carries `type_name`, and `type` is `null` without a node. | `ParquetFieldDetails.kt` |
| 5.6 | Fixed | `overrides` held one target. It is now the list of all overridden methods in the scanned code, sorted by member key. Overrides of external types stay absent, because their methods are not in the snapshot. | `ParquetMethodDetails.kt` |
| 5.7 | Fixed | `type_details` read members and refs by FQN across artifacts. Row, members and refs now come from the one artifact that defines the node (`ParquetReader.readTypeFacts`). The GraphQL node-properties provider still reads members by FQN. | `ParquetTypeDetails.kt`, `ParquetReader.kt` |
| 5.8 | Fixed | Parameter annotations were always `[]`. The scanner records them per parameter in a new `annotations` column of `parameters.parquet`, and `method_details` reports them. | `ClassScanner.kt`, `ParameterFacts.kt`, `ParquetMethodDetails.kt` |
| 5.9 | Fixed | `field_details` carried a `location` that was always `null`, because a field declaration has no line number in bytecode. The key is gone. A method's `location` stays the first line of its code; the class file records no declaration line, and the spec says so. | `ParquetFieldDetails.kt` |
| 5.10 | Fixed | `type_name` was `"unknown"` without a `has_type` edge. Type and name now come from the field's signature, so `type_name` always names the declared type (with `[]` for arrays); `type` is the element type's id or `null` without a node, as for annotations. | `ParquetFieldDetails.kt` |
| 5.11 | Fixed | `methods_sample` was ordered by declaring type only, and accesses compiled against a subtype were not counted. The sample is ordered by type, then signature, and accesses through subtypes that inherit the field count. Inlined compile-time constants leave no read in bytecode; the spec says so. | `ParquetFieldDetails.kt` |
| 5.12 | Fixed | `is_constant` meant `static && final`, and modifiers hid `synthetic`/`bridge`. A new member bit records a `ConstantValue` field, `is_constant` reports it, and the modifier lists end with `bridge` / `synthetic` (also accepted by `modifierFilter`). | `MemberModifiers.kt`, `ClassScanner.kt` |
| 5.13 | Fixed | `type_details`' `DETAIL_REBUILDING` is nested since 1.2; a package id's `WRONG_NODE_KIND` named the parent package as `declaring_type`. The key is now present for members only. | `ParquetTypeDetails.kt` |
| 5.14 | Fixed | `type_details` counted constructors outside `method_count`, unlike `list_children`. `method_count` now includes them, and `constructor_count` is that subset. | `ParquetTypeDetails.kt` |

---

## 6. Maintenance

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| 6.1 | Fixed | The `reload_graph` error `note` always claimed a previous graph was still served. It now says so only when one is, and "No graph is loaded" otherwise. | `HierarchicalGraphService.reload` |
| 6.2 | Fixed | The no-graph message described recovery for manifest mode only. It now names both sources: build the project for a manifest, provide the snapshot directory for Parquet. | `core/GraphUnavailable.kt` |

---

## 7. Text corrections made during the review

These changes affect only descriptions, messages and KDoc. No logic changed.

- **Parameter names.** All tool descriptions, parameter descriptions and error/recovery messages now use the camelCase names that clients actually send (`nodeId`, `kindFilter`, `fromId`, `detailLevel`, `maxDepth`, …).
- **Nonexistent tools.** References to `list_methods`, `list_fields`, `detail_dependencies`, `aggregated_incoming` and `page_size` now point to the real tools (`list_children`, `outgoing_dependencies` / `incoming_dependencies` with `detailLevel` `"detail"`, `limit`).
- **Retired technology.** Mentions of jQAssistant, Neo4j, Cypher, Bolt and "MVP" were removed from KDoc and messages, in `GraphUnavailable`, `DataHash`, `JavaKinds`, `MemberModifiers`, `DetailQueryEngine`, `HierarchicalGraphService` and the detail tools.
- **No-graph message.** It now tells the caller to build the project and call `reload_graph`.
- **Misleading descriptions.**
  - `reload_graph` no longer promises a new hash or stale cursors on every reload.
  - The `direction` parameter description of `pairwise_dependencies` states its labelling semantics.
  - `source_count` of `affected_by` is described as it is computed.
  - The `DETAIL_UNAVAILABLE` message no longer claims Parquet mode is required.
- **Service spec.** `hierarchical-graph-service.md` now says cursors become stale only when a reload changes the graph.
