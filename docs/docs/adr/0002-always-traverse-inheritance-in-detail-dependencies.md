# ADR 0002: Always Traverse Inheritance in Detail-Level Dependency Queries

**Status:** Accepted

**Date:** 2026-05-21

**First applied in:** `detail_dependencies` ([spec](../../specifications/tool-specifications/detail-level/hierograph-detail-dependencies-spec.md), implementation in `mcp-spike/core-app/.../DetailDependenciesMcpTool.java`)

---

## Context

The `detail_dependencies` tool answers questions like "what does subtree A depend on?" by returning the method-level and field-level edges between a source subtree and a target subtree.

Java's inheritance complicates the contract: if `A extends X` and `X` declares `foo()`, then `A.foo()` is part of `A`'s behavior at runtime, even though the code that physically declares `foo` lives on `X`. There are two principled framings of the question, and only one of them was previously supported by default:

- **Narrow / physically-declared.** Only return edges whose source method/field is *declared on* a type in the from-subtree. Equivalent to: "where is the source code that contains this dependency?" Useful for refactoring queries.
- **Broad / inheritance-aware.** Also return edges whose source method/field is inherited from an ancestor of a type in the from-subtree. Equivalent to: "what does this subtree effectively depend on?" Useful for behavioral analysis.

An earlier iteration of the tool exposed this distinction as an `include_inherited` boolean parameter (default `false` — narrow). Two operational observations forced a rethink:

1. **The narrow response is not closed under the LLM's typical question.** When the LLM asks "what does module M depend on?", the inherited edges are part of the honest answer. The narrow default silently omits them, so the LLM either gets an incomplete picture or has to learn to flip the flag — adding API surface it must reason about every call.
2. **Information theory is asymmetric.** From the broad response, the narrow view is a one-line client-side filter (`edges.filter(e => fromSubtreeIds.includes(e.from_parent))`). From the narrow response, the broad view is not recoverable without a second tool call — the inherited edges were never returned.

Keeping the flag forces both producers and consumers to handle two response shapes for a question that has a single richer answer. The flag is a discoverability artifact, not a semantic distinction.

## Decision

For tools that return method-level / field-level dependencies between subtrees, the Cypher query **always traverses `EXTENDS` and `IMPLEMENTS` on both the source and target declarer joins** (zero or more hops). Inherited edges are unconditionally part of the response. No `include_inherited` parameter is exposed.

Per-edge `from_parent` and (when present) `to_parent` report the *actual* declaring type — the ancestor where the source code physically lives. They may therefore point to types **outside** the from-subtree (or to-subtree) the caller passed in. The top-level `nodes` map carries entries for these out-of-subtree ancestors so the LLM can resolve them.

The subtree-anchor type (`st` in the Cypher, equal to `from_id` or one of its descendant types) is *not* preserved in the response — only the declarer is. If the caller needs to know which subtree-anchor contributed an edge, they reconstruct it from `from_parent` and the inheritance tree (or filter to physically-declared by checking `from_parent ∈ list_descendants(from_id)`).

The same rule applies to `to_parent` for relationships whose target is a method or field (`calls`, `overrides`, `reads_field`, `writes_field`, `read_by`, `written_by`). For relationships whose target is itself a type (`throws`, `returns`, `has_type`, `annotated_by`, `parameter_annotated_by`, `parameter_type`), there is no target-side declarer join and the question doesn't arise.

`RETURN DISTINCT` is mandatory on every branch. With both an ancestor and a descendant present in the same subtree, an edge can be matched via multiple paths; DISTINCT collapses the duplicates so the `by_source_type` aggregation stays honest.

## Scope

**Applies to:**

- `detail_dependencies` (canonical case — first application).
- Any future detail-level dependency tool that answers "edges between subtrees" — the inheritance question recurs identically.

**Does not apply to:**

- `list_methods` and `list_fields`. These are *composition* tools — they enumerate the methods/fields of one specific type. Both framings genuinely answer different questions: *"what does this class itself define?"* (declared only) vs *"what's the full callable / accessible surface of this class?"* (including inherited). Both views are useful per-call, neither is recoverable from the other purely by client-side filtering of the response (because the response uses `parent` as the declaring type, but the LLM cares about both the inheriting and the declaring side). These tools keep an `include_inherited` parameter, default `false`.
- `method_details` and `field_details`. These are single-entity tools — they describe one specific method or field. Inheritance is captured via the `overrides` field (for methods) and the explicit `declaring_type` field; there is no subtree-versus-declarer distinction to make.

The dividing line is: *does the tool answer a question that is monotonic in the inheritance closure?* If yes — meaning the broad view is a superset of the narrow view and the narrow view is recoverable client-side — always traverse. If no, the flag is the right design.

## Response shape

Unchanged from the slim encoding in ADR-0001. The only schema-level effect of this decision is that `from_parent` and `to_parent` are now consistently the actual declaring type, never the subtree-anchor:

```json
{
  "nodes": { "...": { } },
  "from_scope": 12345,
  "to_scope": 67890,
  "edges": [
    {
      "from": 91204,
      "from_parent": 47200,
      "to": 88401,
      "to_parent": 38104,
      "relationship": "calls",
      "location": { "line_number": 247 }
    }
  ],
  "summary": { "by_source_type": [{ "type": 47200, "edge_count": 23 }] }
}
```

Here `from_parent = 47200` may or may not be in the from-subtree the caller passed in. If `from_scope = 50000` and 47200 is an ancestor of a type in subtree 50000, the edge is inherited. The caller distinguishes the two cases by checking membership in `list_descendants(from_scope)`.

The Cypher always uses the same pattern:

```
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:<SrcLabel>)<middle><tgt-match>
WHERE id(st) IN $fromTypes AND <whereTgt>
RETURN DISTINCT id(src) AS srcId, ..., id(so) AS srcTypeId, ..., '<relName>' AS relName, <line> AS lineNumber
```

with `<tgt-match>` either `(tgt:Type)` (target is a type) or `(tgt:<TgtLabel>)<-[:DECLARES]-(to:Type)<-[:EXTENDS|IMPLEMENTS*0..]-(tt:Type)` (target is a method or field).

## Consequences

### Positive

- **One round-trip for both framings.** The LLM asks once; if it needs the narrow view, it filters client-side. No conditional re-query.
- **Smaller tool surface.** One fewer parameter for the LLM to reason about; one less branch in the implementation; one less knob in the spec.
- **Honest defaults.** "What does this module depend on?" returns the inheritance-aware answer, which matches the question's intent.
- **Consistent `from_parent` / `to_parent` semantics.** Always "the actual declaring type", regardless of any flag.

### Negative / trade-offs

- **`from_parent` can be outside the from-subtree.** The mental model "the subtree is a container" breaks: it's an *anchor for inheritance traversal*. This must be stated up front in the tool description; once stated, it's unambiguous.
- **Responses are larger.** Inherited edges add rows, ancestor types add entries to the `nodes` map. Truncation kicks in earlier on heavy queries. The `summary` digests cushion this — the LLM gets structure even on truncated responses.
- **`*0..` adds Cypher planner work.** Variable-length path matches are more expensive than direct matches. Java inheritance chains are shallow in practice (1–3 hops typical), so the cost is bounded; worth re-checking if a benchmark suite is added.
- **External (unscanned) ancestors are silently absent.** If `A extends ExternalLib.Base`, the tool cannot return `Base`'s edges as `A`'s — `Base` isn't in the graph. This was already true with the flag; documenting it remains important.

## Alternatives considered

### A. Keep the `include_inherited` flag

Self-documenting: the LLM sees the flag and chooses. Rejected because the same self-documentation is achievable via a paragraph in the tool description ("`from_parent` may be outside the from-subtree; filter to recover narrow"). The flag adds API surface for a distinction the response shape already encodes.

### B. Drop the flag, always narrow (never traverse)

Simpler Cypher (no `*0..`, no `RETURN DISTINCT`), faster query, tight subtree-as-container semantics. Rejected because the narrow view is the smaller view and can't be widened post-hoc. The asymmetry favors broad-by-default.

### C. Always traverse, but preserve the subtree-anchor in the response

Add a second field per edge — `from_anchor` (the type in the from-subtree that "owns" the inheritance match) alongside `from_parent` (the actual declarer). Rejected because (a) when both an ancestor and descendant of the same hierarchy are in the from-subtree, the anchor is ambiguous; (b) the caller can always determine the anchor by checking `from_parent` against the from-subtree's descendant set. The extra field doesn't pay its bytes.

### D. Apply the always-traverse rule to `list_methods` / `list_fields` too

Tempting for consistency. Rejected because those tools ask a different question (composition of one type, not coupling between subtrees) and both views answer it usefully. See *Scope* above.

## Related

- Specification: [`docs/tool-specifications/detail-level/hierograph-detail-dependencies-spec.md`](../../specifications/tool-specifications/detail-level/hierograph-detail-dependencies-spec.md) — see *"Inheritance"* under *Semantics* and the *Per-edge fields* description of `from_parent` / `to_parent`.
- Discussion that led to this decision: [`docs/design-notes/detail-dependencies-inheritance.md`](../docs/design-notes/detail-dependencies-inheritance.md).
- Related encoding decision: [ADR-0001 — Slim Payload Encoding](0001-slim-payload-encoding.md). The slim shape is what makes the always-traverse rule cheap to ship — out-of-subtree ancestor types land once in the `nodes` map regardless of how many edges reference them.
