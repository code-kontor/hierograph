# ADR 0001: Slim Payload Encoding for Graph-Data MCP Responses

**Status:** Accepted

**Date:** 2026-05-20

**First applied in:** `pairwise_dependencies` ([spec](../tool-specifications/cartograph-pairwise-dependencies-spec.md), implementation in `mcp-spike/core-app/.../ReachabilityMcpTools.java`)

---

## Context

MCP tool responses are consumed by LLMs and pass through context windows and host-side inlining limits. Graph-shaped responses — anything that emits a list of nodes plus a list of edges between them — naturally repeat each node many times: once for each edge endpoint, plus once for each appearance in derived structures (topological order, strongly connected components, etc.).

The first concrete failure: `pairwise_dependencies` over a 68-module DSM produced a 145,960-character response. The underlying graph was small (68 nodes, 387 edges); the bloat came from serializing each node's full NodeRef as both endpoints of every edge, plus once inside the topological order, plus once inside each SCC. With three full appearances per node on average and 68 unique nodes, the response contained ~840 serialized NodeRef copies. Most of the bytes were duplicate `qualified_name` strings.

The response exceeded the host's inline-content budget and was spilled to a file, forcing side-channel handling for a graph that is not actually large. This is the wrong failure mode: a sensibly-sized analytical question should produce an inline answer.

The structural cause is encoding, not data volume. The same information fits comfortably inline when each node is serialized exactly once and all other references are by ID.

## Decision

For any MCP tool response whose payload contains a graph — multiple references to the same node (edges, paths, components, orderings) — we use **slim payload encoding**:

1. **A single top-level `nodes` map**, keyed by stringified node ID. Each entry holds the node's display fields once (`name`, `qualified_name`, `kind`).
2. **All other node references in the response carry IDs only**, not embedded NodeRefs. This applies to edges (`from`/`to` are IDs), strongly connected components (lists of IDs), topological orders (lists of IDs), paths, scope references — anywhere a node would otherwise appear repeatedly.
3. **No name hydration is required from the consumer.** The `nodes` map ships in the same response; resolving a name is a single map lookup, not an additional tool call.

The shape mirrors the standard graph-data idiom used by GraphML, Gephi, d3-force, NetworkX, and JGraphT, so any downstream post-processor handles it without custom code.

## Scope

**Apply to:**

- Any tool whose response references the same node more than once. The canonical case is an edge list, but the rule covers SCCs, topological orders, ranked node lists where the same node may appear in multiple sections, and any future tool that emits graph-shaped output.
- New tools by default — do not introduce embedded NodeRefs in fresh designs without a specific reason.

**Do not apply to:**

- Tools whose response references each node at most once. A `find_node` result, a single-pair `dependency_between` answer, or a `method_details` response with one declaring type does not benefit from the wrapper. The overhead of the `nodes` map exceeds the savings; keep the inline NodeRef form.
- Per-endpoint detail that is genuinely different per appearance (e.g., `parent_id` of an inherited method on `list_methods`, where the parent varies between the queried type and the declaring type). Slim encoding deduplicates *node-display* info; per-context fields stay at the appearance site.

The dividing line is: *does this node appear more than once with the same display fields?* If yes, slim encoding wins. If no, inline NodeRefs are fine.

## Response shape

The canonical structure:

```json
{
  "nodes": {
    "5625164": { "name": "core-api", "qualified_name": "com.example.core-api", "kind": "Project" },
    "5625163": { "name": "core-impl", "qualified_name": "com.example.core-impl", "kind": "Project" }
  },
  "edges": [
    { "from": 5625163, "to": 5625164, "weight": 142, "kinds": ["calls", "extends"] }
  ],
  "summary": {
    "node_count": 2,
    "topological_order": [5625164, 5625163],
    "strongly_connected_components": []
  }
}
```

Rules:

- `nodes` is a map (JSON object), not a list. Keys are stringified IDs (JSON object keys are strings); inserting in a meaningful order (topological, weight-ranked, alphabetical) gives readers a useful scan order, but consumers must not depend on key iteration order for correctness.
- The `nodes` value carries only display fields — `name`, `qualified_name`, `kind`. Counts, parent links, and other contextual data that vary by query stay outside.
- Edge endpoints, SCC members, topological-order entries, and any other node references are raw IDs (numbers in JSON), not nested objects.
- The `id` field is **not** repeated inside the node value — it's already the map key. Repeating it doubles every key string in the response.

## Consequences

### Positive

- **Inline-budget friendly.** The DSM case drops from ~146KB embedded to ~45-55KB slim — roughly 60-70% smaller. Savings scale with edge count; a graph with twice the edges saves twice the bytes.
- **Single source of truth.** A node's `qualified_name` cannot disagree between two edges in the same response, because there's only one copy of it.
- **Standard graph idiom.** Consumers that read GraphML, Gephi, d3-force, NetworkX, or JGraphT data already know this shape. No surprises for post-processors.
- **Cheaper to construct.** The producer builds each node's display fields once per response, not once per appearance.

### Negative / trade-offs

- **Consumers need a lookup step.** Reading an edge requires a `nodes[edge.from]` access to get the display name. For LLMs this is trivial; for `jq` users it's a join (`.edges[] | . + {from_name: $nodes[(.from | tostring)].name}`), still a one-liner.
- **Slightly less readable in raw form.** A reader scanning the JSON cannot see edge endpoint names without consulting `nodes`. The audience is overwhelmingly LLM and machine consumers, not humans reading raw responses — but worth flagging.
- **Two encoding conventions in the codebase.** Tools that emit single-reference responses keep inline NodeRefs; tools that emit multi-reference responses use slim. The dividing line is principled and easy to apply, but reviewers need to know it exists.

### Implementation guidance

- The MCP layer already has a helper, `AbstractGraphMcpTools.toNodeRefShort(HGNode)`, that produces the inline form. For slim responses, construct the `nodes` map manually and reference IDs directly; do not call `toNodeRefShort` for edge endpoints or derived structures.
- Insert into `nodes` in a meaningful order (e.g., DSM order for `pairwise_dependencies`, rank order for ranked lists). Most JSON parsers preserve insertion order even though the spec doesn't require it; this makes the response scannable without breaking consumers that don't depend on it.
- Round derived metrics (`density`, similar) to a fixed precision rather than emitting raw floats — bloat reduction is the headline benefit of this ADR, and full-precision decimals erode it.

## Alternatives considered

### A. Keep embedded NodeRefs on every reference

The original `pairwise_dependencies` implementation. Rejected because it forced out-of-band file handling on a small graph; the failure mode scales badly with edge count.

### B. Embed NodeRefs but with shorter field names (`n` instead of `name`, etc.)

Cuts ~30% of the duplicate bytes by shortening keys. Considered and rejected: still embeds the duplication, just smaller; loses readability for marginal savings; doesn't match any existing graph-data idiom. Slim is strictly better.

### C. Add a separate `resolve_node_names` MCP tool the consumer calls after seeing IDs

Decouples the encoding from the data, but adds a round-trip per response. Rejected because the same response can ship the lookup table inline at near-zero cost. The slim shape *is* the inline lookup table.

### D. Apply slim encoding everywhere, including single-reference responses

Consistent, but adds the `nodes` wrapper to responses that don't benefit from it. Rejected: a `method_details` response that references one type doesn't gain anything by moving that type to a top-level `nodes` map; it just nests information one level deeper for no reason.

## Related

- Tool specification that codifies this for the first time: [`cartograph-pairwise-dependencies-spec.md`](../tool-specifications/cartograph-pairwise-dependencies-spec.md) — see *"Encoding: slim, id-referenced"* and *"Implementation notes"*.
- Original problem write-up: `todos/cartograph-mcp-design-suggestions.md`, section *"Payload shape — slim node-id references"*.
