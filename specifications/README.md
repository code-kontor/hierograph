# Hierograph Design Documents

This bundle contains the current design documentation for Hierograph (formerly Cartograph). The documents are mutually consistent and reflect the final state of design decisions as of this snapshot.

## Documents

### Core specifications

**`hierograph-tool-surface-proposal.md`** — The main API specification. Documents the 13 MCP tools, their parameters and response shapes, the NodeRef variants, the input-acceptance policy, the node-kind and edge-attribute vocabularies, and the architectural principles that drive the design (input-bounded vs. data-bounded tools, navigation vs. evidence boundary). This is the primary reference for what the LLM sees.

**`hierograph-architecture.md`** — The internal architecture. Documents the three-layer model (tool, model, provider) and the three-provider sub-structure (HierarchyProvider, CoreDependencyProvider, DetailDependencyProvider). Includes interface sketches in Kotlin, scanner-agnosticism details, and the migration plan for completing the provider model. This is the primary reference for implementers and contributors.

**`hierograph-pagination.md`** — The cursor protocol specification. Documents the stateless cursor design, the five error cases with recovery paths, per-tool iteration orders, and the per-tool page-size defaults calibrated to client constraints. Referenced from the tool surface proposal where cursor parameters appear.

### Supporting analysis

**`hierograph-response-size-limits.md`** — Analysis of Claude Code's MCP response size constraints (10K-token warning, 25K-token hard limit) and how those constraints drive Hierograph's pagination defaults. Includes per-tool calibration of default page sizes against token budgets.

**`hierograph-limits-vs-skill.md`** — Discussion of which behavioral guidance belongs in tool parameters versus in a Skill for the LLM. Surfaces the principle that tools enforce mechanism while Skills shape preference.

**`hierograph-marketing-story.md`** — The Rosetta Stone framing and positioning narrative. Includes jQAssistant attribution. Intended for marketing materials, README, and public communications.

**`hierograph-availability-research.md`** — Research on the name "Hierograph" — verification that the name is available across the relevant namespaces (GitHub org, npm, package registries, domains, social handles). Recommendations for reservation order.

## How the documents relate

The tool surface proposal is the foundation document. The architecture document complements it from the implementation side. The pagination spec is referenced by the tool surface proposal where cursor parameters appear; it stands alone as a focused protocol document.

The response-size-limits analysis grounds the pagination defaults in measured constraints. The limits-vs-skill discussion captures a principle that runs through the API design.

The marketing story and availability research are independent — they don't depend on the technical documents but use them as reference material.

## Design decisions captured across the bundle

A few decisions worth highlighting that show up across multiple documents:

- **Input-bounded vs. data-bounded tools** — pagination applies to data-bounded tools only; input-bounded tools use input validation. Stated in the tool surface proposal, applied in the pagination spec, referenced in the response-size-limits analysis.

- **Two NodeRef shapes** — enriched for browse tools, minimal for structural references. Defined in the tool surface proposal, used consistently across all tools.

- **Attribute-on-edge for type-level dependencies** — one edge per (source, target) pair with scanner-declared boolean attributes. Documented in the tool surface proposal; implementation detail in the architecture document; bit-packed storage rationale also in the architecture document.

- **Scanner-driven vocabularies** — node kinds, edge attributes, and detail-level relationships are all declared by the active provider, not hardcoded. Documented in the architecture document; surfaced through `graph_overview` per the tool surface proposal.

- **Lazy materialization of node properties** — skeleton loaded eagerly, properties materialized on first access. Documented in the tool surface proposal's architecture section.

## Concrete measurements

On a Spring Framework-sized codebase (the reference test case):

- ~110,000 hierarchy parent-child pairs
- 115,906 type-level dependency edges
- 38 ms to load the full skeleton from Neo4j
- 64 MB heap for the loaded skeleton (hierarchy + type-level dependency graph)
- 220 MB heap upper bound after full property materialization
- Microsecond-scale latency for navigation, aggregation, and reachability queries
