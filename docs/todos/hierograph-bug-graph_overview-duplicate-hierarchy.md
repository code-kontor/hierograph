# Bug: `graph_overview` emits every module twice in `hierarchy[]`

**Component:** hierograph MCP server — `graph_overview` tool
**Severity:** Low (correctness/efficiency, not crashing) — but wastes ~50% of the response payload on a token-budgeted channel
**Reported from:** Claude Code session against the DARE codebase (jqassistant scan, 272 modules), 2026-06-07

---

## ✅ RESOLVED (2026-06-07)

Root cause was in graph construction, not in `graph_overview` itself. `graph_overview`'s
`hierarchy[]` is a plain map over `hierarchy.childrenOf(rootNode)`; the root's child list contained
duplicates because:

1. `JQAssistantHierarchyProvider.toplevelNodeIds` is a `flatMap` over several Cypher queries, and a
   Maven project producing both a Main and a Test artifact matches the project query twice — so the
   directory id was returned twice.
2. `DefaultMappingService` step 5a (top-level nodes) added each returned id to the root **without a
   guard**, unlike step 5b (parent-child), which already skipped a child that already had a parent.
   The synthetic external/virtual module came from a different query that returned it once, hence the
   single trailing exception.

Fix (two layers):

- **Invariant enforced at construction** — `DefaultMappingService` step 5a now adds a top-level node
  only when `hierarchy.parentOf(node) == null`, so an id can never be added to the hierarchy twice
  regardless of what the provider returns (mirrors the existing step-5b guard).
- **Provider no longer returns duplicate ids** — `toplevelNodeIds` is de-duplicated by id in both
  `JQAssistantHierarchyProvider` and `AbstractQueryBasedHierarchyProvider` (`distinctBy { it.id }`).

Regression test: `MappingServiceHierarchyDedupTest` — a provider that returns a top-level id twice
yields a hierarchy with that id under the root exactly once.

The **side note** below (giving the synthetic external aggregate a stable label) is **not** addressed
by this fix and remains open.

---

## Summary

The `graph_overview` response field `hierarchy[]` contains each top-level
module **duplicated as two consecutive, byte-identical entries**. Every named
module appears twice in a row; only the final (trailing) node appears once.

For a 272-module codebase this roughly **doubles the size** of an already-large
orientation response that is returned inline into the model's context window —
the most expensive place to waste tokens.

## Expected behavior

Each module appears exactly once in `hierarchy[]`. Length of `hierarchy[]`
should equal the number of top-level modules.

## Actual behavior

Each module appears twice, back-to-back, with identical `id` and identical
field values. The last element in the array (the unnamed external-aggregate
node, see note below) appears only once — strongly suggesting an
append-the-current-then-append-again loop with an off-by-one at the tail,
rather than a post-hoc `list + list` concatenation (which would duplicate the
tail too).

## Evidence (verbatim, from a real `graph_overview` call)

Two consecutive identical entries for the very first node:

```json
{"id":12816,"name":"DARE :: Libs :: Concurrency","qualified_name":"de.dare_plattform:kotlin-test:101.0.0-SNAPSHOT","kind":"java.module","parent_id":-1,"parent_kind":null,"child_count":2,"descendant_type_count":9,"descendant_method_count":29,"outgoing_dep_count":136,"incoming_dep_count":223},
{"id":12816,"name":"DARE :: Libs :: Concurrency","qualified_name":"de.dare_plattform:kotlin-test:101.0.0-SNAPSHOT","kind":"java.module","parent_id":-1,"parent_kind":null,"child_count":2,"descendant_type_count":9,"descendant_method_count":29,"outgoing_dep_count":136,"incoming_dep_count":223},
```

This pattern repeats for **every** module (`id":4` twice, `id":5` twice,
`id":19` twice, `id":30` twice, … through `id":55` twice).

The single trailing exception — appears only once:

```json
{"id":8117862,"name":"","qualified_name":"","kind":"java.module","child_count":19,"descendant_type_count":2211,"descendant_method_count":0,"outgoing_dep_count":0,"incoming_dep_count":75287}
```

The `stats` block in the same response is correct and **not** doubled
(`nodes_by_kind.java.module = 272`), so the bug is isolated to the assembly of
the `hierarchy[]` array, not to the underlying node counts.

## Reproduction

1. Load any multi-module codebase into hierograph.
2. Call `graph_overview` (no parameters).
3. Inspect `hierarchy[]`: observe consecutive duplicate entries; observe
   `len(hierarchy)` ≈ `2 * (module_count) - 1`.

## Suspected root cause

A loop that appends each node to the result list twice (e.g. an inner
`append` left in place, or a yield + append), terminating before the second
append of the last element. The off-by-one tail (only the last node not
duplicated) points at the loop body rather than at a whole-list concatenation.

Worth checking: whether `find_node`/`list_children`/`list_descendants` share
the same serialization helper and exhibit the same doubling.

## Impact

- ~2x payload for the primary orientation call → wasted context-window tokens.
- Any client that trusts `hierarchy[]` as a unique module list (e.g. building a
  picker, counting modules, deduping by index) gets duplicates and must
  defensively dedupe by `id`.

## Suggested fix / acceptance criteria

- `hierarchy[]` contains each module exactly once.
- `len(hierarchy) == stats.nodes_by_kind["java.module"]` (minus any nodes
  intentionally excluded, e.g. the synthetic external node — decide and document
  whether it belongs in `hierarchy` at all, since its `name`/`qualified_name`
  are empty).
- Regression test asserting no duplicate `id`s in `hierarchy[]`.

## Side note (separate, not part of this bug)

The trailing node `id:8117862` has empty `name` and `qualified_name` and carries
75,287 incoming dependencies / 0 outgoing — it is the synthetic
"external / third-party / JDK" aggregate. Consider giving it a stable label
(e.g. `«external»`) so clients can identify it without inferring from the
edge profile. Filed separately if you want it tracked.
