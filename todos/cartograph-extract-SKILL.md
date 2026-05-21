---
name: cartograph-extract
description: Plan a code-extraction refactor (split a Maven/Gradle module, move a package into a new module, carve out a library) using cartograph. Enforces the checks needed to avoid hidden cross-package dependencies and projected Maven cycles. Trigger when the user says "extract X into a new module", "move this package to a new module", "split this module", "carve out a library", or similar. Do NOT use for: read-only "what depends on X" questions (use cartograph directly), single-file renames, or refactors that stay within one module.
---

# cartograph-extract — plan a module-extraction refactor

When the user wants to move a set of classes/packages out of one module into a new one, do these
checks in this exact order. The point of the skill is that **`pairwise_dependencies` cycle results
and `outgoing_core_dependencies` queries scoped to a sibling package will miss cross-package
edges** — every prior extraction plan that skipped step 3 here ended up with a Maven cycle the
user only discovered at build time.

## Why this exists

The natural mistake: you scope a cycle check to the move-set's own package (e.g.
`core.config → core.config`) and conclude the move-set is self-contained. It usually isn't.
Classes in the move-set typically import from sibling packages in the same module
(`core.exceptions`, `core.file`, `data.JsonUtil`, …). Those imports create the cycle. The
sibling-package query won't show them. The project-level `pairwise_dependencies` won't show them
either — that's an analysis of the current graph, not of a *proposed split*.

## The five checks

### 1. Enumerate the exact extract-set

Get concrete cartograph node IDs for every package and every individual class to be moved. Resolve
any wildcards (`TimeLimited*`) into a list of node IDs via `find_node` or `list_descendants` —
**no globs in the rest of the workflow**. If the user says "all `Foo*` types", expand the list and
read it back to them before continuing.

### 2. Outgoing cross-boundary check (cartograph)

For each move-set node, call:

```
outgoing_core_dependencies(
  fromId = <move-set-package-or-class>,
  toId   = <containing-module-or-artifact-root>,   # NOT the same-level sibling
  limit  = 100
)
```

The `toId` is the parent artifact / module root, **not** the move-set's own package. This surfaces
every class→class edge that leaves the move-set, regardless of which sibling package it lands in.

Classify each target:
- in the move-set → ignore
- in the staying-set → potential cycle, see step 5
- in an external library → just a Maven dependency to declare

### 3. File-level grep (ground truth — do NOT skip)

cartograph operates on a periodic scan; the working tree may be ahead. A plain grep is fast,
exhaustive, and definitive. Run:

```bash
grep -h '^import \(de\.\|com\.\|org\.\)' <every-moved-.java-file> | sort -u
```

(Adjust prefix for the project's package roots.) Read the output; any import not satisfied by the
new module's planned Maven dependencies is either a class you must also move or a cross-package
dep that creates a cycle.

If `grep` finds an import that cartograph didn't surface, trust `grep` and re-run step 2 against
the target's containing artifact to confirm. Do not silently widen the move-set.

### 4. Incoming check (downstream pom updates)

For each move-set node, call:

```
incoming_core_dependencies(
  fromId = <containing-module-root>,
  toId   = <move-set-package-or-class>,
  limit  = 100
)
```

This enumerates which downstream modules will need to declare a dependency on the new module.
Group by source artifact. Heavy consumers (large incoming weight from one artifact) probably want
an **explicit** pom declaration even if transitivity would resolve it.

### 5. Cycle projection — the decision step

If step 2 or step 3 found edges from the move-set into types that will *stay behind* in the
original module, the proposed split creates a Maven cycle:

```
new-module ──depends on──> staying type in original module
original ──depends on──> moved type in new module   (because users of moved types live there too)
```

This is a blocker. Present the user with three honest options:

1. **Expand scope** — pull the cross-boundary helpers into the new module. Only safe if the
   helpers are not used elsewhere in the staying module (`incoming_from` check on the staying
   module's other packages).
2. **Refactor source** — break the cross-boundary import (inline a small helper, parameterise an
   injected service, move a single exception class).
3. **Narrow the move-set** — keep `ServiceImpl`s/leaf code in the original module, only move
   interfaces/abstracts/data carriers.

Don't pick for them. Surface the trade-off, let them decide.

## Output of the skill

A plan file (markdown) with these sections:
- **Move-set** — exact class list, grouped by package
- **Bring along** — cross-package types that must also move and why
- **External libraries** — Maven deps the new module needs
- **Downstream consumers** — modules whose poms need an `<dependency>` on the new module
- **Cycle resolution** — if any, the chosen option from step 5 with rationale
- **Verification** — commands to run after implementation (build, re-scan, grep)

## Anti-patterns to avoid

- ❌ Concluding "no cycle" from `pairwise_dependencies` on the current graph. That analysis is
  pre-split. It says nothing about the proposed split.
- ❌ Scoping the cycle check to the move-set's own sibling-package level. Always go up to the
  containing artifact root.
- ❌ Skipping the grep in step 3 because cartograph "should have" caught it. cartograph is a
  cached scan; grep is ground truth. Both. Always.
- ❌ Silently widening the move-set when a missing dependency surfaces. Stop, re-plan, get user
  approval. The new types may have their own cross-boundary edges.
- ❌ Approving a plan that lists "out of scope: source refactor" while the move-set still has
  cross-boundary imports. The two are incompatible.

## When NOT to use this skill

- Read-only dependency questions ("what depends on `Foo`?") — call cartograph directly.
- Single-file moves within the same module — no new module boundary, no cycle risk.
- Renaming a package — different problem (import rewrites, not Maven topology).
- Repository-level moves (file lives in repo A, should be in repo B) — different concern.
