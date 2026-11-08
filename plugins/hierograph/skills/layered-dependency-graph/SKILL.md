---
name: layered-dependency-graph
description: Build an interactive, self-contained HTML page showing dependencies between modules (or packages, services, tables) as a layered graph — core at the bottom, every arrow pointing down, with click-to-trace, coupling weights, filters and a table view. Use this whenever someone wants to see, explore, visualise, diagram or "get a picture of" how the parts of a codebase depend on each other, wants a dependency graph / module graph / architecture overview / layering diagram / coupling map, asks which modules are core or most depended on, or wants to publish DSM or hierograph results as something they can actually click around in. Reach for it even when they only say "show me the structure" or "can I see the dependencies" without naming a chart — a static diagram is almost never what they want for more than about 20 nodes.
---

# Layered dependency graph

Produces one HTML file, no build step and no server, that draws a dependency
graph in horizontal levels: level 0 at the bottom holds everything that depends
on nothing else, and every arrow points downward. That single property is what
makes the picture readable — a reader can see layering violations and hub
modules without tracing individual lines.

The page carries its own data inline, so it can be committed, emailed or opened
from disk years later and still work.

## When a different shape is better

- **Two or three nodes, or one specific pair.** Just say it in prose.
- **"Is X properly layered / are there cycles?"** — that is a question, not a
  page. Answer it directly; offer the page afterwards.
- **You need a matrix, not a picture.** A dependency structure matrix reads
  better for comparing many pairs at once.

## The four steps

### 1. Get the edges

Any source works as long as you end up with weighted, directed edges between
named things. With the **hierograph** MCP server available, that is:

- `graph_overview()` — gives every module's id, name and degree counts.
- `pairwise_dependencies(nodeIds, min_weight=1, edge_sort="weight_desc", limit=50)`
  — read the **summary first**: it reports density, cycles and a topological
  order in a few hundred bytes. Then page `next_cursor` for the edge list (keep
  `limit` small — default 25, server cap 200 — and walk the cursor).

Pass the full node set. Bound the *output* with `limit`, not the input.

Without hierograph, anything that yields `[from, to, weight]` works — a
`jdeps` parse, a Maven dependency tree, an import scan, or Hierograph's own
Maven plugin snapshot. Weight should mean "how much" (reference count), not
"how many files".

### 2. Write the input file

```json
{
  "title":    "Hierograph module dependencies",
  "project":  "Hierograph 0.2.0-SNAPSHOT",
  "scanned":  "2026-11-07",
  "source":   "Hierograph Maven plugin",
  "unit":     "modules",
  "nodes":    {"23815330338705": "hierarchicalgraph.core.model", "5968189658062703": "javaspec"},
  "edges":    [[5968189658062703, 23815330338705, 20]],
  "test_patterns": ["itest", "-test"]
}
```

`test_patterns` are plain substrings matched against the name; matches get a
dashed outline and can be filtered out in the page. Everything above `nodes`
and `edges` is optional — the page falls back to sensible wording.

### Pinning a group onto one layer

To assert that a set of nodes belongs on a single band — an API layer, a
persistence layer, a set of facades — add:

```json
"pin_groups": [
  {"name": "Libs", "names": ["object-mapper", "processing"], "exclusive": true},
  {"name": "API",  "patterns": ["-api"], "exclusive": true}
]
```

A group matches by `names` (exact) or `patterns` (substring), and **the first
group in the list wins**, so order is precedence. Use `names` when membership
comes from something the name does not encode — a source folder, an owning team
— because a substring pattern would quietly capture the wrong modules. Resolve
the folder to a module list yourself before writing the input; `libs/rd30-api`
looks like an API module and is not one.

Members are contracted to one node before levels are assigned, which forces
them onto one band while keeping everything else packed as tightly around them
as the edges allow. The band is then labelled with the group's name, and its
members get a distinct fill.

`place` overrides the computed depth and stacks a group above everything else,
lowest value nearest the rest of the graph — `{"name": "Tests", "place": 2}` with
`{"name": "Adapters", "place": 1}` puts tests on top and adapters directly below
them. Reach for it when a band's position is a claim about the architecture
rather than something the edges imply: "tests sit above everything" is true by
intent, and the longest path has no reason to agree. Check first what else
depends on the group you are lifting — anything left below it becomes an upward
violation, which is reported but is usually a sign the placement was wrong.

`exclusive` reserves the band: non-members that would have landed on it move up
one level, so the group gets a row to itself. This is usually what someone means
by "an API layer" — a band you can point at and say *that is the API surface* —
and without it the layer quietly fills up with whatever else shares that depth.
Members never move, so the shift cannot turn a downward edge into a violation;
it only separates nodes that were already sitting flat beside each other. The
script reports how many nodes it moved and how many had been sharing the band.

This is the useful part: **pinning can contradict the dependencies**, and the
script does not hide that. If a non-member sits between two members in the
dependency order, it gets pulled onto the same band, and the edges that no
longer point downward are counted, listed in the script's output, and drawn in
red on the page — solid for an upward edge, dashed for one that stays within a
layer.

That makes the layer a hypothesis you can test rather than a decoration. Zero
violations means the grouping is real; a handful names exactly which
dependencies break it. Report the number either way — "the API modules form a
clean layer" is a finding, and so is its opposite.

Edges *inside* a pinned layer are counted separately and drawn dotted in the
normal coupling colour, not red. Collapsing a group onto one band makes its own
internal edges horizontal by construction, so flagging them as violations would
cry wolf. Their count is still worth reading: a layer with no internal edges is
a set of independent siblings, one with many is a cluster that happens to share
a depth.

**Two groups with no edges between them can be stacked in either order.** The
level assignment only constrains a layer relative to what it actually depends
on. When you pin several groups, check whether edges run between them before
describing the picture as "A sits above B" — if none do, that vertical order is
an artifact of the layout, and a reader will wrongly infer a dependency from it.
Say so.

Leave out synthetic nodes such as hierograph's "External Types", and drop
modules with no compiled output. They add a row of disconnected boxes that
tells the reader nothing.

### 3. Build

```bash
python3 scripts/make_data.py input.json graphdata.json
python3 scripts/build_page.py graphdata.json /path/to/output.html
```

`make_data.py` computes each node's level as the longest dependency chain
beneath it, counts degrees and coupling totals, flags test nodes, and detects
cycles. It prints the node/edge/level/cycle counts — read that output. If it
reports cycles, say so: levels are still assigned (the cycle is condensed to a
single level and its members get a red outline) but "every arrow points
downward" stops being true, and that is worth telling the user about.

### 4. Check it, then hand it over

Open the file and look at it once. The things that actually go wrong are the
initial view being unreadable, a control that does nothing, and labels sitting
outside their boxes. Then give the path.

**Do not publish this to claude.ai unless the user asks.** Dependency graphs
expose internal architecture and module names. Default to a local file; a
published page is a decision for the user, not a convenience for you.

## What the reader gets

- Click any node to trace it: dependencies highlight in orange below,
  dependents in aqua above, and the side panel lists both with exact weights,
  heaviest first. Every name there is clickable, so the graph can be walked.
- Search, a minimum-coupling slider that thins incidental edges, a toggle that
  drops test nodes and relayouts without them, and a dim-unrelated toggle.
- A full-screen button, zoom and pan, hover tooltips, and a table of every
  edge sorted by weight.

## Encoding decisions worth keeping

These are deliberate; change them only with a reason.

- **Coupling is a magnitude, so it gets one blue ramp light-to-dark**, not
  assorted hues. Violet and green are reserved for the two directional roles and
  appear only on selection; red is reserved for status and never used as a role.
  Both roles are named in the legend, and test nodes are dashed, so neither
  distinction rests on colour alone.
- **Direction is drawn, not merely coloured.** Highlighted and violating edges
  carry an arrowhead. Without one, an edge's direction is only implied by the
  role colour it takes on selection — so the moment a violation turns red, the
  identical line reads as "depends on" from one end and "is depended on by" from
  the other, and only one of those is true. The head settles it from either end.
  Scale the head with the stroke (`markerUnits="strokeWidth"`): a fixed-size head
  is a few pixels at the zoom that fits a whole graph — present in the DOM,
  invisible on screen — and that is exactly the zoom where direction matters. The
  node at the other end is tinted by role as well, so direction still reads when
  the edge itself has given up its colour to red.
- **Edges attach to the side they actually leave and arrive on.** Normal edges
  run down, violations run up, edges inside a layer run across. Using the
  downward attachment for all three drives the upward and flat ones through
  their own boxes and buries the arrowhead inside the node.
- **Node order within a level comes from barycentre sweeps**, not the name. The
  point is fewer crossing edges. Never hand-pick an order — with a layered
  drawing, a chosen order invents apparent structure that is not in the data.
- **Labels hide below a zoom threshold.** At a zoom that fits 78 nodes, text
  renders about four pixels tall and turns to noise. The overview shows shape;
  names arrive on zoom.
- **A violation keeps its red under selection, and gets weight instead of hue.**
  Selecting a node recolours its edges to the two directional roles, which would
  repaint a red violation and hide the finding exactly when someone is inspecting
  the node it belongs to. So red wins — and because red then costs the edge its
  out/in colour, stroke weight has to carry "this one is yours": a selected
  violation is drawn far thicker than an unselected one, not a hair above it.
  Direction still reads from the node outlines and the side panel. Weight is the
  selection's to spend, though: a violation that does *not* touch the selected
  node drops back to its own coupling weight, so the only heavy red lines are
  the ones the reader is actually inspecting. A minimum weight for violations
  applies only when nothing is selected, where there is no competition and the
  point is simply that they can be spotted.

## Things that bite

- **Fit to the rendered bounding box** (`root.getBBox()`), not to computed
  layout numbers — they drift apart as soon as anything about the layout
  changes, and the graph ends up clipped.
- **Refit from a `ResizeObserver`**, not a timer after `fullscreenchange`. A
  timer can fire before the browser has finished resizing.
- **A standalone file needs `<meta charset="utf-8">`.** `build_page.py` adds
  it. Without it every arrow and middot in the page renders as mojibake.
- **Screenshots may be downscaled** on a HiDPI display. Before "fixing" a
  clipped-looking graph, read the real geometry from the page instead — an
  element's `getBoundingClientRect()` against the canvas width settles it in
  one call, where eyeballing a scaled image does not.

## Files

- `assets/template.html` — the page. A fragment with a `__DATA__` placeholder
  and no `<html>`/`<head>` wrapper, so it can serve as an artifact body too.
  Palette tokens sit at the top; both light and dark are defined explicitly.
- `scripts/make_data.py` — edges in, page data out. Levels, degrees, cycles.
- `scripts/build_page.py` — inlines the data and wraps it into a real document.
