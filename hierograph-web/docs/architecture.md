# Frontend Architecture

The frontend is organised into feature _verticals_ as top-level folders under
`src/`. Each vertical owns a specific screen or shared concern. Dependencies
between verticals flow only in the directions listed in the table below — the
dependency graph is a DAG (directed acyclic graph), i.e. the DSM is
lower-triangular.

## Verticals

| Vertical        | Scope                                                                  |
| --------------- | ---------------------------------------------------------------------- |
| `frame`         | the app shell every screen is rendered into                            |
| `start`         | the start screen (`/`)                                                 |
| `design-system` | domain-agnostic UI components and tokens (depends on no vertical)      |
| `graphql`       | data-access platform (GraphQL client and generated code)               |
| `routes`        | thin route wiring                                                      |
| `routing`       | shared search-param codec (comma id lists, enum guards) for the router |
| `testing`       | test infrastructure                                                    |

## Allowed Dependency Direction (DAG)

| From                                 | May import                                         |
| ------------------------------------ | -------------------------------------------------- |
| app (`main.tsx`, `routeTree.gen.ts`) | `routes`, `routing`, `graphql`, `design-system`    |
| `routes`                             | `frame`, `start`, `routing`, `design-system`       |
| `start` (screen)                     | `frame`, `graphql`, `design-system`                |
| `frame`                              | `routing`, `graphql`, `design-system`              |
| `design-system`                      | (nothing internal — only itself)                   |
| `graphql`                            | (nothing internal)                                 |
| `routing`                            | (nothing internal — only `@tanstack/react-router`) |
| `test` / `testing`                   | anything                                           |

The `frame` owns what applies to every screen. Screens are verticals like
`start`: they may import the `frame`, never each other, and the `frame` never
imports a screen. A new screen gets its own vertical, its own route under
`src/routes/`, and a rule in `eslint.config.js` like the one for `start`.

## Router context & data loading

`createRouter` is called with `context: { queryClient }`, and the root route is
typed with `createRootRouteWithContext<{ queryClient: QueryClient }>()`. This
makes `queryClient` available to any route loader via `context.queryClient`.

Route loaders with `ensureQueryData` are deliberately **not** used by default.
In a pure client-side SPA, a loader starts a fetch only microscopically earlier
than the component's `useQuery`, and a blocking loader replaces
component-owned pending/error UI with router-level
`pendingComponent`/`errorComponent`. The `queryClient` context injection keeps
loaders possible where the tradeoff changes.

## Serving path

The Hierograph server serves the built UI under `/ui/`, next to `/graphql` on
the same origin. Vite's `base` is `/ui/`, and the router's `basepath` follows
it (`import.meta.env.BASE_URL` in `main.tsx`). In development the Vite dev
server runs on port 3080 with the same base and proxies `/graphql` to the
server on port 8765.

## URL state / deep-linking

The navigationally relevant selection/view state is held in **typed search
params** (`validateSearch`), so the URL is the source of truth: Back/Forward,
shareable deep links, and URLs built from outside the app (the agent hands out
links into the UI) all restore the view.

- **Each screen owns and validates its own params** in its route. The root
  route owns only frame-level params; it doesn't know any screen's params.
- The `routing` vertical owns the shared codec (`searchCodec.ts`), wired into
  the router as custom `parseSearch`/`stringifySearch`. By convention, a param
  ending in **`_ids`** is a comma-separated id list (`subject_ids=42,43`, not
  TanStack's JSON arrays — readable, hand-buildable); a param referencing a
  single node ends in `_id`. Enum params go through `parseEnum`.
- Unknown or invalid values are silently dropped, so a stale or hand-built URL
  never crashes validation.
- **Node ids are stable**: an id names the same node across reloads of the
  graph, so links use ids directly.

Consumers derive state directly from `useSearch` and write only via `navigate`
(no mirror `useState`, no URL→state→navigate effect). Committed selections push
a history entry; view toggles use `replace`. Display preferences that aren't
navigation state stay in localStorage.

## Public-API Rule

Verticals (`frame`, `start`, `routing`) are entered only through their
**declared public files** — the explicit per-vertical allow-lists in
`eslint.config.js` (`boundaries/dependencies`). Cross-vertical imports use
direct paths (`@/<vertical>/<File>`); there are no re-export barrel `index.ts`
files.

| Vertical  | Public files     |
| --------- | ---------------- |
| `frame`   | `AppFrame.tsx`   |
| `start`   | `StartPage.tsx`  |
| `routing` | `searchCodec.ts` |

The platform layers (`design-system`, `graphql`, `testing`) expose their files
directly (shadcn convention / generated client).

## Tooling

These rules are enforced by ESLint (`eslint-plugin-boundaries`) and by
`dependency-cruiser` (cycle backstop) — see `eslint.config.js` and
`.dependency-cruiser.cjs`.
