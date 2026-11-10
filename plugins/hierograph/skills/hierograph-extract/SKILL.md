---
name: hierograph-extract
description: Plan a code-extraction refactor (split a Maven/Gradle module, move a package into a new module, carve out a library, merge or move types between modules) using hierograph. Defines the proposed split as a restructuring view, so the server projects the new module's dependencies and any Maven cycle before code is touched, and enforces the checks needed to avoid hidden cross-package dependencies. Trigger when the user says "extract X into a new module", "move this package to a new module", "split this module", "carve out a library", "what if we moved X to Y", or similar. Do NOT use for: read-only "what depends on X" questions (use hierograph directly), single-file renames, or refactors that stay within one module.
---

# hierograph-extract — plan a module-extraction refactor

When the user wants to move a set of classes/packages out of one module into a new one, do these
steps in this order. The point of the skill: **a cycle check on the current graph says nothing
about the proposed split**, and a check scoped to the move-set's own package misses the
cross-package edges that create the cycle. Every extraction plan that skipped this ended up with a
Maven cycle the user only discovered at build time.

hierograph answers this directly with a **restructuring view**: a document that moves the
packages and types into a virtual module, applied by the server to a fork of the graph. Every tool
then answers *as if the split were done* (`view = "<name>"`), and the view's report states the
module dependencies the split adds and removes and the module cycles it creates. The checks below
read the projection instead of estimating it.

## Why this exists

The natural mistake: you scope a cycle check to the move-set's own package (e.g.
`core.config → core.config`) and conclude the move-set is self-contained. It usually isn't.
Classes in the move-set typically import from sibling packages in the same module
(`core.exceptions`, `core.file`, `data.JsonUtil`, …), and the staying code uses the moved
classes. Together those create the cycle. A restructuring view counts both directions over the
whole module, so it cannot miss them — provided the view moves exactly the move-set (step 2).

## The steps

### 1. Enumerate the exact extract-set

Get concrete hierograph nodes for every package and every individual class to be moved. Resolve
any wildcards (`TimeLimited*`) into a list via `find_node` or `list_descendants` — **no globs you
have not expanded** in the rest of the workflow. If the user says "all `Foo*` types", expand the
list and read it back to them before continuing. Note each node's qualified name and the module
it lives in (`parent` chain, or `type_details`): the view selects by qualified name.

### 2. Define the split as a restructuring view

```
define_view(document = {
  "name": "extract-billing",
  "restructure": true,
  "modules": [ { "name": "acme-billing" } ],
  "moves": [
    { "select": { "match": "com.acme.billing", "kind": "java.package",
                  "within": "com.acme:acme-core:*" },
      "to": "acme-billing" },
    { "select": { "match": "com.acme.core.Invoice", "kind": "types" },
      "to": "acme-billing" }
  ]
})
```

- One `moves` entry per package or type of step 1. Select by the **exact** qualified name, with
  `kind` and `within` (the containing module's GAV, version as `*`) so a same-named package in
  the test artifact is not dragged along. A package moves with its subtree; a type with its
  nested types.
- `to` is a virtual module's name, an existing module (`com.acme:acme-api:*`, must match exactly
  one), or a package in one (`<module>/<package fqn>`).
- The view name is lower-case letters, digits, `.`, `_`, `-`. View names are global on the
  server; reuse the name for every draft so `define_view` replaces the previous one.

**Check the report before anything else.** `report.errors` must be `0`, and the warnings must be
understood: `MOVE_SELECTS_NOTHING` or `NODE_MOVED_TWICE` means the view moves something other than
the move-set, and every later number describes a different split. Then confirm the moved set:
`list_descendants(nodeId = <virtual module id from virtual_modules>, kindFilter = ["types"],
view = "<name>")` must list exactly the types of step 1 (plus their nested types).

### 3. Read the projected effect

`define_view` returns `restructuring`:

| Field | Meaning |
|-------|---------|
| `added` | module dependencies the split creates: `new-module → X` are what it needs, `X → new-module` are its consumers |
| `removed` | dependencies that disappear (e.g. a consumer that only used the moved code) |
| `changed` | dependencies whose weight changes (`weight_before`, `weight_after`) |
| `new_cycles`, `introduces_cycles` | module cycles the split creates — the decision of step 6 |

A cycle in `new_cycles` that contains the new module and its origin module is the classic
projected Maven cycle. Report it with the SCC's members.

### 4. Cross-boundary evidence (which exact types)

The effect gives weights; the tools in the view give the types behind them. With the virtual
module's id (`virtual_modules[].id`) and the staying module's id:

```
outgoing_dependencies(fromId = <new module id>, toId = <origin module id>, view = "<name>")
    # what the moved code still needs from the code that stays: "bring along" candidates
outgoing_dependencies(fromId = <origin module id>, toId = <new module id>, view = "<name>")
    # what the staying code uses of the moved code: the other half of a cycle
```

Use `detailLevel = "detail"` for the member references and line numbers behind an edge. Classify
each target of the first call:

- in the staying module → cycle risk if the second call is non-empty; see step 6
- in another module → a Maven dependency to declare on the new module
- an external type (under "External Types") → a library dependency to declare

### 5. File-level grep (ground truth — do NOT skip)

hierograph operates on a scan; the working tree may be ahead. A plain grep is fast, exhaustive,
and definitive. Run:

```bash
grep -h '^import \(de\.\|com\.\|org\.\)' <every-moved-.java-file> | sort -u
```

(Adjust the prefix for the project's package roots.) Any import not satisfied by the new module's
planned dependencies is either a class you must also move or a cross-package dependency that
creates a cycle. If `grep` finds an import hierograph didn't surface, trust `grep`, rebuild (the
server rescans compiled classes) and redefine the view. Do not silently widen the move-set.

### 6. Cycle resolution — the decision step

If `introduces_cycles` is true, the split as drafted creates a Maven cycle:

```
new-module ──depends on──> staying type in the origin module
origin     ──depends on──> moved type in the new module
```

This is a blocker. Present the user with three honest options, and **project the first and third
with the view** before recommending anything:

1. **Expand scope** — pull the cross-boundary helpers of step 4 into the new module: add moves,
   `define_view` again, read `new_cycles`. A helper is safe to move only if nothing else in the
   staying module needs it — the redefined view shows that as a new edge or cycle the other way.
2. **Refactor source** — break the cross-boundary import (inline a small helper, inject a
   service, move a single exception class). The view cannot simulate a code change; say which
   edge the refactoring removes (from step 4) and what weight it carries.
3. **Narrow the move-set** — keep `ServiceImpl`s and leaf code in the origin module, move only
   interfaces, abstract types and data carriers: drop moves, define again.

Don't pick for them. Surface the trade-off with the projected numbers of each draft, let them
decide.

### 7. Downstream consumers

The `added` entries `X → new-module` are the modules whose build needs a dependency on the new
module after the split; `changed` entries show consumers whose coupling to the origin shrinks.
For per-consumer evidence:

```
incoming_dependencies(fromId = <new module id>, toId = <consumer module id>, view = "<name>")
```

**Mind the direction**: `fromId` is the *depended-upon* (the new module), `toId` the *depender*;
the edges run from `toId`'s subtree into `fromId`'s. Heavy consumers probably want an explicit
dependency even if transitivity would resolve it.

## Output of the skill

A plan file (markdown) with these sections:
- **Move-set** — exact class list, grouped by package
- **Bring along** — cross-package types that must also move and why
- **New module's dependencies** — modules and external libraries it needs (step 4, `added`)
- **Downstream consumers** — modules whose builds need a dependency on the new module (step 7)
- **Cycle resolution** — if any, the chosen option from step 6 with the projected effect of each
  draft
- **The view** — the final document, so the projection can be re-run after every rebuild
- **Verification** — commands to run after implementation (build, re-scan, grep), and that the
  real module graph then matches the projection

When the plan is done, ask whether to keep the view. `delete_view(name)` removes it; to keep it
with the code, the user saves the document as `.hierograph/views/<name>.json` in the build root,
and every rebuild then re-checks the code against the planned split.

## Anti-patterns to avoid

- ❌ Concluding "no cycle" from `pairwise_dependencies` on the current graph. That analysis is
  pre-split. Read `new_cycles` of the restructuring view.
- ❌ Trusting a projection whose report has errors or unexplained warnings. A skipped or empty
  move means the view describes a different split.
- ❌ Forgetting `view = "<name>"` on a call. The answer silently comes from the current graph, and
  the virtual module's id is unknown there (`NODE_NOT_FOUND`).
- ❌ Selecting by a broad pattern (`com.acme.billing*`) instead of the exact extract-set: it
  catches sub-packages and test artifacts the user did not ask to move.
- ❌ Skipping the grep in step 5 because hierograph "should have" caught it. hierograph is a
  scan; grep is ground truth. Both. Always.
- ❌ Inverting the `incoming_dependencies` direction. `fromId` is the *depended-upon*, `toId` the
  *depender*. Swapping them returns the opposite direction silently and the "downstream
  consumers" list will be wrong.
- ❌ Silently widening the move-set when a missing dependency surfaces. Stop, re-plan, get user
  approval. The new types may have their own cross-boundary edges — the redefined view shows them.
- ❌ Approving a plan that lists "out of scope: source refactor" while the projection still has a
  new cycle. The two are incompatible.

## When NOT to use this skill

- Read-only dependency questions ("what depends on `Foo`?") — call `incoming_dependencies` or
  `aggregated_dependencies` directly.
- Single-file moves within the same module — no new module boundary, no cycle risk.
- Renaming a package — different problem (import rewrites, not Maven topology).
- Repository-level moves (file lives in repo A, should be in repo B) — different concern.
- Looking at the system by layer or multi-module without moving code — that is a grouping view,
  in **hierograph-structure**.
