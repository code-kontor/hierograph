# Cartograph: `pairwise_dependencies` Specification

This is the complete specification for `pairwise_dependencies`, the **Dependency Structure Matrix (DSM)** tool of the hierarchical tier. It serves the "how are these modules internally coupled?" question — given a node set (typically siblings, layers, or a user-specified group), it returns all pairwise aggregated dependencies among them as an edge list, plus server-computed structural insights: density, cycle presence, strongly connected components, and topological order.

This specification builds on conventions established in `mcp-tools.md` (NodeRef, node IDs, the type-level aggregated-dependency model, limits, JSON precision) and the architectural framing in `cartograph-architecture-overview.md` (the hierarchical/detail/code layer model). It is the *all-pairs* counterpart to `dependency_between` (single-pair), `outgoing_to` / `incoming_from` (one-to-many), and `aggregated_outgoing` / `aggregated_incoming` (one-to-all-within-scope).

## Purpose

`pairwise_dependencies` returns the aggregated dependencies *among* a given set of nodes — every directed pair within the set — plus a server-computed structural summary that tells the LLM what kind of dependency graph it has on its hands.

This is the **DSM tool**. The terms that should pattern-match to this tool include: *dependency structure matrix*, *DSM*, *module coupling matrix*, *coupling matrix*, *layering analysis*, *cycle detection across a module set*, *all-pairs coupling*, *internal coupling*, *architecture assessment*.

The fundamental promise is one call instead of N² calls. For a set of 68 top-level modules, the alternative — looping `aggregated_outgoing` (or `dependency_between`) over every pair — is 4,624 round-trips of redundant work, since each call independently traverses the same in-memory subtrees. This tool does the full pairwise computation in a single in-memory pass over the existing aggregated-dependency model, then layers cycle detection and topological sort on top via the existing `IDependencyStructureMatrix` algorithm.

The output is shaped for two consumers: an LLM that wants the structural digest (density, cycles, SCCs, topological order) and a downstream renderer or post-processor that wants a clean edge list keyed by node id.

## Parameters

```
pairwise_dependencies(
    node_ids: long[],                // required: the node set to analyze
    include_self_loops: bool = false // include internal coupling within each node's subtree
)
```

### `node_ids` (required)

The list of node IDs forming the analysis set. Order in the response follows the DSM-computed order (which is topological when acyclic), not input order.

Typical sources:
- The IDs of the children of a parent node (from `list_children`) — e.g., the top-level projects under root, or the packages within a module. This is the canonical "layering analysis" call.
- A user-specified group of modules under investigation for extraction or layering.
- The result of a `find_descendants(kind: ...)` query — e.g., all service classes within a subtree — for finer-grained coupling analysis.

Each node is treated as a *subtree*: an aggregated edge from node A to node B exists if any descendant (leaf-level core) dependency from A's subtree reaches B's subtree. This matches the semantics of `dependency_between` and `aggregated_outgoing`.

There is no hard cap on `node_ids.length`, but practical limits apply:
- 10-100 nodes is the sweet spot. Response stays inline; DSM is comfortably readable when rendered.
- 100-500 nodes is feasible but the response approaches the inlining budget. Consider whether the question really needs all of them.
- Beyond 500 nodes, ask whether the question is better posed as "find a smaller relevant subset first" (e.g., via `aggregated_outgoing` from one focal module).

### `include_self_loops` (default `false`)

When `true`, the response includes edges from a node to itself — i.e., the *internal coupling within each node's subtree*. This is the count of core dependencies whose source and target both fall inside that node.

The default is `false` because the headline use cases (layering check, cycle detection, DSM rendering) are about *between-node* coupling. Self-loops are a separate signal — "how internally cohesive is each module?" — and including them inflates the edge list with values that aren't comparable to the off-diagonal entries (a self-loop weight reflects internal density, not coupling to a peer).

Set to `true` when the question explicitly involves internal coupling: "rank these modules by how internally entangled they are" or "find modules whose internal coupling dwarfs their external coupling."

## Response shape

```json
{
  "nodes": {
    "5625164": { "name": "core-api", "qualified_name": "com.example.core-api", "kind": "Project" },
    "5625163": { "name": "core-impl", "qualified_name": "com.example.core-impl", "kind": "Project" },
    "5625162": { "name": "transport", "qualified_name": "com.example.transport", "kind": "Project" }
  },
  "edges": [
    { "from": 5625163, "to": 5625164, "weight": 142, "kinds": ["calls", "extends", "of_type"] },
    { "from": 5625162, "to": 5625164, "weight": 38, "kinds": ["calls"] },
    { "from": 5625162, "to": 5625163, "weight": 5, "kinds": ["calls"] }
  ],
  "summary": {
    "node_count": 3,
    "edge_count": 3,
    "total_weight": 185,
    "max_edge_weight": 142,
    "density": 0.5,
    "has_cycles": false,
    "strongly_connected_components": [],
    "topological_order": [5625164, 5625163, 5625162]
  }
}
```

### Encoding: slim, id-referenced

Nodes are serialized **once** in a top-level `nodes` map keyed by ID. Every other reference to a node (edge endpoints, topological order, SCC members) is an ID, not an embedded NodeRef. This is the standard graph-data idiom (GraphML, Gephi, d3-force, NetworkX, JGraphT) and keeps responses inside the inlining budget for typical module-set sizes.

For a set of 68 modules with 387 edges, the slim form is roughly 60-70% smaller than the equivalent embedded form. The savings compound with edge count, since every edge would otherwise carry two full NodeRefs.

### `nodes` map

Keyed by string-form node ID (JSON object keys are strings). The value is a short NodeRef — `name`, `qualified_name`, `kind`. The full NodeRef shape (with `parent_id`, `parent_kind`) is omitted here because the parent context isn't usually relevant in a DSM view — when it is, the LLM can resolve it via other tools.

### `edges` array

A flat list of directed edges. Each edge has:

**`from`** / **`to`** — Node IDs (longs), keys into the `nodes` map.

**`weight`** — Aggregated weight: the count of core (leaf-level) dependencies that contribute to this aggregated edge.

**`kinds`** — Sorted list of the distinct core dependency types contributing to this edge (e.g., `["calls", "extends", "of_type"]`). Useful for distinguishing "structural" coupling (inheritance, field types) from "behavioral" coupling (method calls).

Self-loops (when `include_self_loops: true`) appear as edges with `from == to`. When `include_self_loops` is `false`, no self-loops appear regardless of internal weight.

Edges are emitted in DSM order: outer loop over `nodes` in topological/DSM order as source, inner loop as target. This produces a stable, deterministic ordering that mirrors what a DSM cell-by-cell reader would see. Edges with `weight == 0` are omitted.

### `summary` block

**`node_count`** — Length of the `nodes` map.

**`edge_count`** — Number of non-zero entries in the `edges` array. Includes self-loops only when `include_self_loops: true`.

**`total_weight`** — Sum of `weight` across all returned edges.

**`max_edge_weight`** — The largest single-edge weight. Useful for setting a sensible color scale or threshold when rendering the matrix; also flags the heaviest single pair-coupling for follow-up investigation.

**`density`** — Ratio of actual off-diagonal edges to possible off-diagonal edges: `non_self_edges / (n * (n - 1))`. Rounded to three decimal places. Self-loops are excluded from both numerator and denominator regardless of `include_self_loops`, because density is meant to characterize *coupling between distinct nodes*.

- Near `0.0`: sparse, well-layered.
- Around `0.1`: typical for well-architected module sets.
- Above `0.3`: tightly coupled; worth investigating for refactoring opportunities.
- Approaching `1.0`: nearly complete graph; the layering boundary is fictional.

**`has_cycles`** — Boolean. `true` if any directed cycle exists among the off-diagonal edges. A common layering check is `has_cycles == false` over the project's top-level modules.

**`strongly_connected_components`** — List of ID-lists. Each inner list is one non-trivial SCC (size ≥ 2): a set of nodes that all mutually reach each other and therefore participate in cycles together. Empty when `has_cycles` is `false`. When non-empty, the inner lists name the nodes that need to be untangled to restore acyclicity.

**`topological_order`** — List of IDs. Present only when `has_cycles` is `false`. Orders the nodes so that every edge points from an earlier node to a later one — this is the natural reading order of the DSM (and the order used for the `nodes` map and `edges` array).

When cycles exist, `topological_order` is omitted entirely (rather than included as `null`) — its absence is the signal that the graph isn't a DAG. The LLM can still reason about layering by reading the SCCs and the off-cycle edges, but it should not expect a global linear order.

### What the per-edge `in_cycle` flag does *not* do

An earlier draft of this tool emitted a per-edge `"in_cycle": true | false` boolean. It has been removed. The information the LLM actually wants about cycles is *which nodes participate in a cycle* — that's `strongly_connected_components`. Per-edge cycle membership added one boolean per edge (387 redundant `false` values on the acyclic case) and was almost never the answer to the question being asked.

If future use cases need to know *which specific edges close cycles*, the right shape is a single `summary.cycle_edges: [[from_id, to_id], ...]` list, populated only when `has_cycles` is `true`. Defer adding this until a concrete callsite demands it.

## Semantics

A few details worth being explicit about:

### Subtree-aggregated edges, not leaf edges

Each entry in `edges` is an *aggregated* edge — its weight counts every leaf-level core dependency that crosses from any descendant of `from` to any descendant of `to`. This matches the standard hierarchical model used throughout the type-level tier.

For *detail-level* (method/field) evidence underneath an aggregated edge identified by this tool, follow up with `detail_dependencies(from_id, to_id)`. For *type-level* evidence (which concrete type-pairs contribute), follow up with `outgoing_core_dependencies(from_id, to_id)`.

### "Pairwise" means *within the supplied set*

Edges are restricted to (source, target) pairs where both endpoints appear in `node_ids`. Dependencies from a node in the set to a node *outside* the set are not returned, and don't contribute to any weight. This is what makes the tool a DSM tool: the analysis frame is bounded by the set itself.

To investigate coupling that crosses the boundary of the set, use `aggregated_outgoing` or `aggregated_incoming` on individual members.

### Edge ordering

Edges are emitted in DSM row-major order: outer loop over `nodes` in DSM order (topological when acyclic) as source, inner loop as target. Within each (source, target) cell there is at most one edge.

This means a reader of the `edges` array sees the DSM matrix top-to-bottom, left-to-right. When the graph is acyclic, *all edges point from earlier to later nodes* — the upper triangle, by DSM convention — so a quick scan reveals layering at a glance.

### Self-loops semantics

When `include_self_loops: true`, a self-loop's weight counts core dependencies whose source and target are *both* descendants of the same node. This includes intra-subtree coupling at any depth — methods in one class calling methods in a sibling class, etc.

Self-loops are emitted but do not contribute to `density` (which characterizes inter-node coupling) and do not affect `has_cycles` or SCC computation (a node is not in a cycle with itself for layering purposes).

### Empty result

If none of the supplied nodes have aggregated dependencies among each other (a perfectly decoupled set), the response returns `edges: []`, `total_weight: 0`, `has_cycles: false`, `topological_order: <input order>`, and `strongly_connected_components: []`. The `nodes` map is still populated. This is a meaningful answer — "there's no coupling here" — distinct from an error.

### Single-node input

If `node_ids` has exactly one entry, there are no off-diagonal pairs. With `include_self_loops: false`, the response has empty `edges`. With `include_self_loops: true`, it has at most one edge (the self-loop). The tool doesn't reject this case — sometimes the LLM constructs the call programmatically from a filter that happens to yield a single node, and a clean empty response is friendlier than an error.

## Error cases

The tool returns a structured error in these cases:

**`NODE_NOT_FOUND`** — One of the supplied `node_ids` doesn't exist in the graph. The error names the offending ID.

**`EMPTY_NODE_SET`** — `node_ids` is empty or omitted. The error explains that the tool needs at least one node ID.

Pathological cases (huge sets, deeply nested subtrees) do not produce errors — they produce slow responses or, at worst, responses larger than the inlining budget. The LLM is expected to apply judgment about set size; the tool description spells out the practical range.

## Performance characteristics

`pairwise_dependencies` reads entirely from the in-memory hierarchical graph model — no Neo4j round-trip. The dominant cost is:

1. **DSM construction**: O(N²) calls to `getOutgoingDependenciesTo`, each O(1) over the precomputed aggregated-dependency index. For 100 nodes: 10,000 lookups; well under 100ms.
2. **SCC + topological sort**: Tarjan-style algorithm over N nodes and E edges, well under O(N²).
3. **JSON serialization**: linear in `edge_count` (after the slim encoding eliminates per-endpoint NodeRef bloat).

Empirical expectations:

- **Typical case** (10-50 modules): under 50ms total, response under 20KB.
- **Common case** (50-150 modules): under 200ms, response 50-150KB — stays inline.
- **Large case** (150-500 nodes): may exceed 1 second; response 200KB-1MB. Slim encoding keeps this manageable but reaching half a megabyte is a smell.
- **Pathological case** (1000+ nodes): consider whether the question is genuinely "all-pairs over 1000 nodes" or whether a smaller relevant subset would answer it.

The slim payload encoding is essential for the practical range. With embedded NodeRefs on both endpoints of every edge, a 68-module / 387-edge response measured 145,960 characters in real-world testing — over the inlining limit, forcing side-channel file spillover. The slim form reduces this to roughly 45-55k.

## Description for the tool registration

This is the text exposed to the LLM via MCP. It leads with the canonical user-vocabulary terms (DSM, coupling matrix, layering), gives a worked example, and includes anti-patterns that catch the most common wrong-tool decisions.

> [Hierarchical pairwise] **Use this for: dependency structure matrix (DSM), module coupling matrix, cycle detection across a module set, layering analysis, all-pairs coupling within a group of modules.**
>
> Given a set of nodes (typically siblings, layers, or a user-specified group), return all pairwise aggregated dependencies among them as an edge list, plus server-computed structural insights: density, cycle presence, strongly connected components, and topological order. This is the right tool when you need to understand the *internal coupling* within a group of nodes — one call instead of N² calls to `dependency_between` or `aggregated_outgoing`.
>
> **Example:** `pairwise_dependencies(node_ids=[<ids of top-level projects from describe_graph or list_children of root>])` → returns the DSM edge list, plus density, cycle status, SCCs, and topological order.
>
> Returns nodes once in a top-level `nodes` map keyed by ID; `edges` reference nodes by ID, not embedded copies (keeps the response inline for module-set sizes up to several hundred). The `summary` block carries the structural digest — for many architectural questions (cycle check, density, layering), the summary alone is the answer.
>
> Common use cases:
>
> - **Layering check**: `has_cycles == false` confirms the supplied set is properly layered. If `has_cycles` is `true`, `strongly_connected_components` names the nodes that need to be untangled.
> - **Coupling analysis**: `density` characterizes how tightly the set is coupled; `max_edge_weight` flags the heaviest single pair-coupling.
> - **Extraction feasibility**: low coupling between a candidate sub-group and the rest of the set suggests a clean extraction boundary.
> - **Architecture assessment**: `topological_order` (when acyclic) gives the natural reading order — the layering from base to top.
>
> When to use this vs. neighboring tools:
>
> - **Do NOT loop `aggregated_outgoing` or `dependency_between` over a node set to build a matrix.** Use `pairwise_dependencies` instead — one call, plus you get DAG/SCC analysis for free.
> - For a *single* (source, target) yes/no/weight check, use `dependency_between`. For *one source against many targets*, use `outgoing_to`. For *many sources against one target*, use `incoming_from`.
> - For the heaviest things a single module depends on (without specifying targets), use `aggregated_outgoing` (fan-out).
> - For the heaviest things that depend on a single module (blast radius), use `aggregated_incoming` (fan-in).
> - For drilling into the methods/fields that realize one aggregated edge identified here, use `detail_dependencies`.

## Integration with the broader workflow

`pairwise_dependencies` typically sits at the top of an architectural investigation, narrowing into more specific tools as questions sharpen.

**Workflow 1: top-level DSM.**

1. `describe_graph` → orient; see the top-level module IDs in the response.
2. `list_children(root_id)` → confirm the top-level module set.
3. `pairwise_dependencies(node_ids=[top-level module ids])` → the DSM in one call.
4. Read `summary.has_cycles` and `summary.density` first. If acyclic and dense, the layering is fine but the modules are tightly bound. If cyclic, dig into `strongly_connected_components`.
5. For any heavy edge of interest, `outgoing_core_dependencies(from, to)` → type-level evidence, then `detail_dependencies` → method-level evidence.

**Workflow 2: cycle investigation.**

1. `pairwise_dependencies(node_ids=...)` → notice `has_cycles: true` and an SCC of three modules.
2. For each pair within the SCC, `outgoing_core_dependencies(a, b)` → which types contribute to the back-edge.
3. `detail_dependencies(a, b)` on the offending pair → which methods or fields are responsible.
4. The result names the concrete call sites that close the cycle.

**Workflow 3: extraction feasibility.**

1. The user is considering moving a subset of modules into a separate library.
2. `pairwise_dependencies(node_ids=[candidates + neighbors])` over the candidate set plus the rest of the relevant context.
3. Read edges *between candidates* (the internal coupling of the proposed library) vs. edges *crossing the candidate boundary* (the future API surface).
4. If the boundary is narrow, extraction is feasible. If broad, more refactoring is needed first.

**Workflow 4: package-level DSM within one module.**

1. `find_node("com.example.coremodule")` → module ID.
2. `list_descendants(module_id, kind: "java.package")` → the package set.
3. `pairwise_dependencies(node_ids=[package ids])` → DSM at the package level within the module.
4. Same downstream reasoning as the top-level case, one level deeper.

In each workflow, `pairwise_dependencies` provides the structural frame; the more specific tools fill in the evidence.

## Implementation notes

A few specifics worth flagging during implementation.

**Use the existing DSM algorithm.** `GraphUtils.createDependencyStructureMatrix(nodes)` already implements DSM construction, SCC detection, and topological ordering over an `HGNode` collection. The tool is a thin adapter: resolve IDs to nodes, call the algorithm, project the results into the response shape.

**Slim encoding is non-negotiable.** Every node appears once in `nodes`; `edges`, `topological_order`, and `strongly_connected_components` reference by ID only. Embedding NodeRefs on edges inflates the response 3-4× and pushes typical module-set responses out of the inlining budget. The slim form is also the standard graph-data idiom — any post-processor handles it natively.

**`density` rounding.** Round to 3 decimal places. `0.085` is meaningful; `0.0853024129...` is noise that bloats JSON and adds nothing.

**`kinds` derivation.** The `kinds` array per edge is the sorted distinct set of `HGCoreDependency.getType()` values for the core dependencies contributing to the aggregated edge. Compute via a TreeSet during edge construction. Omit nulls.

**Self-loop weight.** When `include_self_loops: true`, the self-loop weight is the aggregated weight of the node-to-itself entry in the DSM. This naturally counts all core dependencies whose `from` and `to` are both descendants of the node — the existing DSM cell-weight logic handles this.

**No `in_cycle` per edge.** Do not emit a per-edge `in_cycle` boolean. The SCC list in the summary carries the cycle information at the granularity the LLM actually uses.

**Topological order omitted on cycles.** When `has_cycles: true`, omit `topological_order` from the summary entirely (not `null`, not `[]`). The absence is the signal.

**`nodes` map ordering.** JSON object key order is not guaranteed by spec, but most parsers and most readers preserve insertion order. Insert in DSM order (the same order as `topological_order`, when present). This makes the response visually scannable.

**Testing checklist:**

- Acyclic set (top-level modules of a well-layered project) — verify `has_cycles: false`, `topological_order` present and matches DSM ordering, density between 0 and 1.
- Cyclic set (deliberately construct one) — verify `has_cycles: true`, `strongly_connected_components` non-empty and lists the right nodes, `topological_order` omitted.
- Single-node input — verify empty `edges` with `include_self_loops: false`, at most one edge with `include_self_loops: true`, no error.
- Empty `node_ids` — verify `EMPTY_NODE_SET` error.
- One bad ID in `node_ids` — verify `NODE_NOT_FOUND` error naming the offending ID.
- Large set (≥ 100 nodes) — verify slim encoding keeps response under inlining budget; verify reasonable response time.
- Set where some pairs have multiple core dependency kinds — verify `kinds` array per edge is sorted, distinct, no nulls.
- `include_self_loops: true` on a set where some nodes are leaves with no internal subtree edges — verify those nodes simply have no self-loop edge (not a zero-weight edge).
- Mixed-kind set (e.g., one type-kind node alongside package-kind nodes) — verify it works; aggregated dependencies are kind-agnostic.
- Two unrelated subtrees with no inter-edges — verify `edges: []`, `total_weight: 0`, `density: 0.0`, `has_cycles: false`.
