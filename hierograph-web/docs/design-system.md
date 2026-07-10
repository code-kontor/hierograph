# Design system & the Claude Design loop

hierograph-web has a deliberate **IDE character** (references: Eclipse, JetBrains
IDEA): a calm functional palette, unambiguous interaction-state colors, fine panel
dividers, tool-like density. This document is the durable convention for keeping the
UI design-conform and for how design changes flow between the code and the design tool.

## Where the design lives

- **In code (the material truth):** the design tokens live in `src/index.css`
  (Tailwind v4 `@theme` + `:root`, oklch, light-only), and the reusable primitives
  live in `src/design-system/ui/`. This is what actually renders — treat it as the
  source of truth for anything already materialized here.
- **Canonical design source:** the live **Claude Design** project
  **"# IDE Character Redesign"** (`af5f2fb0-a6e8-425d-adef-b5596141c854`,
  <https://claude.ai/design/p/af5f2fb0-a6e8-425d-adef-b5596141c854>). It holds the
  component specs, interaction behaviour, per-view `.dc.html` references, screenshots,
  and the exact copy/labels/badges/legends that prose can't fully carry. When a detail
  isn't already in `index.css` or the primitives, read it from there (see the MCP
  section below) rather than inventing it.

> The redesign was originally delivered as a static handoff package
> (`design_handoff_hierograph_ide/`). That package has been removed — it drifted from
> the live project and duplicated what is now either in-repo code or reachable live via
> the MCP. It remains in git history if ever needed. The live project above is the
> current source.

## The rule for UI work

- **New or changed UI components must be styled design-conform** to the IDE design
  system: use the tokens from `src/index.css` and the component specs, never ad-hoc
  colors or spacings.
- **Design decisions and design changes happen only in Claude Design**, never ad hoc in
  the code. From the code you read the design **read-only** (via the Design MCP); you do
  not invent or alter the design here.
- **Open example — `src/design-system/ui/button.tsx`:** still the stock, pre-redesign
  shadcn Button (token-based but not yet restyled to the 4-variant spec). It is the
  worked instance of the "pending" pattern: when a component is first genuinely used,
  restyle it design-conform _first_. Any component that carries such a documented
  "pending" note is the exception; everything else in `ui/` is expected to be conform.

## Connecting the Design MCP (read-only)

The Claude Design MCP server exposes the live project. Add it once and log in:

```
claude mcp add --scope user --transport http claude-design https://api.anthropic.com/v1/design/mcp
/design-login
```

Use it **only to read** (design decisions stay in Claude Design). Useful tools:
`list_projects` / `get_project` (confirm the project id), `list_files` + `read_file`
(pull specs, tokens, `.dc.html` references; use `if_none_match`/etags to skip unchanged
files), and `get_conversation` (read the design chat transcript — see the loop below).

## The change loop (Claude Code ↔ Claude Design)

Design changes flow through a three-step loop. The visual design step is deliberately
human-driven; the two former copy-paste handoffs are replaced by MCP reads.

1. **Outbound — describe the change (Claude Code → you → Claude Design).** Draft a
   requirements/use-case brief for the change (context, use-case, what to add/change,
   constraints, what to preserve) and take it into Claude Design. The
   **`design-brief` skill** drafts this brief, grounded in the current app state.
2. **Design — in Claude Design (you).** Make the visual change. At the end, have Claude
   Design write a short handoff summary **into the chat** (what changed, which
   specs/strings/tokens/views are affected).
3. **Inbound — pull it back (Claude Design → Claude Code).** Instead of pasting the
   summary, Claude Code reads it live: `get_conversation` returns the chat (its tail is
   that handoff summary), then `read_file` pulls the specific changed docs/`.dc.html`,
   reconciled against the repo state into an implementation-ready change list. The
   **`design-sync` skill** performs this pull.

> The `design-sync` (inbound) and `design-brief` (outbound) skills are the intended
> mechanism for steps 3 and 1, provided by the surrounding hg-develop workspace tooling.
> If not present, perform the steps manually with the MCP tools above.

## Dev tooling

There is intentionally **no in-repo component showcase route** — a former
`/dev/primitives` page was removed. Inspect primitives in their real usage, or against
the live Claude Design references.
