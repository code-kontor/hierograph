# Design note: whole-graph / boundary dependency rollups in hierograph

## Context

Ranking the heaviest-used external types/packages (Neo4j driver, `java.util`, Spring,
…) exposed a gap in hierograph's query model. The natural question —
**"for these targets, what is the total dependency weight coming in from the whole
codebase?"** — has no clean answer today:

- `aggregated_dependencies(sources, targets)` forces the caller to enumerate every
  source (e.g. all 10 internal modules) and then **sum the pairwise edges by hand**.
  That is error-prone; the only safety net was that the package totals happened to sum
  to the External incoming weight (3908) computed independently from the DSM.
- There is no single **"all internal code"** handle — the 10 Maven modules all sit at
  `parent_id: -1`, with no umbrella node to address them collectively.

Two independent design ideas came out of this. They are **orthogonal** and both worth
recording:

1. **Hierarchy** — give the graph `Internal` / `External` umbrella nodes.
2. **Query API** — let dependency queries roll up "from anywhere" without combinatorial
   blow-up.

This note covers both, and the constraint that governs them.

---

# Part 1 — Hierarchy: `Internal` / `External` umbrella nodes

## The proposal and the tension

The idea: collapse the top level to two nodes, `Internal` and `External`. The *need*
behind it is real — a single "all internal code" handle would have removed the
hand-enumeration of module IDs. But "**only** two top-level nodes" is the wrong shape,
because two questions want **opposite** granularities at the top level:

- **"External coupling"** wants a coarse handle: one `Internal` node so
  `aggregated_dependencies([Internal], [External])` is a single clean call.
- **"Top-level DSM"** wants the 10 modules *as* the top level — that is the matrix that
  revealed the layering. Collapse them under one node and a top-level DSM degenerates
  into a useless 2×2 (`Internal → External`); you would have to drill down every time
  to recover what is free today.

So flattening to exactly two nodes optimizes the painful query and pessimizes the one
that worked well.

## Recommendation

**Add an `Internal` parent over the existing modules — do not replace them.** One extra
level, not "only two":

```
Internal (umbrella)          External (already exists, :Virtual:Artifact)
 ├─ boltclient                ├─ java.util
 ├─ core.model                ├─ org.neo4j…
 ├─ …                         └─ …
 └─ mcp.server
```

This gives the one-hop "all internal → external" source node that is missing today,
**and** keeps the module-level DSM granularity (query the children of `Internal`), with
clean symmetry against `External`.

### Two refinements

1. **Make `Internal` the real Maven reactor root, not a synthetic node.** There *is* a
   real aggregator (`io.hierograph` / `hierograph-parent`); the projection currently
   drops it and surfaces each module at `parent_id: -1`. Reattaching modules under the
   real root is more honest than inventing a node and stays consistent with the scanned
   model. (Contrast: `External` is legitimately synthetic because no artifact was
   scanned for it — that is where a virtual node is justified.)
2. **Give `External` sub-structure by library.** It is currently a flat bag of
   packages. Grouping it into `org.neo4j`, `org.springframework`,
   `com.fasterxml.jackson`, etc. as child "artifacts" would turn package-level ranking
   into a child-listing instead of manual summation, and close the asymmetry (nested
   `Internal` vs. flat `External`).

---

# Part 2 — Query API: rolling up "from anywhere" without blow-up

## The governing constraint

The `source × target` parameters on `aggregated_dependencies` exist to bound the
**result set** to `|S| × |T|` edges (capped at 2500). The catastrophic query is **open
on *both* sides at once**: all-nodes × all-nodes = the full DSM = O(N²) edges, which
hits the ceiling on mid/large graphs. Preventing that is the whole point of the two
required parameters.

The clarifying distinction:

- **Open on one side only** (e.g. universe source, explicit targets): result is
  `1 × |T| = |T|` — bounded by the caller's explicit list, **no** cartesian product.
- **Open on both sides**: O(N²) — must stay forbidden.

So the hard rule, regardless of any feature added: **never allow a query open on both
source and target at once.**

## Option A — a "whole graph" sentinel at the aggregated layer

Let `aggregated_dependencies` take a sentinel source meaning "the whole graph", e.g.
`aggregated_dependencies("*", [targets…])`. Surface options: omit the source arg; a
reserved string (`"*"`); or an explicit `source_scope` enum. (Do **not** reuse `-1` —
`parent_id: -1` already means "root/no parent"; overloading it is a landmine.)

But the semantics get subtle:

- **Double-counting.** The sentinel can't expand to "all node IDs" — subtrees overlap
  (module ⊃ package ⊃ type), so the same underlying type-edge would be counted under
  several source subtrees. It must mean a **single universal source subtree**, counting
  each underlying edge exactly once.
- **Universe vs. complement.** "Everything" includes the target's own subtree, so a
  target's internal self-references count as "incoming." For an honest ranking you want
  **complement of the target** (everything except the target subtree), per target.

All of this complexity is an **artifact of forcing a single-sided question through a
pairwise API.** A one-sided sentinel does not reintroduce N² in the *result*, but it
trades a bounded indexed pair-lookup for a **full edge scan, O(E)** — linear, the cost
of computing one *column* of the DSM. Columns are cheap; the full matrix is what
explodes.

## Option B (the simple one) — do it at the core dependency layer

This never needed aggregation. Implement it at the **core dependency layer**, where the
operation is naturally bounded:

```
incoming_dependencies(targetIds)        // depender side omitted = "from anywhere"
```

Why this is the right home:

- **Degree-bounded, no pairs.** The core layer has real edges, not synthetic pairs. The
  number of edges terminating in a target set is just its in-degree:
  `|result| = Σ in-degree(target) ≤ E`. For External that is the ~634 incoming edges we
  already saw. No cartesian anything; paginated like any edge listing.
- **Clarifies the params.** The two subtree params on `incoming`/`outgoing` are for
  **scoping**, not explosion-prevention. Dropping the filter on one side just means
  "don't scope the depender" — always safe.
- **No aggregation needed.** Type-level edges already carry a baked-in `weight` (the
  scanner pre-aggregates member references into the type→type edge). Grouping listed
  edges by endpoint is a *group-by*, not a query-time aggregation.
- **The rollup already exists.** The `incoming_dependencies` summary already returns
  `by_target_nodes: [{ node, aggregated_weight }, …]`. So
  `incoming_dependencies(externalTargets)` with no source could return the per-type /
  per-package weight ranking directly — the exact table built by hand — with no new
  concept, sentinel, or universe/complement semantics.

### The one caveat

The raw edge list is unambiguous (each edge appears once, with its real source). The
only place double-counting can sneak in is a **rollup over overlapping target subtrees**
(e.g. passing both `java.util` and `java.util.concurrent`). That is a
target-list-hygiene rule — keep the target set disjoint — identical to the existing
aggregated tool, and easy to document or detect-and-warn. The edge enumeration itself is
never wrong.

## Comparison of the query approaches

| Approach | Bounded? | New semantics needed | Verdict |
|---|---|---|---|
| Sentinel at aggregated layer | result yes, but O(E) scan | universe-vs-complement, double-count handling | Overcomplicated — fakes a "universe source" to fit a pairwise API |
| `incoming_dependencies(targets)`, depender optional | yes, degree-bounded | none (reuses `by_*_nodes` rollup) | **Best fix for this question** — minimal change, lives where the bound is free |

---

# How the two parts fit together

The hierarchy idea and the query-API idea solve **different** needs and are
complementary:

- **Core-layer `incoming_dependencies(targets)`** is the best answer for *global*
  rollups ("from anywhere"). Minimal change, degree-bounded, no new abstractions.
- **`Internal` / structured `External` umbrella nodes** are the answer for *scoped*
  rollups that a global listing can't express — "all internal except test modules",
  "per-library external coupling". With `Internal` as a real node,
  `aggregated_dependencies([Internal], [External])` is a `1 × 1` query that lives
  entirely inside the existing bounded model, no special path needed.
- The **aggregated-layer sentinel** is dominated by both and not recommended; it only
  reintroduces partition/complement complexity.

If only one thing is built first, the **core-layer optional-depender query** is the
cheapest, highest-leverage fix for the immediate ranking pain. The umbrella nodes are a
larger, independently valuable change for scoped analysis.

Overriding invariant for any of these: **never allow a query open on both source and
target at once.**
