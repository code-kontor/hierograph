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
- **Grey text is `fg-muted`, quieter text `fg-subtle`;** both are dark enough for
  text (WCAG AA, 4.5:1, on the panels; `fg-muted` also on the hover and selection
  fills). Never lighter greys for text: the borders' greys are for lines.
  Every secondary text — a section label, a table header, a caption, a count, an
  inactive tab — is `fg-muted`; `fg-subtle` is for icons, chevrons and glyphs only.
  (`muted-foreground` is an alias of `fg-muted`.)
- **One fill below the panel: `inset`.** Title bars, toolbars, table headers and
  tracks use it (`panel-header`, `zebra`, `muted` and `secondary` are aliases). Don't
  add a second near-white beside it, and don't tint content areas or stripe tables:
  rows are separated by hairlines.
- **A node is named with its icon,** `NodeIcon` (`src/design-system/graph/treeIcons.tsx`):
  its kind's icon (view group, module, External Types, package, member), and for a
  type the letter IDEs give its kind in a rounded square: C (class), I (interface),
  E (enum), R (record), @ (annotation).
- **Type: nothing below 12 px; monospace only for code identifiers** (packages, types,
  members, signatures). Module names, counts, chip labels and headings are sans; counts
  use tabular figures. Only the panes' titles are uppercase and letter-spaced; column
  and table headings are sentence case, 12 px, `fg-muted`.
- **Targets are at least 24 × 24 px** (WCAG 2.2), splitters at least 12 px across.
  Where the drawn control is smaller (a 14 px chevron, a 6 px splitter), an
  absolutely positioned `after:` pseudo-element enlarges what the pointer finds,
  without moving anything.
- **Notes inside a pane are a `Notice`** (`src/design-system/ui/notice.tsx`): `info`
  (a choice, such as what a view excludes), `warning` (expected but incomplete, such
  as what isn't compiled yet) or `error`. All are laid out alike: the icon in a column
  of its own, a title, then the explanation and
  any list. A screen-sized state (loading, empty, failed) is a `Message` instead.
- **Open example — `src/design-system/ui/button.tsx`:** still the stock shadcn Button
  (token-based but not restyled). When a component is first genuinely used, restyle it
  to fit the rest of the UI _first_. A component with such a documented "pending" note
  is the exception; everything else in `ui/` is expected to fit.
- **Hierarchical graphs are operated the same way** on every screen and screen part
  that shows one (the structure map, the hierarchy tree, …): the
  [interaction model](#interaction) below.

## Interaction

Every hierarchical graph follows one interaction model, so what a user learns on one
screen holds on all of them. It follows the platform conventions (macOS, Windows) and
the tools the UI takes its character from, rather than inventing gestures of its own.

The structure map (`src/structure/StructurePage.tsx`) and the start screen's hierarchy
tree (`src/start/HierarchyTree.tsx`) follow the model. What they share lives in
`src/design-system/graph/`: `SelectionStatus` (the status chip),
`PinSelectionButton` (the pin), `GesturesHelp` (the `?`, from `SELECTION_GESTURES` and
`KEYBOARD_GESTURES` plus a graph's own) and `ContextMenuShortcut` for the menus. A graph zooms and pans only if it needs to: the
tree neither zooms nor pans, so it leaves `Ctrl`/`⌘`+wheel to the browser and lists no
zoom or pan gestures.

**Pending:** the click, double-click and keyboard handling is still written once per
screen. Pull it into `src/design-system/graph/` too once a third graph needs it, or
when the two copies drift.

### Gestures

| Input                                                                                                          | Action                                                                                                                                      |
| -------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| Click a node                                                                                                   | Select it: its incoming and outgoing dependencies are drawn. Clicking the selected node again clears the selection.                         |
| Click a node's chevron (in a tree, anything left of its name: indent, chevron, icon), or double-click the node | Expand or collapse it.                                                                                                                      |
| Right-click a node                                                                                             | Context menu with the node's commands (see below).                                                                                          |
| Click the empty canvas, `Esc`, or "Clear selection"                                                            | Clear the selection.                                                                                                                        |
| Pin button, left of the `?` (where there is one)                                                               | Pin the selection: clicks, double-clicks and `Enter` keep it, only a menu's "Select …" changes it; clearing it unpins it.                   |
| Wheel / `Shift`+wheel / trackpad swipe                                                                         | Scroll vertically / horizontally — the browser's native scrolling.                                                                          |
| `Ctrl`/`⌘`+wheel, trackpad pinch                                                                               | Zoom, anchored at the pointer.                                                                                                              |
| `Ctrl`/`⌘`+`+` / `−` / `0`, and a "Fit" button                                                                 | Zoom in / out / reset to 100 % / fit the graph to the viewport (while the graph has focus).                                                 |
| "Zoom to the selection" button, next to "Fit"                                                                  | Zoom and scroll to the selected node and the nodes its arrows end at, with a margin, never above 100 %; disabled while nothing is selected. |
| Drag the canvas (a node too: it has nothing else to drag)                                                      | Pan.                                                                                                                                        |
| Arrow keys                                                                                                     | Move to the next node the way the graph is drawn (see the rule below).                                                                      |
| `+` / `−`                                                                                                      | Expand / collapse the node; `−` in a node that isn't open collapses the node it lies in, and moves there.                                   |
| `Home` / `End` / `Enter` `Space`                                                                               | The first / the last node / select.                                                                                                         |
| `Ctrl`/`⌘`+click                                                                                               | Reserved for multi-select; give it no other meaning.                                                                                        |

### Rules

- **No hover mode.** Hovering never changes which dependencies are drawn. It only gives
  feedback: a pointer cursor, a subtle outline on the node under the pointer, and a
  delayed tooltip for a name that is cut off. A node must look clickable.
- **The selection is sticky.** It stays until another node is selected or it is
  cleared; nothing clears it by accident (moving the pointer, scrolling, zooming,
  expanding or collapsing). The selected node gets a clear outline, and the graph pane
  shows a status chip in its upper-right corner — "Selected: `name` · Esc to clear" —
  with the button that clears it. Where another part of the screen names the selection
  already (the start screen's dependencies' heading, with the clear button), the chip
  is only announced to screen readers (`SelectionAnnouncement`): state is said once.
- **A selection can be pinned** where clicking around must keep it (the structure
  map; the start screen's tree has no pin, since its selection is explored in the
  dependency pane beside it). The pin button sits in the pane's toolbar after the
  status chip and before the `?`, disabled while nothing is selected. Pinned, the
  selection stays whatever is clicked: a click, a double-click, a click on the empty
  space and `Enter` leave it (a double-click and the expander still expand). Only a
  menu's "Select …" changes it, and the pin stays; `Esc` or "Clear selection" clears it
  and unpins it. It is a mode, so it must not go unnoticed: pressed, the pin is in the
  warning orange (`status-warning` on `status-warning-bg`), the colour the start
  screen gives uncommitted changes, and apart from the blues of the selection. The pin
  is in the URL with the selection (`selection_pinned`).
- **Left-click only selects.** A plain click never navigates, drills in or expands
  (the chevron is its own target). Anything heavier goes on double-click, `Enter` or the
  context menu.
- **Right-click is the context menu,** never a direct action. The menu offers the
  node's commands (Select / Clear selection, Expand / Collapse, Expand all, …) and
  shows each command's gesture (`ContextMenuShortcut`), so the menu also teaches the
  gestures.
- **Scrolling is native.** Don't handle plain or `Shift`+wheel events: macOS and the
  browsers already turn `Shift`+wheel into horizontal scrolling, and trackpads send
  horizontal deltas themselves; handling them again scrolls twice or breaks the
  trackpad.
- **Scrollbars are quiet but always there.** They are thin, with a transparent track.
  The thumb is at rest in the splitters' hover fill (`state-hover`) and a step darker
  (`border`) while the pointer is over the scrolling area or focus is inside it (`src/index.css`, base layer). They
  are never hidden: a visible thumb is what tells that a pane scrolls, and it respects
  a user who set the system to always show scroll bars. Don't restyle a scrollbar per
  component.
- **The zoom controls sit in the graph's lower right corner,** in this order: zoom out,
  the zoom (a click resets it to 100 %), zoom in, fit the graph, zoom to the selection,
  then the graph's own (the structure map's "Zoom to the dependencies"). Zoom to the
  selection is always there, disabled while nothing is selected, so the controls don't
  shift as the selection comes and goes.
- **Zoom stays inside the graph** (where a graph zooms). `Ctrl`+wheel (which is also what a trackpad pinch
  sends) and `⌘`+wheel are handled by a non-passive `wheel` listener that calls
  `preventDefault()`, so the browser's page zoom does not kick in. The zoom shortcuts
  apply only while the graph has focus.
- **One word for it: select.** The UI, the docs, tooltips and URL parameters say
  _select_ / _clear selection_, not _pin_ / _unpin_ or _focus_.
- **Discoverable.** Every button's tooltip names its shortcut ("Clear selection (Esc)"),
  and a `?` button in the graph pane lists the gestures above, and the pane's buttons
  that change what the gestures do: the pin (`SELECTION_GESTURES` holds both). A new
  such button goes into the `?` too.
- **Keyboard-operable** (WCAG 2.1.1): everything a mouse can do, the keyboard can do;
  the graph is focusable and the focused node is visible.
- **Arrow keys move the way the graph is drawn; `+` / `−` expand and collapse
  everywhere.** In a graph laid out in two dimensions (the structure map: boxes side
  by side in rows, rows stacked by level), the arrow keys move to the nearest node in
  that direction, and an open container counts by its name bar, so ↓ enters it and ↑
  leaves it. In a graph that runs top to bottom (the hierarchy tree), ↑ ↓ move and
  → ← expand, collapse and step in and out, as in a file tree (the WAI-ARIA tree
  pattern). `+` and `−` expand and collapse on every graph, as in Windows tree views and
  JetBrains IDEs, and match the `+`/`−` toggles drawn on the nodes; the context menus
  show them next to Expand and Collapse. Not `Shift`+arrows: those extend a selection,
  which multi-select will need.

## Dev tooling

There is intentionally **no in-repo component showcase route** — a former
`/dev/primitives` page was removed. Inspect primitives in their real usage.
