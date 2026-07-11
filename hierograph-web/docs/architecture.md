# Frontend Architecture

The frontend is organised into feature _verticals_ as top-level folders under
`src/`. Each vertical owns a specific screen or shared concern. Dependencies
between verticals flow only in the directions listed in the table below — the
dependency graph is a DAG (directed acyclic graph), i.e. the DSM is
lower-triangular.

## Verticals

| Vertical                   | Scope                                                                                       |
| -------------------------- | ------------------------------------------------------------------------------------------- |
| `dsm`                      | DSM screen (`/dsm`)                                                                         |
| `cross-reference-explorer` | Cross-Reference Explorer screen (`/cross-reference-explorer`)                               |
| `dependency-diagram`       | Dependency Diagram screen (`/dependency-diagram`)                                           |
| `dependency-details`       | shared inspector pane                                                                       |
| `dev-panel`                | floating developer inspector panel (node details + dev query log), rendered globally in DEV |
| `selection`                | cross-view workbench selection state                                                        |
| `tree`                     | async tree widget and settings                                                              |
| `graph`                    | shared node-domain concepts (icons, labels, node queries)                                   |
| `design-system`            | domain-agnostic UI components (depends on no vertical)                                      |
| `graphql`                  | data-access platform (Apollo client and generated code)                                     |
| `routes`                   | thin route wiring                                                                           |
| `routing`                  | shared search-param codec (comma id lists, enum guards) for `validateSearch` and the router |
| `testing`                  | test infrastructure                                                                         |

## Allowed Dependency Direction (DAG)

| From                                         | May import                                                                                                    |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| app (`main.tsx`, `routeTree.gen.ts`)         | `routes`, `routing`, `graphql`, `design-system`                                                               |
| `routes`                                     | `dsm`, `cross-reference-explorer`, `dependency-diagram`, `selection`, `dev-panel`, `routing`, `design-system` |
| `dsm` / `cross-reference-explorer` (screens) | `dependency-details`, `selection`, `tree`, `graph`, `design-system`, `graphql`                                |
| `dependency-diagram` (screen)                | `dependency-details`, `selection`, `tree`, `graph`, `design-system`, `graphql`                                |
| `dependency-details` (shared pane)           | `selection`, `tree`, `graph`, `design-system`, `graphql`                                                      |
| `dev-panel`                                  | `selection`, `graph`, `design-system`, `graphql`                                                              |
| `tree`                                       | `graph`, `design-system`                                                                                      |
| `graph`                                      | `graphql`, `design-system`                                                                                    |
| `selection`                                  | (nothing internal)                                                                                            |
| `design-system`                              | (nothing internal — only itself)                                                                              |
| `graphql`                                    | (nothing internal)                                                                                            |
| `routing`                                    | (nothing internal — only `@tanstack/react-router`)                                                            |
| `test` / `testing`                           | anything                                                                                                      |

The `dsm` and `cross-reference-explorer` verticals are _screen verticals_ —
each owns a top-level screen and composes the shared `dependency-details`
pane into that screen. `dependency-details` is a _shared pane_ with no
knowledge of which screen uses it. This screen-→-pane layering is
deliberately directed: `dependency-details` does not import `dsm` or
`cross-reference-explorer`, keeping the DSM lower-triangular (no back-edge,
no cycle).

`dependency-diagram` is a third, experimental screen vertical (`/dependency-diagram`).
It composes the shared `dependency-details` pane as a lower panel (an edge click
in the diagram populates it) and, like `cross-reference-explorer`, builds its own
tree wiring directly from the shared `tree`/`graph` primitives rather than
importing another screen's internals — there is no `dsm` → `dependency-diagram`
edge (or vice versa); screen verticals never import each other.

## Router context & data loading

`createRouter` is called with `context: { queryClient }`, and the root route is
typed with `createRootRouteWithContext<{ queryClient: QueryClient }>()`. This
makes `queryClient` available to any route loader via `context.queryClient`.

Route loaders with `ensureQueryData` are deliberately **not** introduced for the
root-node fetch. The addressed waterfall is single-level (route match →
component render → `useQuery`). In a pure client-side SPA, a loader would start
the fetch only microscopically earlier — before rather than immediately after the
first render — with no real latency benefit. `HierarchyTree` and
`CrossReferenceExplorerView` each carry their own pending/error UI; a blocking loader
would replace that component-owned UI with router-level
`pendingComponent`/`errorComponent`, changing observable behaviour with no
upside. The `queryClient` context injection is the structural enabler that makes
loaders possible in a future task if the tradeoff ever changes.

## URL state / deep-linking

The navigationally relevant selection/view state is held in **typed search
params** (`validateSearch`), so the URL is the source of truth: Back/Forward,
shareable deep links, and hand-built URLs all restore the view. The `routing`
vertical owns a shared codec (`searchCodec.ts`): comma-encoded id lists
(`subject_ids=42,43`, not TanStack's JSON arrays — readable, hand-buildable) and
enum guards, wired into the router as custom `parseSearch`/`stringifySearch`.

Params (all optional, unknown/invalid values silently dropped — no crash on a
stale id):

- `/dsm`: `subject_ids` (csv), `from_id`, `to_id` (active cell), `tab`
  (`usages`|`paths`).
- `/cross-reference-explorer`: `center_ids` (csv), `side`, `aggregated`
  (`uses`|`used-by`).

The root route validates the union (type coercion only) and runs
`retainSearchParams(true)` (both param sets survive route switches, so the
header `<Link>`s carry no `search`) + `stripSearchParams({ tab: "usages" })`.
Each leaf route validates its own subset with a cascade (a level is dropped when
its parent is absent: `from_id`/`to_id` need `subject_ids`; `tab` needs a
complete cell; `side`/`aggregated` need `center_ids`).

Consumers derive state directly from `useSearch` and write only via `navigate`
(no mirror `useState`, no URL→state→navigate effect — the one allowed
URL-reading effect is the tree reveal, which never navigates). Committed
selections push a history entry; view toggles (`tab`, aggregate inspect) use
`replace`. Node ids are volatile (Neo4j `id(n)`); the `_id` suffix on every
node-referencing param is the migration hook for a later FQN switch. Tree
expansion is **not** serialized — it is reconstructed via `AsyncTree.revealNode`
from the selection's ancestor chain. localStorage display preferences stay in
localStorage.

`DependencyPartnersPanel` is the second public file of `dependency-details`: an
anchor-centric aggregate overview (tier 1) used by the Cross-Reference
Explorer, whereas `DependencyDetailsPane` remains the DSM inspector.

## Selection & DevPanel provider scoping

**One selection provider per screen, exposing the same `useSelection()` API.**
Every screen mounts its own provider — there is deliberately no global one:

- `DsmPage` uses `DsmSelectionProvider` (in `dsm`), a **URL-read-through
  adapter**: `selectedIds`/`cellSelection` are derived from the `/dsm` search
  params and writes go through `navigate`. Consumers (`DependencyMatrix`,
  `HierarchyTree`, …) keep calling `useSelection()` unchanged — only the source
  behind it moved to the URL.
- `CrossReferenceExplorerPage` and `DependencyDiagramPage` keep the state-backed
  `SelectionProvider` (`selection/SelectionContext.tsx`). The Cross-Reference
  Explorer only URL-binds its **view** params (`center_ids`/`side`/`aggregated`)
  inside its view; the nested `SelectionProvider` still owns transient
  focus/`cellSelection` (partner clicks), keeping the #0096 isolation intact.

Both providers share the transient focus logic via the `useFocusState()` hook
(`selection/useFocusState.ts`): focus is never serialized, and is mirrored into
the globally-mounted `FocusBridge` (`selection/FocusBridge.tsx`) so the
DEV-only `DevPanel` reads the focused node regardless of which screen provider
is mounted (a no-op when no bridge is above, e.g. standalone browsertests).

Per-screen providers give each screen a clean reset on remount instead of an
imperative global reset that is easy to get subtly wrong, keep each screen's
selection lifecycle self-contained, and decouple the panel's focus read from
screen internals via the bridge.

## Public-API Rule

Verticals (`dsm`, `cross-reference-explorer`, `dependency-diagram`,
`dependency-details`, `dev-panel`, `selection`, `tree`, `graph`, `routing`) are
entered only through their **declared public files** — the explicit per-vertical
allow-lists in `eslint.config.js` (`boundaries/dependencies`). Cross-vertical
imports use direct paths (`@/<vertical>/<File>`); there are no re-export
barrel `index.ts` files.

| Vertical                   | Public files                                                       |
| -------------------------- | ------------------------------------------------------------------ |
| `dsm`                      | `DsmPage.tsx`                                                      |
| `cross-reference-explorer` | `CrossReferenceExplorerPage.tsx`                                   |
| `dependency-diagram`       | `DependencyDiagramPage.tsx`                                        |
| `dependency-details`       | `DependencyDetailsPane.tsx`, `DependencyPartnersPanel.tsx`         |
| `dev-panel`                | `DevPanel.tsx`, `DevPanelContext.tsx`                              |
| `selection`                | `SelectionContext.tsx`, `FocusBridge.tsx`, `useFocusState.ts`      |
| `tree`                     | `AsyncTree.tsx`, `TreeSettingsMenu.tsx`, `useTreeSettings.ts`      |
| `graph`                    | `queries.ts`, `nodeIcon.ts`, `NodeInfoTooltip.tsx`, `nodeLabel.ts` |
| `routing`                  | `searchCodec.ts`                                                   |

The platform layers (`design-system`, `graphql`, `testing`) expose their files directly (shadcn convention / generated client).

## Tooling

These rules are enforced by ESLint (`eslint-plugin-boundaries`) and by
`dependency-cruiser` (cycle backstop) — see `eslint.config.js` and
`.dependency-cruiser.cjs`.
