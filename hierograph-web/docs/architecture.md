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

| Folder               | Holds                                                                                                                                                                                        |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/frame/`         | `AppFrame`, the graph status and its panel (`ServerStatus`), the hierarchy view menu, the graph scope, the screen registry type, the context menus' Copy submenu (`CopyMenu`), frame queries |
| `src/start/`         | the start screen, its query and display helpers                                                                                                                                              |
| `src/structure/`     | the structure map, its queries and label helpers                                                                                                                                             |
| `src/routes/`        | one file per route; `__root.tsx` holds the screen registry and the frame-level params                                                                                                        |
| `src/routing/`       | the search-param codec                                                                                                                                                                       |
| `src/graphql/`       | the client (`execute`) and the generated documents and types (`generated/`)                                                                                                                  |
| `src/design-system/` | shadcn components (`ui/`), layouts (`layout/`), small hooks                                                                                                                                  |
| `src/testing/`       | render helpers, MSW handlers and worker, the recorded fixtures and their recorder                                                                                                            |

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
   navigation `label`, `needsGraph`, and optionally `keepOnViewChange`: the
   search params that don't depend on the hierarchy view. The frame builds the
   navigation from it and shows a message instead of a screen that needs a
   graph while none is loaded.
4. **Boundary rules** in `eslint.config.js`: an element for the vertical, its
   public files in `PUBLIC`, a rule like the one for `start`, and the vertical
   added to what `routes` may import. Update the tables in this document in
   the same change.

## The frame

`AppFrame` renders the header (the logo and "hierograph – <project>", the
project the server serves; navigation from the screen registry; the hierarchy
view menu; the graph status) and the matched screen. It owns:

- **The graph status.** It polls
  `server { workspace viewsVersion commit artifacts graph { state update generation } }`
  every 5 s (`frame/queries.ts`), paused while the tab is hidden, with an
  immediate check when it becomes visible again. A new `generation` means a
  reloaded graph; a new `viewsVersion` that a view document was added, changed
  or removed, for example by the agent, which doesn't change the generation.
  Artifacts not compiled yet and a server built from uncommitted changes need
  attention: with the graph loaded, the indicator then names the first of
  them instead of "Graph loaded" ("1 artifact not compiled yet", "+1" for
  each more), with a warning sign in place of the green dot; its tooltip and
  the panel list them all.
- **The status panel.** Clicking the graph status opens a popover
  (`ServerStatus`) with a tab each for the graph (state, generation, load
  time, counts; the first), the workspace and its graph
  source (with the artifacts not compiled yet), the server, and its JVM (heap against its limit and what the
  last collection kept, threads, CPU load, garbage collection). Below the
  graph's facts, for a hierarchy view with exclusions, what the view excludes
  (`ExcludedNodes`, from `views { excludedCount excludedNodes }`): an info
  `Notice` marked "Excluded" with a crossed-out eye, one line with the counts,
  and on "Show them" the outermost excluded nodes, named and sorted as the
  tree names its rows (the names and icons in `design-system/graph`, shared
  with the tree), scrolling on their own.
  A click opens it on the tab that explains what the indicator shows: the
  graph when there is none or the server can't be reached, the workspace for
  artifacts not compiled yet, the server for uncommitted changes, otherwise
  the graph. The open tab is
  the frame-level search param `status` (`graph`, `workspace`, `server`,
  `jvm`; validated by the root route), so a link can open it; opening,
  switching and closing replace the history entry. Its query
  (`ServerStatus`) runs only while the panel is open, polled like the graph
  status and keyed by the graph generation.
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
  one. The header's `HierarchyViewMenu` shows the current view and chooses
  another on every screen: the scanned hierarchy first as `default` (not a
  view name, so choosing it drops `view=`), then the views under their
  source's label ("Project views", "Runtime views"). Every row has the same
  structure: the name (with a "restructuring" badge), then the same fields
  for all (its groups, and what it excludes by the kind of its outermost
  excluded nodes), then its description, at most two lines with the whole
  text on hover. An invalid view is listed with its problems but can't be
  chosen. Choosing one stays on the screen and
  restores it as it was last left in that view, keeping only the params the
  screen names in `keepOnViewChange` (none yet): the tree of another view
  starts collapsed.
- **Messages instead of a screen.** A registry entry says whether its screen
  `needsGraph`. Without a graph, or when the server can't be reached, the frame
  shows a message instead of such a screen; the start screen doesn't need a
  graph and stays visible.
- **Screens come back as they were left.** The frame remembers each screen's
  own search params, per hierarchy view, in memory, and its navigation links
  restore them: going back to a screen finds the tree or the map with the
  same nodes open and the same selection (on the map: pinned, too).

## The start screen

The start screen (`/`, "Dependencies" in the header) is where the UI opens.
`StartPage` is the hierarchy tree of the current hierarchy view and, beside
it, the dependencies of its selection (#77); the view is chosen in the header,
and the server's state, with what the view excludes, is in the header's status
panel. On wide screens the tree is on the left and the dependencies on the
right (30/70 until the user moves the split, which the browser keeps; the
dependencies' own split, trees above the list, is 40/60); narrow screens
stack them. On a wide screen the hierarchy collapses to a rail at the left
edge, to make room for the dependencies: with the button in its title bar, by
dragging the splitter to the edge, or by double-clicking the splitter. The
rail names the selection and brings the hierarchy back. The collapse is the
start screen's search param `hierarchy_collapsed` (replacing the history
entry), so a link can open the page with it; the URL wins over the layout the
browser remembers.

- **The dependencies** (`DependencyTrees`) are a pane with three trees for
  the selection (`selected_ids`: one node, or a group of them taken together;
  a selected cycle asks for rows instead), read
  left to right from source to target: "Used by", the sources of its
  incoming dependencies; "Selection", the selected nodes, open, complete (the hierarchy
  tree's own levels); "Uses", the targets of its
  outgoing dependencies. A group's tops are its nodes in the order selected,
  its sentences name "the selected nodes", the heading its size (the names as
  its tooltip), and a pick in a side tree keeps dark only the selected nodes
  it reaches. The dependencies are the selection's two dependency sets
  (`NodeSet.incomingDependencies` / `outgoingDependencies`, for one node the
  same as `Node`'s, #79: those crossing the border around all the selected
  nodes, the ones between them left out; for one node,
  its boundary). Each tree scrolls on its own; a side without dependencies
  says so. So that the side trees aren't taken for the hierarchy (with it
  collapsed, "Used by" sits where it was), the pane's title names the
  selection ("Dependencies of lib"), with the button that clears the
  selection (the hierarchy only announces it, `SelectionAnnouncement`, for
  screen readers: the name isn't repeated on screen); and each row of "Used
  by" and "Uses" counts the selection's
  dependencies it holds, with the references in its tooltip
  (`filteredChildrenWithCounts`), which the hierarchy never shows. The trees are three equal columns;
  arrows between the titles point the way the dependencies go, and each
  title's tooltip says what its tree holds. A quiet switch in the pane's title bar, three small icons in one outline, apart from the `?` (the selection as a bar, arrows into it, through it, out of it: Incoming, Both, Outgoing; the chosen one on the secondary selection's fill, edged, the names as their tooltips; a radio group with one tab stop and the arrow keys), shows one direction at a time: "Incoming" hides "Uses" and the list's outgoing tab, "Outgoing" hides "Used by" and the incoming tab, and the list's remaining tab is a plain heading. A pick on the hidden side stays in the URL, ignored, and is back with its side; with one side shown, picking there leaves it. A direction with no dependencies offers the other one where it says so, in its tree and the list ("14 outgoing dependencies · Show"). The hierarchy's arcs keep both directions, the hidden one's dimmed. It is the search param `dependency_directions` (`incoming`, `outgoing`; none for both, replacing the history entry), and clearing the selection (the heading's button, Esc, the hierarchy) brings back both. The pane's `?` lists the trees'
  gestures and what the greyed rows mean.
  - **The trees**: "Used by" and "Uses" are the sets' sides
    (`filteredChildrenWithCounts` on a set, `SOURCE` or `TARGET`, with
    `compact`; the marks use the same field on the narrowed set);
    "Selection" is the hierarchy tree's levels below the node. All are
    folded and labelled exactly as the
    hierarchy tree (#63: the server's display children, the label relative
    to the row above, the skipped packages in the title and description),
    loaded a level at a time, stopping at types. Each level is in the
    hierarchy tree's order for the same parent (dependency order, from the
    same cached level query), External Types last at the top level. Each tree's open rows are in the URL
    (`used_by_expanded_ids`, `selection_expanded_ids`, `uses_expanded_ids`,
    the rows below its tops), so a link restores them; the Selection tree's
    tops, the selected nodes, open with it and can be closed like any row
    (`selection_collapsed_ids`, the rows below kept for their reopening).
    Selecting another node drops them with the picks.
  - **A row's context menu** (right-click): in the Selection tree, Select
    makes the row the selection, as selecting it in the hierarchy tree
    would: the picks and the trees' open rows go, and the hierarchy tree
    opens down to it (its display ancestors, `predecessors(compact: true)`,
    added to `expanded_ids`) and scrolls it into view; disabled on the
    selection itself. On a row that can open, Expand all opens it and
    everything in it down to the types (its levels loaded first, then opened
    at once); Collapse all closes them. Copy (every row) copies its names
    and paths.
  - **Picking** a row (a click; the chevron opens it) keeps what it connects
    to dark in the other trees and fades the rest (half opacity: name, icon and count; weight carries
    no state), the trees staying complete, and narrows the lists
    to it: `used_by_id`, `selection_part_id`, `uses_id`, replacing the
    history entry; a click on the picked row drops it. One direction at a
    time: a pick in "Used by" drops the one in "Uses" and the other way round
    (a link may still name both); the part stays. A user marks the parts
    of the selection it uses, a used node the parts that use it (both, from a
    link, the parts connected to both); a part marks its users and what it uses. The
    marks are the set narrowed by the picks (`filteredDependencies`, then
    `filteredChildren`), asked for below the connected rows only. A pick is a filter,
    not the hierarchy's selection, so it is marked with a bar (the secondary
    selection tokens) rather than filled. Picking a user opens the list's
    incoming tab, picking a used node its outgoing tab. A link that names a
    pick opens its tree down to it (the pick's display ancestors,
    `predecessors(compact: true)`) and scrolls it into view. A closed row
    above the pick hides the filter, so it carries the pick's bar without
    the fill ("contains the picked node"), and its menu's "Reveal the pick"
    opens the rows down to it. Selecting another node in the hierarchy drops
    the picks.

- **The dependency list** (`DependencyListPane`) is a pane below the trees (the
  right column is split; narrow screens
  stack it last) with the tabs "Incoming dependencies" and "Outgoing
  dependencies", each narrowed by the picks on its side (incoming: the user
  and the Selection tree's pick; outgoing: that pick and the used node; each chip is named after its tree). The tab is the start
  screen's search param `dependency_list` (`incoming`, `outgoing`; validated
  in `routes/index.tsx`), so a link can open one; switching replaces the
  history entry, and the first tab leaves no param behind. Each tab pages its
  set 50 type-level dependencies at a time (`dependencyPage`) as a table as wide as the pane, its header untinted: source and target, sharing the width, each as `<module>:<package>.<Class>` (module and package subdued, giving way before the class when narrow; the module from the type's ancestors), what its references are by kind, the most frequent first (`Dependency.kinds`: `calls 2, has type 1`), and the references. The columns' widths are set on the borders between their headings (drag, or ← → when focused; a double-click restores the defaults), one set for both tabs, kept by the browser (`ColumnWidths`). The selection's types group the rows, outgoing by source, incoming by target (each page put in target order first): in a run of rows with the same one only the first names it;
  each row's tooltip and name keep both ends in full. Above it the count with
  the references they stand for (`references`), a chip
  per pick that narrows it (a click drops the pick) and the paging. The page
  is local and starts over with each narrowing.

- **The tree** (`HierarchyTree`) of the current hierarchy
  view, folded like the structure map (`children(compact: true)`, #63): a
  chain of lone packages is one row, its last package, whose label's tooltip
  and the row's description name the packages it stands for (every label's
  tooltip has the node's full name; a long label is cut in the middle,
  keeping its end, `MiddleTruncated`, so siblings that differ at the end stay
  apart). It loads level by level as nodes are
  expanded, with queries keyed by the graph scope. Its open rows and its selection are the start
  screen's search params `expanded_ids` and `selected_ids`, or
  `selected_cycle_ids` for a cycle (both replace the history entry; an older
  link's `selected_id` is read as a list of one). It has
  no pin: what to look at is explored in the dependency pane, so a click in
  the tree always selects one row. ⌘- or Ctrl-click (⌘/Ctrl+Enter or Space,
  and the row's menu) adds a row to the selected rows or takes it out: they
  are taken together as a group. A row can't be grouped with one above or
  below it in the hierarchy: adding it drops those (their display ancestors,
  `DependencyPickPath`, tell). A collapsed row above a selected one carries
  the selection's bar without its fill ("contains the selection"); its
  menu's "Reveal the selection" expands the rows down to it. Each level's siblings are in
  dependency order (`orderedAdjacencyMatrix`: those depending on the others on
  top), dependency cycles marked with a bar; the tree ends at the types (their
  members' dependencies aren't resolved yet). Directly to the right of the labels, `TreeArcs` draws, for the
  selected rows only (a group's arcs those crossing the border around all of
  them), thin bracket arcs to the visible rows it depends on and
  that depend on it (rows whose children aren't shown, External Types and its
  rows included), each with an arrowhead on the row depended on: with the
  order to the right, against it to the left and dashed; what the row uses
  blue, what uses it in the lighter grey of the secondary bar, whichever way
  they run; quiet (1 px) as context, at full strength (by weight) while the
  pointer is over one of their ends. On each side, what the row uses and what uses it run in lanes of their own, each set evenly spaced and the smaller one centred on the larger (the shortest span innermost, so the brackets nest; closer together when they don't fit, never on one line). The arcs take only the room they need
  beside the labels (`--arc-gutter` on the rows; none without a selection),
  reaching out less when a label would get narrower than 120 px.
  Selecting a cycle bar draws instead the arcs between the cycle's members
  (rows inside an expanded member counted to it), in the bar's colour: the
  whole cycle, with the edges that close it against the order. It measures the
  rows marked `data-frontier` whenever the tree changes and loads their
  dependencies in one query. The tree follows the design system's interaction
  model without zoom and pan: hovering traces nothing; a click selects a row
  or a cycle bar (again, in the empty space, Esc or the clear button in
  the dependencies' heading clears it). A click left of a row's name (its indent, chevron or
  icon) expands or collapses it without selecting it; a double-click on the
  name expands or collapses it and leaves it selected. A right
  click opens the menu of a row, a bar or an arc, each item with its gesture.
  The `tree` element is focusable, its active descendant the keyboard's
  cursor, walked over the rows as drawn (→ ← and `+` / `−` expand and
  collapse). The pane's `?` lists the gestures.

## The structure map

`StructurePage` (`/structure`, needs a graph) shows only the levelized
structure map of the current hierarchy view, ported from the
`hierograph-structure` skill's static page (`assets/lsm-template.html`, its
look in `structure-map.css`): every container's children in rows by level,
bottom first, so a box sits above what it uses; the members of a cycle
together in a red tangle frame, in their own rows; and only the dependencies
against the levels drawn, as dashed arrows in the cycle colour. Selecting a box traces what it
uses (accent) and what uses it (grey) among the visible boxes, with rings and
arrows, and greys out the rest. It follows the design system's interaction
model (`docs/design-system.md`, "Interaction").

- **Data.** The root and each expanded container load their levelization
  over the folded children (`node(id) { children(compact: true) {
levelization } }`, #61, #63), keyed by the graph scope; `mapModel.ts`
  builds the visible map from them: the boxes, the open containers' rows, the
  frontier (boxes whose content isn't shown) and the feedback dependencies.
  The server folds a chain of packages that each hold nothing but the next
  package into one box, its last package, at every level; the box is named
  relative to its container (`io.hierograph.server` in a module, `server.mcp`
  in `io.hierograph`), and its tooltip names the packages it stands for
  (`compactedFrom`). A dependency handed to the map is placed at where the
  folded map shows its ends (`displayNode`, `predecessors(compact: true)`). The first selected box loads the matrix over the
  frontier (`orderedAdjacencyMatrix`) for the weights.
- **Interaction.** Hovering traces nothing. A click selects a box (again,
  on the canvas, Esc or the pane's selection status clears it), a
  double-click expands or collapses it, a right click opens the menu of a
  box, an arrow or the canvas, each item with its gesture. The canvas is the
  `tree`: focusable, its active descendant the keyboard's cursor. The arrow
  keys move to the nearest box that way as drawn (`boxToward`, an open
  container by its name bar), Home and End in reading order (`boxOrder`),
  `+` / `−` expand and collapse. The pane's pin keeps the selection whatever
  is clicked (the start screen's tree has none). The pane's `?` lists the gestures.
- **Layout.** A box takes a share of its row by how many boxes are visible in
  it, and is never narrower than its content; a map wider than the pane
  scrolls. The arrows are measured from the boxes whenever the map changes or
  is resized (`mapArrows.ts`, the template's routing).
- **Given dependencies.** `dependency_ids` hands the map a set of
  dependencies (`<source>-<target>`, a source's targets grouped as
  `<source>-<target>.<target>`; at most 200), e.g. from the agent's
  `structure_map_link`. `givenDependencies.ts` parses them and, once
  `aggregatedDependencies(pairs)` has resolved their ends (with their
  ancestors in the view) and weights, places each between the visible boxes
  its ends lie in; the arrows are the `given` role, dashed against the levels
  in place of the feedback arrow. Without
  `expanded_ids`, the map opens just far enough that every dependency's ends
  lie in different boxes and writes that into the URL (once per set);
  `given_only` (the pane's "Only these" switch) draws only these: the map keeps only the boxes they join and the
  boxes around them (`restrictModel`), laid out compactly. Once they are drawn
  where they stay (expanded and loaded), the map zooms and scrolls to the
  boxes they join (at most 100%) instead of fitting the whole map, once per
  set; a zoom control brings them back. Next to fitting the map, another
  zooms to the selection: the selected box and the boxes its arrows end at. Where a set comes from
  is kept apart from how it is drawn, so a named set (#59) is one more source
  of pairs.
- **URL.** `expanded_ids` (expanding replaces the history entry),
  `selected_id`, `selection_pinned`, `dependency_ids` and `given_only` (all
  replace it), validated in `routes/structure.tsx`.

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

| Vertical    | Public files                                                                                               |
| ----------- | ---------------------------------------------------------------------------------------------------------- |
| `frame`     | `AppFrame.tsx`, `GraphScope.tsx`, `screens.ts`, `ServerStatus.tsx` (its `status` tabs, for the root route) |
| `start`     | `StartPage.tsx`                                                                                            |
| `structure` | `StructurePage.tsx`                                                                                        |
| `routing`   | `searchCodec.ts`                                                                                           |

The platform layers (`design-system`, `graphql`, `testing`) expose their files
directly (shadcn convention / generated client).

## Tooling

These rules are enforced by ESLint (`eslint-plugin-boundaries`) and by
`dependency-cruiser` (cycle backstop) — see `eslint.config.js` and
`.dependency-cruiser.cjs`.
