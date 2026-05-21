---
name: cartograph-dsm
description: Render a Dependency Structure Matrix (DSM), or perform layering / cycle-detection analysis over a set of modules using cartograph. Enforces use of `pairwise_dependencies` (which returns the structural digest in one call) and prevents the common failure of hand-picking a row order that fabricates apparent back-edges. Trigger when the user says "show as a DSM", "dependency structure matrix", "module coupling matrix", "layering analysis", "is X properly layered", "are there cycles", "find cycles between these modules", or "show me the structure of these modules". Do NOT use for: "what depends on X" (use `aggregated_incoming`), "what does X depend on" (use `aggregated_outgoing`), or one-off pair checks (use `dependency_between`).
---

# cartograph-dsm — DSM, layering, cycle detection

When the user wants a matrix view of dependencies among a set of modules, or asks whether a set
of modules is properly layered / has cycles, follow this procedure exactly. The point of the
skill is that **`pairwise_dependencies` already returns the topological order, cycle status, and
SCCs in one call** — re-deriving them by hand (looping `aggregated_outgoing`, hand-picking a row
order from naming heuristics) produces wrong answers that *look* right.

## Why this exists

The natural mistake: build the matrix by calling `aggregated_outgoing` once per module, then
order the rows by your own guess (e.g. "`*.testfwk` modules go after their main module"). This
sometimes creates apparent back-edges that don't exist in the graph — your row order simply
isn't a valid topological ordering. Worse, in a cyclic graph it can hide a real cycle by placing
both participants in the right relative position by luck. `pairwise_dependencies` computes the
ordering once, authoritatively, and tells you whether the graph is acyclic.

This skill exists because that mistake was made: a DSM was rendered with three above-diagonal
entries that the author dismissed as "back-edges to investigate" when in fact the graph was
acyclic and the row order was wrong.

## The procedure

### 1. Choose the node set

Resolve the modules of interest to concrete node IDs:

- Top-level modules: `describe_graph()` → use `top_level_children` IDs.
- Children of a scope: `list_children(nodeId=<scope>)` (only the *direct* children — never
  recurse for this step).
- A user-named group: `find_node` for each.

If the user said "modules" without scoping, default to top-level projects (children of root).
Read the resolved set back to the user before continuing.

### 2. Call `pairwise_dependencies` exactly once

```
pairwise_dependencies(
  nodeIds          = [<all IDs from step 1>],
  includeSelfLoops = false   # true only if ranking modules by internal coupling
)
```

One call. Do **not** loop `aggregated_outgoing` or `dependency_between` to assemble the matrix.

### 3. Read the `summary` block before rendering

`summary.has_cycles`, `summary.density`, `summary.max_edge_weight`,
`summary.strongly_connected_components`, `summary.topological_order` are the headline answer for
most layering questions. Check them first; for "is this properly layered?" the summary alone is
the answer and a matrix may not even be necessary.

### 4. Render using `topological_order`, reversed

The tool returns `topological_order` as [consumer, …, sink]. **Reverse it** so sinks (base
modules) sit at the top of the matrix and consumers at the bottom. Matrix convention:

- Rows = source (depender); columns = target (depended-upon).
- Cell = edge weight; `·` = no dependency; `■` = diagonal.
- Use short labels (rightmost path segment is usually enough).

With a reversed topological order on an acyclic graph, every edge falls **on or below** the
diagonal. That visual is the signal of clean layering.

### 5. Verify before reporting (mandatory)

Scan the rendered matrix for any cell above the diagonal:

- If `summary.has_cycles == false` and you see one → **your rendering is wrong**, not the data.
  You either picked the row order incorrectly or transcribed an edge into the wrong cell.
  Re-render before reporting; do not publish "interesting back-edge found" commentary.
- If `summary.has_cycles == true`, above-diagonal cells are cycle edges. Flag them explicitly,
  name the SCC each belongs to (from `strongly_connected_components`), and recommend the SCC as
  the untangling target.

This scan is the cheapest possible self-check. Skipping it is exactly how this skill came to
exist.

### 6. Report from the `summary`, not just the matrix

Lead the writeup with the structural facts:

- `has_cycles` outcome (and SCCs, if any).
- `density` — one number, one sentence on sparse vs. tightly coupled.
- Heaviest edge (`max_edge_weight`) and its endpoints — the structural hotspot.
- Densest column = the hub the rest of the system depends on.
- Densest row = the top-of-stack consumer.
- Empty rows = leaf / foundation modules.

Then the matrix itself, then any architectural callouts (e.g. a module whose role disagrees with
its name).

## Anti-patterns to avoid

- ❌ Looping `aggregated_outgoing` over the node set to build the matrix. One
  `pairwise_dependencies` call replaces N of those and includes the SCC/topo analysis for free.
- ❌ Hand-picking the row order from module names. The `*.testfwk → main module` direction is
  not guaranteed; sometimes a "testfwk" module is itself a low-level helper consumed by the main
  module. Always trust `topological_order`.
- ❌ Claiming "no cycle" without quoting `summary.has_cycles`. The matrix can look clean and
  still contain a cycle if your ordering happens to place SCC members in the right order.
- ❌ Ignoring `strongly_connected_components` when `has_cycles == true`. The SCC list names
  exactly what to untangle.
- ❌ Skipping the above-diagonal scan in step 5. If `has_cycles == false`, that scan must come
  back empty before you publish the matrix.
- ❌ Treating "back-edge" as a finding when `has_cycles == false`. There is no back-edge in an
  acyclic graph; what you're seeing is a rendering bug.

## When NOT to use this skill

- "What depends on `Foo`?" — single-target blast radius, use `aggregated_incoming` directly.
- "What does `Foo` depend on?" — single-source fan-out, use `aggregated_outgoing` directly.
- One pair: "Does `A` depend on `B`?" — `dependency_between`.
- Code-level evidence beneath a known aggregated edge — `detail_dependencies`.
- Tree exploration ("what's inside this module?") — `list_descendants`.
- Planning a module split or carve-out — use the **cartograph-extract** skill instead; it does
  *projected* cycle analysis on a proposed split, which `pairwise_dependencies` cannot.
