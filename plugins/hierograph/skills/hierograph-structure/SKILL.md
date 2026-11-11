---
name: hierograph-structure
description: Analyse and visualise the dependency structure among a set of modules (or packages, services, types) with hierograph — render a Dependency Structure Matrix (DSM), draw an interactive layered dependency graph (core at the bottom, every arrow pointing down, click-to-trace, coupling weights, filters), and answer layering / cycle-detection questions. Use this whenever the user wants to see, explore, diagram or "get a picture of" how the parts of a codebase depend on each other; asks for a dependency graph, module graph, architecture overview, coupling map, DSM or dependency structure matrix; asks "is X properly layered", "are there cycles", "what's the structure of these modules", or "which modules are core / most depended on"; or wants to look at the system by multi-module, layer, bounded context or target architecture — a hierograph view, which this skill uses and proposes. Reach for it even when they only say "show me the structure" or "can I see the dependencies" without naming a chart. Do NOT use for single-target blast-radius questions ("what depends on X" / "what does X depend on" — query hierograph's incoming/outgoing/aggregated_dependencies directly), one-off pair checks, planning a module extraction (that is hierograph-extract), or first-time hierograph setup (that is hierograph-bootstrap).
---

# hierograph-structure — DSM, layering, cycles, and the layered graph

A DSM and a layered dependency graph are **two renderings of one analysis**: take
a set of modules, compute the dependency structure among them once, then draw it
either as a matrix or as an interactive layered picture. The matrix reads best
for comparing many pairs at a glance or answering a layering/cycle question in
text; the graph reads best for exploring more than ~20 nodes or handing someone
something they can click around in.

Both can also run **in a view**: a regrouping of the hierarchy the server keeps —
multi-modules, layers, contexts, a target architecture — whose groups are nodes the
tools accept like any other. A group-level DSM is then one call, and the layered
graph's boxes come from the view. See `references/views.md` for using a view and for
proposing one.

Because the expensive, error-prone part — resolving the node set and reading the
dependency digest correctly — is shared, do it **once, here**, and only then pick
the rendering. Steps 1 and 2 below are common to both; step 3 routes you to the
rendering-specific reference.

## When a different shape is better

- **One specific pair, or two or three nodes.** Just say it in prose; no render.
- **"What depends on `Foo`?" / "What does `Foo` depend on?"** — single-target
  blast radius, not a structure. Query hierograph directly:
  `incoming_dependencies` / `outgoing_dependencies` for one pair,
  `aggregated_dependencies([sources], [targets])` for many.
- **Planning to split or extract a module** — that is a *proposed-split* analysis
  with its own cycle-projection traps. Use **hierograph-extract**, not this.
- **hierograph isn't running yet** — set it up first with **hierograph-bootstrap**.

## Step 1 — Choose the node set

Resolve the things of interest to concrete hierograph node IDs:

- **Top-level modules:** `graph_overview()` → use the IDs in the `hierarchy`
  array (each entry is a top-level `java.module`, plus its degree counts).
- **Children of a scope:** `list_children(<scope-id>)` — the *direct* children
  only; do not recurse for this step.
- **A user-named group:** `find_node(<name>)` for each.
- **The groups of a view:** `list_views()`, then `graph_overview(view = "<name>")`
  — its `hierarchy` is the view's top level, groups and ungrouped modules. Pass the
  same `view` to every later call (`references/views.md`).
- **"By layer / by multi-module / by context", and no view fits:** propose one first
  (`references/views.md`, *Propose a view*), then come back here with its groups.

If the user said "modules" without scoping, default to **all** top-level projects in
`hierarchy`. **Read the resolved set back to the user before continuing** — the
set is the one decision that silently changes every later number.

**Never leave a module out without asking.** If you intend to drop anything from
the set — test modules (`… (test)`), test tooling, thin `*-api` modules, modules
with no compiled output, anything you consider noise — you **MUST** first name
exactly which modules you would drop and why, and get the user's explicit
permission. Do not decide "main modules only" on your own, and do not present a
reduced set as if it were the whole system. Only hierograph's synthetic
**"External Types"** node is not a module of the codebase; say that you omit it.

If the proposed set contains both a parent and one of its
descendants, surface that before running anything — the strict reading can give
an empty result (hierograph does not aggregate child edges into a parent–child
pair), the permissive reading mixes hierarchy with real coupling; let the user pick.

## Step 2 — One `pairwise_dependencies` call, then read the digest

`pairwise_dependencies` returns the whole structural digest — density, cycle
status, strongly-connected components and a topological order — in one call. This
is the heart of both renderings: **never** re-derive it by looping
`aggregated_dependencies` per row and ordering rows by a naming guess. That is how
you fabricate back-edges that look real (see `references/dsm.md` for the war story).

```
pairwise_dependencies(nodeIds = [<all IDs from step 1>],   # 2–50 nodes
                      minWeight = 1, edgeSort = "weight_desc", limit = 50)
```

**Read the `summary` block first.** It reports `node_count`, `edge_count`,
`possible_edges`, `density`, `has_cycles`, `strongly_connected_components` and
`topological_order` in a few hundred bytes. For a pure "is this properly layered?"
or "are there cycles?" question, **the summary alone is the answer** — lead with
it, and offer a rendering only if the user wants the picture.

Then fetch the edge list, sized from `edge_count`:

- If `edge_count` is under the server cap (**200**), set `limit` to cover it all
  and take the whole list in **one call** — no cursor. Sizing `limit` to the edge
  count is the common case; a small graph never needs pagination.
- Only when `edge_count` exceeds the cap do you page `next_cursor`, and then the
  continuation call must **repeat every original argument verbatim** (`nodeIds`,
  `minWeight`, `edgeSort`, `limit`) alongside the `cursor`, or the server rejects
  it as a stale-cursor mismatch.

There is no `max_edge_weight` in the summary — if you need the heaviest edge for
the writeup, scan the `edges` array yourself (`max(edges, key=…["weight"])`).

## Step 3 — Pick the rendering

| The user wants… | Go to |
|---|---|
| a matrix / DSM, a text layering or cycle verdict, to compare many pairs at once | **`references/dsm.md`** |
| an interactive picture to explore, >~20 nodes, "a picture of the structure", something clickable/publishable | **`references/layered.md`** |
| the structure by multi-module, layer or context; a view to use, or to propose | **`references/views.md`**, then one of the two above |

Read only the one you need. Both start from the node set and digest you already
have; neither re-queries the structure.

## Reporting (either rendering)

Lead the writeup with the structural facts from the `summary`, not with the
picture. In a view, name the view and whether its report has errors first — an
analysis of a partly applied view is an analysis of a different grouping.

- **`has_cycles`** outcome, and the SCCs if any — name them as the untangling target.
- **`density`** — one number, one sentence on sparse vs. tightly coupled.
- **Heaviest edge** (from the `edges` array) and its endpoints — the hotspot.
- **The hub** — the most depended-on node (densest column / highest incoming weight).
- **Top-of-stack consumer** and **foundation/leaf** modules.
- Any module whose structural role disagrees with its name.

Then the rendering, then architectural callouts.
