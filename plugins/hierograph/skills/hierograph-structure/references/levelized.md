# Levelized structure map — nested boxes, rows by level

A levelized structure map (LSM) draws the containment hierarchy as nested boxes —
modules holding packages holding sub-packages — and arranges the children of every
box in **rows by level**: a box depends only on boxes in rows below it. The layering
is read from position alone, so the map draws no ordinary dependency at all. The
only arrows are the dependencies that **break** the levels, and the boxes that are
caught in a cycle sit together in a red **tangle** box. A clean map is all boxes
and no arrows; every arrow on it is a finding.

Use it when the question is about the structure *inside* modules — "how are the
packages of `server` layered", "where are the package cycles" — or for one picture
of the whole system down to packages. For module-level layering alone, the layered
graph (`references/layered.md`) shows more; for comparing many pairs, the DSM.

Paths are relative to this skill's directory; write intermediate JSON to a
scratchpad, not the project tree.

Contents: [What it shows](#what-it-shows) · [1. Collect the tree](#1-collect-the-tree)
· [2. Collect the edges](#2-collect-the-edges) · [3. Write the input](#3-write-the-input)
· [4. Build](#4-build) · [5. Check and hand over](#5-check-it-then-hand-it-over) ·
[Encoding decisions](#encoding-decisions-worth-keeping) · [Things that bite](#things-that-bite)

## What it shows

- **Rows are levels, per container.** Each container is levelized on its own
  children: every dependency between two subtrees is charged to the one pair of
  siblings it crosses (the children of the endpoints' lowest common ancestor), and
  a child's row is the longest chain of such sibling dependencies beneath it. Row 0
  is at the bottom. Order **within** a row means nothing, and the page says so.
- **Tangles.** Siblings that form a cycle share a row inside a red box. Inside it
  they are ordered so the heaviest dependencies point down (Eades–Lin–Smyth,
  weighted); the rest — the **feedback** dependencies — are drawn as dashed red
  arrows and listed in a table under the map. They are the cheapest set of
  dependencies whose removal would untangle the box, so they are the refactoring
  candidates, not merely "some edge in the cycle".
- **Feedback arrows join the siblings they break.** A feedback dependency is a fact
  about two siblings in a tangle, so its arrow joins those two boxes, however deep
  inside them the classes are. Drawing it between the leaves instead fanned one
  fact out into a dozen long lines through unrelated boxes (tried on DA/RE
  `ondemand`). The leaf-level detail is in the table, and selecting a box draws its
  own feedback dependencies from where they really start.
- **Interaction.** `+`/`−` collapse and expand any container; click a box to tint
  what it depends on (violet) and what depends on it (green), with arrows and a side
  panel listing both with weights; search reveals and highlights matches; the table
  of feedback dependencies is clickable.
- **Save SVG / Save PNG.** Redraws the whole map as it is currently expanded —
  including what is scrolled out of view, the selection and the arrows — as a
  standalone SVG, or as a PNG at twice the size. The PNG uses the system monospace
  font (an SVG drawn into a canvas cannot load web fonts).

## 1. Collect the tree

Decide the scope with the user first (SKILL.md step 1): one module, a few, or the
whole system. Then, per module:

```
list_descendants(nodeId = <module id>, kindFilter = ["packages"], limit = 200)   # page with next_cursor
```

That is every package with its `parent_id` in a few calls — **not** one
`list_children` per package. Every result becomes a `nodes` entry
(`id`, `parent` = `parent_id`, `name`, `kind`); the module itself is the entry with
`parent: null`. For the whole system, take the modules from
`graph_overview().hierarchy` and repeat per module; several roots are placed under
one synthetic root.

Chains of single-child namespace packages (`io` › `hierograph` › `server`) are
folded into one box by the script; do not fold them yourself.

**Leaves are what the edges are queried for**, and they must be disjoint
(`pairwise_dependencies` rejects a node together with one of its descendants). So
the leaves of the tree are:

- every package without sub-packages — it stands for its types;
- for a package that holds **both** sub-packages and types of its own
  (`direct_type_count > 0` on a package that also has children): its direct types,
  as one synthetic leaf — `list_children(nodeId = <pkg>, kindFilter = ["types"])`,
  then `{"id": "<pkg>:types", "parent": <pkg>, "name": "(types)", "kind": "types",
  "ids": [<type ids>]}`. Without it, those types' dependencies are silently lost.

**Size budget.** `pairwise_dependencies` takes at most 1,000 ids. Count the leaf
ids (types of synthetic leaves count one each) before querying. Over budget — or
over ~400 boxes, where the map stops being readable — cut the tree at a depth:
drop the packages below the cut, and a package at the cut becomes a leaf that
stands for its whole subtree. Tell the user where you cut.

**External Types.** Like everywhere in this skill, hierograph's synthetic
"External Types" node is not part of the codebase; leave it out unless the user
wants the libraries in the picture. If they do, add it as a root with its
`list_children` packages as leaves; it is drawn in blue at the bottom.

**In a view**, the groups are containers: take the tree from
`list_children(..., view = "<name>")` starting at the view's `hierarchy`, and pass
the same `view` to every call (`references/views.md`).

## 2. Collect the edges

One call over **all** leaf ids — every type id of a synthetic leaf individually:

```
pairwise_dependencies(nodeIds = [<all leaf ids>], edgeSort = "dsm", minWeight = 1, limit = 166)
```

Page `next_cursor` until done, repeating every original argument verbatim. Do not
filter with `minWeight`: a cycle needs every edge, and the summary does not tell
which light edges close it. Read the `summary` first anyway — `has_cycles` and the
SCCs over the leaves are already the headline.

Edges between leaves are all the script needs: it sums them up to every sibling
pair of every container itself.

**When the leaf-level edge list is too big.** A full page of 166 edges fills the
~50 KB response budget, so check `summary.edge_count` on the first page: past ~650
edges (four pages), switch to **one call per container** over its direct children
(plus the type ids of its `(types)` leaf) and pass all of those edges together.
Each sibling pair belongs to exactly one container, so nothing is counted twice,
and the levels, tangles and feedback dependencies come out identical. What is lost
is precision below the container: an arrow starts at the sibling box rather than
the class inside it, and a selection lists a box's dependencies only as far as its
own container's siblings — anything further out shows up on the ancestor that
crosses it. Say so when handing over.

## 3. Write the input

```json
{
  "title":   "io.hierograph.server — levelized structure",
  "project": "Hierograph 0.3.0", "scanned": "2026-11-13",
  "source":  "hierograph MCP (list_descendants, pairwise_dependencies)",
  "expand_depth": 3,
  "nodes": [
    {"id": 7802, "parent": null, "name": "io.hierograph.server", "kind": "java.module"},
    {"id": 9001, "parent": 7802, "name": "io", "kind": "java.package"},
    {"id": "9002:types", "parent": 9002, "name": "(types)", "kind": "types", "ids": [9101, 9102]}
  ],
  "edges": [[9101, 9002, 12]]
}
```

`expand_depth` is optional: containers deeper than it start collapsed. Without it
the page expands as deep as it can while showing at most ~160 boxes.

## 4. Build

```bash
python3 scripts/make_lsm.py input.json lsmdata.json
python3 scripts/build_page.py lsmdata.json /path/to/output.html assets/lsm-template.html
```

`make_lsm.py` prints every container with its rows (`L0` at the bottom), each
tangle with its members, and the heaviest feedback dependencies — **read that
output**; it is the writeup. It also reports edges it dropped: an endpoint that is
not in `nodes` means a leaf is missing from the tree, which is worth fixing rather
than mentioning.

## 5. Check it, then hand it over

Open the page once. What goes wrong: a container so wide its children shrink to
unreadable slivers (collapse it, or cut the tree), arrows that cut across labels,
and a default expansion that hides the tangles the stdout reported. Then give the
path, and report from the stdout:

- the tangles — which container, which members, and the feedback dependencies
  that untangle each, heaviest first;
- the container with the most rows (the deepest internal layering) and any
  container whose children are all on one row (independent siblings — fine — or
  never-used code);
- a package whose level contradicts its name (`util` above `service`).

**Do not publish this to claude.ai unless the user asks.** Like the layered graph,
it exposes internal architecture and package names.

## Encoding decisions worth keeping

- **Position carries direction; arrows carry only problems.** Drawing every
  dependency turns a nested map into a hairball. The level rule makes ordinary
  dependencies redundant, so only feedback dependencies (always) and the
  selection's dependencies (on click) get lines. Past 40 feedback dependencies the
  page starts with them hidden — the table and the selection carry them then.
- **Feedback, not "every edge in the cycle".** All edges inside a tangle are part of
  the cycle; marking all of them red says nothing. The weighted ordering picks the
  few that point up when the heavy ones point down.
- **Width follows content.** A box's share of its row grows with the number of
  boxes visible inside it, so an expanded container gets room and a collapsed one
  does not; a minimum width per box keeps names readable, with horizontal scroll in
  the map rather than squashed text.
- **Colours are roles, as in the layered graph.** Green-grey containers, cream
  leaves, red only for tangles and feedback, blue only for external code; violet
  and green only on selection.

## Things that bite

- **A missing synthetic `(types)` leaf** under a package with both sub-packages and
  types drops those types' dependencies without an error from the server — the
  script only sees fewer edges. Check `direct_type_count` on every container.
- **Two leaves in an ancestor relation** make `pairwise_dependencies` fail with
  `INVALID_PARAMETER`; a package at the depth cut must not also have its
  sub-packages in the leaf list.
- **Levels are per container.** Row 3 in one container and row 3 in another are not
  comparable; do not describe the map as having global layers.
- **Screenshots may be downscaled** on a HiDPI display; read geometry with
  `getBoundingClientRect()` before "fixing" an arrow that looks misplaced.

## Files

- `assets/lsm-template.html` — the page fragment with a `__DATA__` placeholder;
  `scripts/build_page.py` wraps it into a document (third argument).
- `scripts/make_lsm.py` — tree and edges in, levels, tangles and feedback out.
  Reuses the Tarjan implementation of `make_data.py`.

`lsmdata.json` (minified): `nodes[]` with `n` name, `k` kind, `p` parent index
(−1 = root), `x` external, `l` level in its container, `tg` in a tangle, `iw`/`ow`
in/out weight of the subtree; `layout` maps a container's index to its rows, top
row first, each item a node index or `{"t": rows}` for a tangle; `edges[]` are
`[from, to, weight]` between node indices, or `[from, to, weight, 1, sibA, sibB]`
for a feedback dependency, with the sibling pair it breaks; plus `tangles`,
`violations`.
