# Hierograph: Response Size Limits and Their Implications

This document captures the actual response size constraints that apply to Hierograph as an MCP server in Claude Code, and what those numbers mean for pagination defaults and tool design.

## The actual limits

**Claude context windows (as of May 2026):**

- Claude Opus 4.7 and Claude Sonnet 4.6: 1,000,000-token context window
- Claude Haiku 4.5: 200,000-token context window
- Older models (Sonnet 4.5 and below): 200,000 tokens

**Claude Code's MCP-specific limit (the one that matters for Hierograph):**

This is the critical number. Claude Code manages MCP tool output to prevent overwhelming the conversation context:

- **Warning threshold:** 10,000 tokens — Claude Code displays a warning when any MCP tool output exceeds this
- **Default maximum:** 25,000 tokens — adjustable via the `MAX_MCP_OUTPUT_TOKENS` environment variable
- **Per-tool override:** tools that set `anthropic/maxResultSizeChars` use that value for text content, regardless of `MAX_MCP_OUTPUT_TOKENS`

## What this means for Hierograph

The relevant ceiling for the design is **25,000 tokens per MCP response by default in Claude Code**, with a warning at 10,000 tokens.

In bytes, this works out to roughly:

- 10,000 tokens ≈ 40 KB of JSON
- 25,000 tokens ≈ 100 KB of JSON

In Hierograph-relevant terms, an enriched NodeRef is roughly 200-400 bytes of JSON. So:

- **Page of ~25 enriched NodeRefs:** under the warning threshold (~10 KB)
- **Page of ~100 enriched NodeRefs:** at the warning threshold (~40 KB)
- **Page of ~250 enriched NodeRefs:** at the hard limit (~100 KB)

This validates the earlier observation that 750 results becomes unwieldy, and the choice of 200 as a default page size is in the right ballpark. But it's worth checking more carefully for the larger response shapes:

- A detail-level edge with full NodeRefs for source, target, location (path + line), and a relationship kind is probably 400-600 bytes of JSON
- 200 such edges ≈ 80-120 KB ≈ 20-30K tokens

That puts a 200-edge page right at the limit, possibly slightly over. **The default of 200 should be reconsidered** for the tools whose response items are larger (detail-level dependency results in particular).

## Other relevant facts

A few additional details worth knowing:

**The "1 MB MCP response limit" mentioned in some online sources is mythology, not protocol.** That number comes from a Python utility's self-imposed safety threshold. There's no MCP protocol-level size limit — the binding constraint is the client's token cap (Claude Code's default of 25K).

**Per-tool limits can be declared.** The `anthropic/maxResultSizeChars` annotation lets a tool tell Claude Code "use this specific size limit for me." Hierograph can set this to exactly match its pagination strategy, ensuring responses always fit.

**Users can configure higher limits.** Setting `MAX_MCP_OUTPUT_TOKENS` to a higher value lets Hierograph users opt into larger responses if their workflow benefits. So the limit is a sensible default, not a hard ceiling for all users.

**Other LLM clients have different limits.** Cursor, Cline, and other MCP-compatible clients each have their own conventions. Designing Hierograph to fit Claude Code's defaults gives safety in the primary target client, with room for users in other clients to adjust.

## Concrete recommendations for Hierograph

Given the 25,000-token default ceiling:

**1. Measure actual response sizes for representative tool calls.** The 200 default page size was a guess; ground it in measurement. Take a typical `outgoing_dependencies` response at `detail_level: "detail"` with 200 edges, serialize it, count tokens (roughly bytes/4 for English text + JSON). If it comes in at 15-20K tokens, the default is right. If it's pushing 25K, lower the default to 100 or 150.

**2. Consider setting `anthropic/maxResultSizeChars` per tool.** This is a small but meaningful integration with Claude Code — Hierograph tells the client "responses won't exceed X chars," which both validates the pagination math and prevents Claude Code from rejecting responses unexpectedly.

**3. Document the limits in the pagination spec.** The pagination document currently says "default page size 200" without explaining why. Adding the actual constraint (Claude Code's 25K-token cap, 10K warning threshold) lets future maintainers recalculate if needed.

**4. Target "comfortably under 10K tokens" for normal responses.** Pages under 10K trigger no warning. Pages between 10K and 25K work but might produce user-visible warnings. Pages over 25K fail or get truncated. The sweet spot is "comfortably under 10K" for typical responses, with headroom for outlier cases.

## Calibrating the page-size defaults per tool

Different paginated tools have different response shapes. The page-size defaults should reflect this:

**`list_descendants`** — items are enriched NodeRefs, ~250 bytes JSON each
- 200 items × 250 bytes = 50 KB ≈ 12.5K tokens (slightly above warning)
- Suggested default: **150** (under warning, comfortable margin)

**`outgoing_dependencies` / `incoming_dependencies` at `detail_level: "type"`** — items are type-to-type edges with source/target NodeRefs and metadata, ~300-400 bytes JSON each
- 200 items × 350 bytes = 70 KB ≈ 17.5K tokens (well above warning, approaching limit)
- Suggested default: **100** (around warning threshold)

**`outgoing_dependencies` / `incoming_dependencies` at `detail_level: "detail"`** — items are method/field-level edges with source location (path + line), ~500-600 bytes JSON each
- 200 items × 550 bytes = 110 KB ≈ 27.5K tokens (above hard limit)
- Suggested default: **80** (around warning threshold)

**`affected_by`** — items are affected NodeRefs with distance and via path, ~400-500 bytes JSON each
- 200 items × 450 bytes = 90 KB ≈ 22.5K tokens (above warning, approaching limit)
- Suggested default: **100** (around warning threshold)

These are estimates based on rough size calculations; actual measurement on real responses would refine them. The point is that "200 across the board" is probably wrong — different tools produce different per-item sizes, and the defaults should reflect that.

## What this changes in the pagination spec

The pagination spec should be updated with:

1. The token-budget rationale for default page sizes (Claude Code's 25K-token cap, 10K warning)
2. Per-tool defaults calibrated to fit comfortably under the warning threshold
3. A note about `anthropic/maxResultSizeChars` as the proper protocol-level declaration
4. Server-side caps that prevent any user-configured `limit` from producing responses above 25K tokens

These changes tighten the design from "pick 200 as a default" to "pick a default that fits the actual constraint, calibrated per tool's response shape."

## A meta-observation worth recording

The pagination work has gone through several stages of refinement:

1. **First pass:** generic limits on every tool
2. **Second pass:** limits only on tools where result size isn't input-bounded
3. **Third pass:** cursor-based pagination on data-bounded tools
4. **Fourth pass (this one):** per-tool page-size defaults calibrated to actual response sizes and client constraints

Each refinement was driven by surfacing a constraint or principle that had been implicit. This is what API design should look like — start with the obvious, refine as the underlying truths become visible. The final design is more honest about how the system actually works under the constraints it actually faces.
