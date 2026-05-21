# Tool Specification Conventions

Rules that apply to every spec under `docs/tool-specifications/`. These are deliberately short — they capture decisions that came up while writing the specs and would otherwise be re-litigated each time a new spec is added.

## No ADR references

Specs must not name or link to ADRs (no "Per ADR-0001…", no links to `docs/adr/*.md`, no "the ADR calls this out", etc.). Every spec is a standalone normative document: it must respect every ADR the project has accepted, but state each decision directly as its own design rule.

**Why.** Specs are the source of truth for tool implementers and (transitively, via the `@Tool` description) for the LLMs that call the tools. ADR citations break self-containment — readers have to follow a second hop to learn what the spec actually requires, and project-internal decision history leaks into a contract that is supposed to stand on its own.

**How to apply.** When a design point originates in an ADR, copy the *behavior* — the response shape, the encoding rule, the field semantics, the exclusion criteria — into the spec as a direct statement. Drop all phrasing like *"per the ADR"*, *"the ADR explicitly excludes…"*, *"see the ADR rationale"*. ADRs remain authoritative inside `docs/adr/` for internal design conversations; they just don't surface in the spec text.

If you are unsure whether a sentence is citing an ADR, grep:

```
grep -rni 'ADR\|adr/' docs/tool-specifications/
```

The expected output is empty.
