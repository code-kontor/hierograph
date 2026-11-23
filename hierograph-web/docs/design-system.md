# Design system

hierograph-web has a deliberate **IDE character** (references: Eclipse, JetBrains
IDEA): a calm functional palette, unambiguous interaction-state colors, fine panel
dividers, tool-like density. This document is the convention for keeping the UI
consistent with it.

## Where the design lives

The design tokens live in `src/index.css` (Tailwind v4 `@theme` + `:root`, oklch,
light-only), and the reusable primitives live in `src/design-system/ui/`. This is what
renders, and it is the source of truth.

## The rule for UI work

- **Style new or changed UI with the tokens** from `src/index.css` and the primitives,
  never ad-hoc colors or spacings. A screen that needs colours of its own (a map's role
  colours, say) defines them once, next to the screen, from the tokens where it can.
- **Dependencies are drawn with `DependencyArrow`** (`src/design-system/graph/`), on
  every screen: its role colours (what the focus uses, what uses it, a cycle's, given ones handed
  to the screen), widths
  by weight, dashed against the order, open arrowheads (`DependencyArrowHeads` once per
  SVG). A screen lays out the paths; the arrows look the same everywhere.
- **Open example — `src/design-system/ui/button.tsx`:** still the stock shadcn Button
  (token-based but not restyled). When a component is first genuinely used, restyle it
  to fit the rest of the UI _first_. A component with such a documented "pending" note
  is the exception; everything else in `ui/` is expected to fit.

## Dev tooling

There is intentionally **no in-repo component showcase route** — a former
`/dev/primitives` page was removed. Inspect primitives in their real usage.
