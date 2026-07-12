# hierograph-web

Run all commands from this directory (`hierograph-web/`). shadcn/ui components
live in `src/design-system/ui/` (add via `pnpm dlx shadcn@latest add <component>`).

## Design system

UI must be styled design-conform to the IDE design system (tokens in
`src/index.css` + component specs). Design decisions happen **only in Claude
Design**, never ad hoc in code; from code the design is read **read-only** via
the Design MCP. Convention, MCP setup and the Claude Design ↔ Claude Code loop:
[`docs/design-system.md`](docs/design-system.md).

## Architecture

Feature verticals under `src/` with enforced import boundaries
(`eslint-plugin-boundaries` + dependency-cruiser). The vertical list, public
files and allowed edges live in `eslint.config.js`; the dependency table in
[`docs/architecture.md`](docs/architecture.md).

- **No barrel `index.ts`** — import public files by direct path
  (`@/<vertical>/<File>`); intra-vertical imports are direct paths too.
- **Never loosen, disable or add exceptions to the boundary rules to make
  `pnpm check` pass.** A failing check means the _code_ is wrong (wrong
  direction, deep import, misplaced file) — fix the code. Widen a rule only for
  a genuinely intended new edge, and update `docs/architecture.md` in the same
  change.

## Code style

Not lint-enforced — mind these; everything else `pnpm check` auto-fixes:

- Component props: always a named type, never inline
  (`type XProps = {…}` + `function X({…}: XProps)`).
- Compose class names with `twMerge(…)`, not template strings.
- No `void` in front of a function call (no fire-and-forget
  `void promise()` — await it or handle the promise).
- No inline `.map()` in JSX — write components out explicitly.
- **React Compiler is on** (manual `useMemo`/`useCallback`/`memo` are
  lint-blocked). Caveat: destructured prop defaults (`function C({ a = 1 }: P)`)
  make the compiler bail out on the whole component
  (babel-plugin-react-compiler 1.0.0). Where stable identities matter, make the
  prop required or default in the body.

## Checking

After changing code run `pnpm check` (Prettier + ESLint auto-fix + `tsc`; it
modifies files). Check-only: `check:ts`, `check:prettier`, `check:lint`,
`check:architecture`.

## TanStack Query

- `queryFn` as a **method** in `queryOptions`, not an arrow property.
- In `useMutation` `onSuccess`, take the client from the 4th param
  (`context.client`), not `useQueryClient`; return the `invalidateQueries`
  promise (combine multiple with `Promise.all`).

## GraphQL

- Endpoint: fixed relative `/graphql` (dev proxy, target via
  `VITE_GRAPHQL_PROXY_TARGET`, see `vite.config.ts`).
- Write documents inline with `graphql()` from `@/graphql/generated`; no
  `.graphql` files.
- Query colocation: each vertical owns `queries.ts` (document + `queryOptions`
  factory); shared node queries in `graph/queries.ts`. Query-key first part =
  vertical name; shared graph queries keep the `hierarchicalGraph` root.
  Requests go through `execute()` (`src/graphql/client.ts`).
- `src/graphql/generated/` is committed and **never hand-edited**. After a
  new/changed document: `pnpm codegen`, then typecheck (`pnpm codegen:watch`
  for ongoing work).

## Testing

- Config is in `vite.config.ts` (`test.projects`), no `vitest.config.ts`.
  `unit` = `*.test.{ts,tsx}` (node, no DOM); `browser` = `*.browsertest.{ts,tsx}`
  (Vitest browser mode, Playwright/Chromium).
- `pnpm test` (both, headless), `test:watch`, `test:unit`, `test:browser`,
  `test:headed` (visible). Screenshot baselines in `__screenshots__/`
  (committed) — update with `pnpm test:browser:update`.
- **Node ids are not stable** — reference nodes by fqn via `resolveNodeId`
  (`@/testing/nodeLookup`), never hard-code ids.
- Use `renderWithQueryClient` (`@/testing/render`); MSW intercepts GraphQL
  automatically. Fixtures in `src/testing/fixtures/` — re-record with
  `pnpm fixtures:record`, never edit by hand.
- Harmless: an occasional `CancelledError` console line from query cancellation
  on teardown — never fails a test, safe to ignore.
- Strategy: [`docs/testing-strategy.md`](docs/testing-strategy.md).

## Routing (TanStack Router, file-based)

- `src/routeTree.gen.ts` is generated — never hand-edit.
- New route = create an **empty** file under `src/routes/`, run
  `pnpm routes:generate` (fills empty files with boilerplate), then add content.
  `__root.tsx` uses `createRootRoute`.
- Run `pnpm routes:generate` after every route add/rename/delete, before the
  typecheck.
