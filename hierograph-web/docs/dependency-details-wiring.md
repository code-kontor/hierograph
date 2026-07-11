# Dependency details wiring

Interaction model for how the **Cross-Reference Explorer** (center /
used-by / uses trees) and the **Dependencies Details** panel
(`DependencyDetailsPane`) are wired together.

`DependencyDetailsPane` is shared between the DSM view and the
Cross-Reference Explorer. Both feed it exactly one directed
`{sourceNodeId, targetNodeId}` pair — `cellSelection` from
`useSelection()`. The Cross-Reference Explorer also tracks an
`aggregateSide: "left" | "right" | null` UI state that determines whether
one of the two aggregate columns is currently pinned into the pane.

## Precedence & reset

The `cellSelection` pair is derived with the following precedence, highest
first:

1. **Active partner pivot** (see "Partner pivot & highlight flip" below).
2. **Aggregate inspect button** (see "Aggregate pinning" below).
3. **Empty** — no `cellSelection`, the pane shows its empty state.

`aggregateSide` resets to `null` whenever the center selection changes, and
whenever a partner is clicked — a partner pivot always takes precedence
over a previously active aggregate pin.

## Aggregate pinning

Each partner column (used-by on the left, uses on the right) has a
per-column inspect button. Clicking it pins the aggregated
center-to-column relationship into the details pane, without requiring a
partner to be selected:

- **Used by** column (left) → `(root, C)`, i.e. "Everything that uses C".
- **Uses** column (right) → `(C, root)`, i.e. "Everything C uses".

The inspect buttons are only shown while a center node is selected — only
then are the columns populated and the relationship meaningful.

## Partner pivot & highlight flip

Clicking a partner row makes that partner the subject of the details pane
instead of the center node:

- Used-by partner P (left) → `(P, root)`, i.e. "Everything P uses".
- Uses partner Q (right) → `(root, Q)`, i.e. "Everything that uses Q".

The center tree's highlight flips to match: a left-click highlights what
the partner uses (`referencedNodes`), a right-click highlights who uses the
partner (`referencingNodes`) — so the tree highlight and the details pane
always show the same set of nodes.
