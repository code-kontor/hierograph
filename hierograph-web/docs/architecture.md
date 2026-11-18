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
`src/routes/`, an entry in the screen registry (`SCREENS` in
`src/routes/__root.tsx`), and a rule in `eslint.config.js` like the one for
`start`.

## The frame

`AppFrame` renders the header (brand, navigation from the screen registry, the
hierarchy view, the graph status) and the matched screen. It owns:

- **The graph status.** It polls `server { graph { state update generation } }`
  every 5 s (`frame/queries.ts`), paused while the tab is hidden, with an
  immediate check when it becomes visible again.
- **The graph scope.** `useGraphScope()` gives a screen the scope its graph
  queries run in: the graph `generation` and the hierarchy `view`. Every graph
  query puts it into its key with `graphQueryKey(vertical, scope, ...)` and
  waits until it is defined (`enabled: scope !== undefined`). Within one
  generation the cache never goes stale (`staleTime: Infinity`); a new
  generation refetches everything, with no manual invalidation.
- **The hierarchy view.** `view` is the only search param of the root route,
  kept across screen switches (`retainSearchParams`). The frame checks it
  against the server's views and shows a message, with a way to drop it, for an
  unknown or invalid one.
- **Messages instead of a screen.** A registry entry says whether its screen
  `needsGraph`. Without a graph, or when the server can't be reached, the frame
  shows a message instead of such a screen; the start screen doesn't need a
  graph and stays visible.

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

| Vertical  | Public files                                   |
| --------- | ---------------------------------------------- |
| `frame`   | `AppFrame.tsx`, `GraphScope.tsx`, `screens.ts` |
| `start`   | `StartPage.tsx`                                |
| `routing` | `searchCodec.ts`                               |

The platform layers (`design-system`, `graphql`, `testing`) expose their files
directly (shadcn convention / generated client).

## Tooling

These rules are enforced by ESLint (`eslint-plugin-boundaries`) and by
`dependency-cruiser` (cycle backstop) — see `eslint.config.js` and
`.dependency-cruiser.cjs`.
