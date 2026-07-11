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
| `testing`                  | test infrastructure                                                                         |

## Allowed Dependency Direction (DAG)

| From                                         | May import                                                                                         |
| -------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| app (`main.tsx`, `routeTree.gen.ts`)         | `routes`, `graphql`, `design-system`                                                               |
| `routes`                                     | `dsm`, `cross-reference-explorer`, `dependency-diagram`, `selection`, `dev-panel`, `design-system` |
| `dsm` / `cross-reference-explorer` (screens) | `dependency-details`, `selection`, `tree`, `graph`, `design-system`, `graphql`                     |
| `dependency-diagram` (screen)                | `selection`, `tree`, `graph`, `design-system`, `graphql`                                           |
| `dependency-details` (shared pane)           | `selection`, `tree`, `graph`, `design-system`, `graphql`                                           |
| `dev-panel`                                  | `selection`, `graph`, `design-system`, `graphql`                                                   |
| `tree`                                       | `graph`, `design-system`                                                                           |
| `graph`                                      | `graphql`, `design-system`                                                                         |
| `selection`                                  | (nothing internal)                                                                                 |
| `design-system`                              | (nothing internal — only itself)                                                                   |
| `graphql`                                    | (nothing internal)                                                                                 |
| `test` / `testing`                           | anything                                                                                           |

The `dsm` and `cross-reference-explorer` verticals are _screen verticals_ —
each owns a top-level screen and composes the shared `dependency-details`
pane into that screen. `dependency-details` is a _shared pane_ with no
knowledge of which screen uses it. This screen-→-pane layering is
deliberately directed: `dependency-details` does not import `dsm` or
`cross-reference-explorer`, keeping the DSM lower-triangular (no back-edge,
no cycle).

`dependency-diagram` is a third, experimental screen vertical (`/dependency-diagram`).
It does not compose `dependency-details` (no details pane in this screen) and,
like `cross-reference-explorer`, builds its own tree wiring directly from the
shared `tree`/`graph` primitives rather than importing another screen's
internals — there is no `dsm` → `dependency-diagram` edge (or vice versa);
screen verticals never import each other.

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

## URL state / deep-linking (deferred)

`cellSelection` (DSM cell) and `selectedIds` are intentionally **not** exposed
as validated search params in this release. Deep-linking is a UX design question
that spans the entire workbench state:

- `cellSelection` alone does not restore the full DSM view — `DependencyMatrix`
  renders only when `selectedIds` are set; the upper pane would be blank on
  reload.
- `DependencyDetailsPane` loads edge-based data directly from `selectedIds` and
  could technically be restored in isolation, but the DSM highlight
  (`selectedCell`) would be missing.
- `DependencyMatrix` has an active `useEffect(() => setCellSelection(null),
[selectedIds])` reset; serialising `cellSelection` alongside `selectedIds`
  requires careful ordering.

`DependencyPartnersPanel` is the second public file of `dependency-details`: an
anchor-centric aggregate overview (tier 1) used by the Cross-Reference
Explorer, whereas `DependencyDetailsPane` remains the DSM inspector.

Deep-linking belongs in a dedicated follow-up task with a holistic UX design for
workbench-state restoration. `zod` will not be introduced until that task.

## Selection & DevPanel provider scoping

**One `SelectionProvider` per screen.** `DsmPage`,
`CrossReferenceExplorerPage`, and `DependencyDiagramPage` each mount their own
`SelectionProvider` — there is deliberately no global `SelectionProvider`. The globally-rendered
`DevPanel` does not read any screen's `SelectionProvider`; instead it reads
the current focus through a purpose-built global `FocusBridge`
(`selection/FocusBridge.tsx`), mounted once at the root around both the
router outlet and the panel. `SelectionProvider` mirrors its `focusedId`/
`focusedName` into the bridge whenever one is present above it (a no-op
otherwise, e.g. in browsertests that render a standalone `SelectionProvider`).

This was chosen over the alternative of a single global `SelectionProvider`
(with the Cross-Reference Explorer's local reset made explicit on route
change): per-screen providers give each screen a clean reset on remount
instead of an imperative global reset that is easy to get subtly wrong, keep
each screen's selection lifecycle self-contained, and decouple the panel's
focus read from screen internals via the bridge.

## Public-API Rule

Verticals (`dsm`, `cross-reference-explorer`, `dependency-diagram`,
`dependency-details`, `dev-panel`, `selection`, `tree`, `graph`) are entered
only through their **declared public files** — the explicit per-vertical
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
| `selection`                | `SelectionContext.tsx`, `FocusBridge.tsx`                          |
| `tree`                     | `AsyncTree.tsx`, `TreeSettingsMenu.tsx`, `useTreeSettings.ts`      |
| `graph`                    | `queries.ts`, `nodeIcon.ts`, `NodeInfoTooltip.tsx`, `nodeLabel.ts` |

The platform layers (`design-system`, `graphql`, `testing`) expose their files directly (shadcn convention / generated client).

## Tooling

These rules are enforced by ESLint (`eslint-plugin-boundaries`) and by
`dependency-cruiser` (cycle backstop) — see `eslint.config.js` and
`.dependency-cruiser.cjs`.
