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
| `structure`     | the levelized structure map (`/structure`)                             |
| `design-system` | domain-agnostic UI components and tokens (depends on no vertical)      |
| `graphql`       | data-access platform (GraphQL client and generated code)               |
| `routes`        | thin route wiring                                                      |
| `routing`       | shared search-param codec (comma id lists, enum guards) for the router |
| `testing`       | test infrastructure                                                    |

## Allowed Dependency Direction (DAG)

| From                                 | May import                                                |
| ------------------------------------ | --------------------------------------------------------- |
| app (`main.tsx`, `routeTree.gen.ts`) | `routes`, `routing`, `graphql`, `design-system`           |
| `routes`                             | `frame`, `start`, `structure`, `routing`, `design-system` |
| `start`, `structure` (screens)       | `frame`, `graphql`, `design-system`                       |
| `frame`                              | `routing`, `graphql`, `design-system`                     |
| `design-system`                      | (nothing internal — only itself)                          |
| `graphql`                            | (nothing internal)                                        |
| `routing`                            | (nothing internal — only `@tanstack/react-router`)        |
| `test` / `testing`                   | anything                                                  |

The `frame` owns what applies to every screen. Screens are verticals like
`start`: they may import the `frame`, never each other, and the `frame` never
imports a screen.

## Folders

| Folder               | Holds                                                                                  |
| -------------------- | -------------------------------------------------------------------------------------- |
| `src/frame/`         | `AppFrame`, the graph status, the graph scope, the screen registry type, frame queries |
| `src/start/`         | the start screen, its query and display helpers                                        |
| `src/structure/`     | the structure map, its queries and label helpers                                       |
| `src/routes/`        | one file per route; `__root.tsx` holds the screen registry and the frame-level params  |
| `src/routing/`       | the search-param codec                                                                 |
| `src/graphql/`       | the client (`execute`) and the generated documents and types (`generated/`)            |
| `src/design-system/` | shadcn components (`ui/`), layouts (`layout/`), small hooks                            |
| `src/testing/`       | render helpers, MSW handlers and worker, the recorded fixtures and their recorder      |

## Adding a screen

A screen registers with the frame in four places; the frame itself doesn't
change.

1. **A vertical** `src/<screen>/` with the screen component and its
   `queries.ts`. Graph queries are keyed with
   `graphQueryKey("<screen>", useGraphScope(), …)` and wait for the scope.
2. **A route** `src/routes/<screen>.tsx` (create it empty, run
   `pnpm routes:generate`), rendering the screen and validating the screen's
   own search params.
3. **A registry entry** in `SCREENS` (`src/routes/__root.tsx`): `path`, the
   navigation `label`, and `needsGraph`. The frame builds the navigation from
   it and shows a message instead of a screen that needs a graph while none is
   loaded.
4. **Boundary rules** in `eslint.config.js`: an element for the vertical, its
   public files in `PUBLIC`, a rule like the one for `start`, and the vertical
   added to what `routes` may import. Update the tables in this document in
   the same change.

## The frame

`AppFrame` renders the header (the logo and "hierograph – <project>", the
project the server serves; navigation from the screen registry; the hierarchy
view; the graph status) and the matched screen. It owns:

- **The graph status.** It polls
  `server { workspace viewsVersion graph { state update generation } }` every
  5 s (`frame/queries.ts`), paused while the tab is hidden, with an immediate
  check when it becomes visible again. A new `generation` means a reloaded
  graph; a new `viewsVersion` that a view document was added, changed or
  removed, for example by the agent, which doesn't change the generation.
- **The graph scope.** `useGraphScope()` gives a screen the scope its graph
  queries run in: the graph `generation`, the hierarchy `view` and, with a
  view, the `viewsVersion`. Every graph query puts it into its key with
  `graphQueryKey(vertical, scope, ...)` and waits until it is defined
  (`enabled: scope !== undefined`). Within one generation the cache never goes
  stale (`staleTime: Infinity`); a new generation refetches everything, and a
  changed view document everything in a view, with no manual invalidation.
- **The hierarchy view.** `view` is the only search param of the root route,
  kept across screen switches (`retainSearchParams`). The frame checks it
  against the server's views, fetched again with every new generation or
  `viewsVersion`, so a view defined after the page was loaded can be chosen at
  once. It shows a message, with a way to drop it, for an unknown or invalid
  one.
- **Messages instead of a screen.** A registry entry says whether its screen
  `needsGraph`. Without a graph, or when the server can't be reached, the frame
  shows a message instead of such a screen; the start screen doesn't need a
  graph and stays visible.
- **Screens come back as they were left.** The frame remembers each screen's
  own search params, per hierarchy view, in memory, and its navigation links
  restore them: going back to a screen finds the tree or the map with the
  same nodes open and the same arcs pinned.

## The start screen

`StartPage` shows what the server serves. On wide screens the hierarchy tree
is on the left and the rest on the right, the views above the tabs; both
splits are moved by the user and kept by the browser (the resizable panels'
own storage), each box scrolling on its own. Narrow screens stack the views,
the tabs and the tree.

- **Right, on top:** the hierarchy views, as cards (the default, all of one
  size) or a list, switched in the box's corner and kept in localStorage;
  choosing a valid one sets `view=` and keeps only the `tab`, so the tree of
  the new view starts collapsed. The scanned hierarchy comes first as
  `default`: it isn't a view name, so choosing it drops `view=`.
- **Right, below:** a tab each for the workspace and its graph source (with the
  artifacts not compiled yet), the graph (state, generation, load time,
  counts), the server, and its JVM (heap against its limit and what the last
  collection kept, threads, CPU load, garbage collection). The tab is the start
  screen's own search param `tab` (`workspace`, `graph`, `server`, `jvm`;
  validated in `routes/index.tsx`), so a link can open one; switching replaces
  the history entry, and the first tab leaves no param behind.
- **Left:** the hierarchy tree (`HierarchyTree`) of the current hierarchy
  view. It loads level by level as nodes are
  expanded, with queries keyed by the graph scope. Its open rows and pinned
  arcs are the start screen's search params `expanded_ids` (expanding replaces
  the history entry) and `pinned_id`, or `pinned_cycle_ids` for a cycle's
  arcs (pinning pushes one). Each level's siblings are in
  dependency order (`orderedAdjacencyMatrix`: those depending on the others on
  top), dependency cycles marked with a bar; the tree ends at the types (their
  members' dependencies aren't resolved yet). Directly to the right of the labels, `TreeArcs` draws, for the
  hovered row only, thin bracket arcs to the visible rows it depends on and
  that depend on it (rows whose children aren't shown, External Types and its
  rows included), each with an arrowhead on the row depended on: with the
  order to the right, against it to the left and dashed; what the row uses
  blue, what uses it grey, whichever way they run; by weight.
  Hovering a cycle bar draws instead the arcs between the cycle's members
  (rows inside an expanded member counted to it), in the bar's colour: the
  whole cycle, with the edges that close it against the order. It measures the
  rows marked `data-frontier` whenever the tree changes and loads their
  dependencies in one query.

The server data is live: its query is polled like the graph status and keyed
by the generation, so a reload shows at once. The start screen handles a server
that can't be reached itself, since the frame doesn't block a screen without
`needsGraph`.

## The structure map

`StructurePage` (`/structure`, needs a graph) shows only the levelized
structure map of the current hierarchy view, ported from the
`hierograph-structure` skill's static page (`assets/lsm-template.html`, its
look in `structure-map.css`): every container's children in rows by level,
bottom first, so a box sits above what it uses; the members of a cycle
together in a red tangle frame, in their own rows; and only the dependencies
against the levels drawn, as dashed arrows in the cycle colour. Hovering a box traces what it
uses (accent) and what uses it (grey) among the visible boxes, with rings and
arrows, and greys out the rest.

- **Data.** The root and each expanded container load their levelization
  (`node(id) { children { levelization } }`, #61), keyed by the graph scope;
  `mapModel.ts` builds the visible map from them: the boxes, the open
  containers' rows, the frontier (boxes whose content isn't shown) and the
  feedback dependencies. In a module, a chain of packages that each hold
  nothing but the next package is folded into one box, its last package
  named in full (`io.hierograph.server`); the chain's packages load their
  levelization too, and the module opens once they have. The first hovered box loads the matrix over the
  frontier (`orderedAdjacencyMatrix`) for the weights; hovering traces a
  box's dependencies, its context menu pins them (as the start screen's tree
  pins its arcs), Esc or the pane's unpin button unpins them.
- **Layout.** A box takes a share of its row by how many boxes are visible in
  it, and is never narrower than its content; a map wider than the pane
  scrolls. The arrows are measured from the boxes whenever the map changes or
  is resized (`mapArrows.ts`, the template's routing).
- **URL.** `expanded_ids` (expanding replaces the history entry) and
  `pinned_id` (pinning pushes one), validated in `routes/structure.tsx`.

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

Verticals (`frame`, `start`, `structure`, `routing`) are entered only through their
**declared public files** — the explicit per-vertical allow-lists in
`eslint.config.js` (`boundaries/dependencies`). Cross-vertical imports use
direct paths (`@/<vertical>/<File>`); there are no re-export barrel `index.ts`
files.

| Vertical    | Public files                                   |
| ----------- | ---------------------------------------------- |
| `frame`     | `AppFrame.tsx`, `GraphScope.tsx`, `screens.ts` |
| `start`     | `StartPage.tsx`                                |
| `structure` | `StructurePage.tsx`                            |
| `routing`   | `searchCodec.ts`                               |

The platform layers (`design-system`, `graphql`, `testing`) expose their files
directly (shadcn convention / generated client).

## Tooling

These rules are enforced by ESLint (`eslint-plugin-boundaries`) and by
`dependency-cruiser` (cycle backstop) — see `eslint.config.js` and
`.dependency-cruiser.cjs`.
