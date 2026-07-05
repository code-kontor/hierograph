# Frontend Architecture

The frontend is organised into feature _verticals_ as top-level folders under
`src/`. Each vertical owns a specific screen or shared concern. Dependencies
between verticals flow only in the directions listed in the table below — the
dependency graph is a DAG (directed acyclic graph), i.e. the DSM is
lower-triangular.

## Verticals

| Vertical             | Scope                                                     |
| -------------------- | --------------------------------------------------------- |
| `dependencies`       | DSM screen (`/dependencies`)                              |
| `cross-reference`    | cross-reference screen                                    |
| `dependency-details` | shared inspector pane                                     |
| `hierarchy`          | hierarchy tree browser                                    |
| `selection`          | cross-view workbench selection state                      |
| `tree`               | async tree widget and settings                            |
| `graph`              | shared node-domain concepts (icons, labels, node queries) |
| `design-system`      | domain-agnostic UI components (depends on no vertical)    |
| `graphql`            | data-access platform (Apollo client and generated code)   |
| `routes`             | thin route wiring                                         |
| `testing`            | test infrastructure                                       |

## Allowed Dependency Direction (DAG)

| From                                              | May import                                                                                                          |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| app (`main.tsx`, `routeTree.gen.ts`)              | `routes`, `graphql`, `design-system`                                                                                |
| `routes`                                          | `dependencies`, `cross-reference`, `dependency-details`, `hierarchy`, `selection`, `tree`, `graph`, `design-system` |
| `dependencies` / `cross-reference` (screens)      | `hierarchy`, `dependency-details`, `selection`, `tree`, `graph`, `design-system`, `graphql`                         |
| `dependency-details` / `hierarchy` (shared panes) | `selection`, `tree`, `graph`, `design-system`, `graphql`                                                            |
| `tree`                                            | `graph`, `design-system`                                                                                            |
| `graph`                                           | `graphql`, `design-system`                                                                                          |
| `selection`                                       | (nothing internal)                                                                                                  |
| `design-system`                                   | (nothing internal — only itself)                                                                                    |
| `graphql`                                         | (nothing internal)                                                                                                  |
| `test` / `testing`                                | anything                                                                                                            |

The `dependencies` and `cross-reference` verticals are _screen verticals_ —
each owns a top-level screen and composes the shared panes (`hierarchy`,
`dependency-details`) into that screen. `hierarchy` and `dependency-details`
are _shared panes_ with no knowledge of which screen uses them. This
screen-→-pane layering is deliberately directed: `hierarchy` and
`dependency-details` do not import `dependencies` or `cross-reference`,
keeping the DSM lower-triangular (no back-edge, no cycle).

## Router context & data loading

`createRouter` is called with `context: { queryClient }`, and the root route is
typed with `createRootRouteWithContext<{ queryClient: QueryClient }>()`. This
makes `queryClient` available to any route loader via `context.queryClient`.

Route loaders with `ensureQueryData` are deliberately **not** introduced for the
root-node fetch. The addressed waterfall is single-level (route match →
component render → `useQuery`). In a pure client-side SPA, a loader would start
the fetch only microscopically earlier — before rather than immediately after the
first render — with no real latency benefit. `HierarchyTree` and
`CrossReferenceView` each carry their own pending/error UI; a blocking loader
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
- `cellSelection` is also set from `/xref` via _Inspect_ (no DSM context there).

Deep-linking belongs in a dedicated follow-up task with a holistic UX design for
workbench-state restoration. `zod` will not be introduced until that task.

## Public-API Rule

Verticals (`dependencies`, `cross-reference`, `dependency-details`,
`hierarchy`, `selection`, `tree`, `graph`) are entered only through their
**declared public files** — the explicit per-vertical allow-lists in
`eslint.config.js` (`boundaries/dependencies`). Cross-vertical imports use
direct paths (`@/<vertical>/<File>`); there are no re-export barrel `index.ts`
files. The platform layers (`design-system`, `graphql`, `testing`) expose their
files directly (shadcn convention / generated client).

## Tooling

These rules are enforced by ESLint (`eslint-plugin-boundaries`) and by
`dependency-cruiser` (cycle backstop) — see `eslint.config.js` and
`.dependency-cruiser.cjs`.
