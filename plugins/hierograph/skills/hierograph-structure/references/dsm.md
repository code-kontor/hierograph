# DSM rendering — matrix, layering, cycle detection

You arrive here with the node set and the `pairwise_dependencies` result already
in hand (SKILL.md steps 1–2). This reference covers only the matrix-specific
rendering and its self-check.

## Why this rendering has a trap worth naming

The natural mistake is to build the matrix by calling `aggregated_dependencies`
once per module and ordering the rows by your own guess (e.g. "`*.testfwk`
modules go after their main module"). That sometimes creates apparent back-edges
that don't exist — your row order simply isn't a valid topological ordering. In a
cyclic graph it can do the opposite and *hide* a real cycle by luck of placement.
This is not hypothetical: a DSM was once published with three above-diagonal
entries dismissed as "back-edges to investigate" when the graph was in fact
acyclic and the row order was wrong.

The `summary` from step 2 already computed the authoritative ordering and cycle
status. Render from it; do not re-derive it.

## Render using `topological_order`, reversed

The tool returns `topological_order` as `[consumer, …, sink]`. **Reverse it** so
sinks (base modules) sit at the top and consumers at the bottom. Convention:

- Rows = source (depender); columns = target (depended-upon).
- Cell = edge weight; `·` = no dependency; `■` = diagonal.
- Short labels — the rightmost path segment is usually enough.

With a reversed topological order on an acyclic graph, every edge falls **on or
below** the diagonal. That clean triangle *is* the signal of clean layering.
(Leaving the order unreversed gives an upper-triangular matrix — also valid; pick
one convention and hold it within a single answer.)

## Verify before reporting — the cheap self-check

Scan the rendered matrix for any cell on the wrong side of the diagonal (above,
if you reversed per above):

- **`has_cycles == false` and you see one → your rendering is wrong, not the
  data.** You picked the row order incorrectly or transcribed an edge into the
  wrong cell. Re-render before reporting; do not publish "interesting back-edge
  found" commentary — there is no back-edge in an acyclic graph.
- **`has_cycles == true` → wrong-side cells are the cycle edges.** Flag them
  explicitly, name the SCC each belongs to (from `strongly_connected_components`),
  and recommend the SCC as the untangling target.

Skipping this scan is exactly how the war story above happened. If
`has_cycles == false`, the scan must come back empty before you publish.

## Report

Lead with the structural facts (the shared list in SKILL.md), then the matrix,
then callouts. For a matrix specifically, the densest column is the hub the rest
of the system rests on, the densest row is the top-of-stack consumer, and empty
rows are the foundation/leaf modules.

## Anti-patterns

- ❌ Looping `aggregated_dependencies` / `outgoing_dependencies` over the node set
  to assemble the matrix. One `pairwise_dependencies` call replaces N of those and
  includes the SCC/topo analysis for free.
- ❌ Hand-picking the row order from module names. A "testfwk" module is sometimes
  a low-level helper consumed by the main module, not a downstream of it. Trust
  `topological_order`.
- ❌ Claiming "no cycle" without quoting `summary.has_cycles`. A clean-looking
  matrix can still hide a cycle if your ordering placed SCC members in a lucky order.
- ❌ Ignoring `strongly_connected_components` when `has_cycles == true` — that list
  names exactly what to untangle.
- ❌ Treating a wrong-side cell as a finding when `has_cycles == false`. It is a
  rendering bug, not a back-edge.
