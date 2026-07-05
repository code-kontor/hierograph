# hierograph-web

React + Vite + TypeScript (strict) + Tailwind CSS v4 + shadcn/ui. Run all
commands from this directory (`hierograph-web/`). Package manager: pnpm (via
Corepack, `packageManager` field in `package.json`).

shadcn/ui components live in `src/components/ui/` (generated via
`pnpm dlx shadcn@latest add <component>`).

## Language & commit policy

- **Everything in `hierograph-web` is written in English** — code comments,
  identifiers, commit messages, and documentation (including this CLAUDE.md and
  the README). No German in this subproject.
- **Never add a `Co-Authored-By` trailer to commits** touching
  `hierograph-web` (nor any other trailer attributing the change to an AI).
  Commits stay single-line subject only.
- **Write as if the app is being built fresh, never as a migration.** From the
  hierograph project's point of view it does not matter that this code
  originates from an earlier application. Commit messages, comments, and docs
  must not mention porting/migration or the predecessor project (e.g. slizaa) —
  describe the code on its own terms.

## Code Style

- For React component props always use an object with its own named type, never
  an inline type
  - OWN TYPE: `type MyComponentProps = { label: string }; function MyComponent({label}: MyComponentProps) { /* ... */ }`
  - NOT: `function MyComponent({label}: {label: string}) { /* ... */ }`
- Use `type` instead of `interface` for type declarations
- No `void` in front of a function call
  - NOT: `void queryClient.invalidateQueries({ queryKey: ["posts"] });`
- Instead of composing CSS class names with template strings, use `twMerge`
  - NOT: ``className={`btn-submit ${isSuccess ? "success" : ""}`}``
  - WITH TWMERGE: `className={twMerge("btn-submit", isSuccess && "success")}`
- No inline array with `.map()` in JSX — write components out explicitly
  instead of generating them dynamically
  - NOT: `{(['a', 'b'] as const).map(x => <Item key={x} value={x} />)}`
  - INSTEAD: `<Item value="a" /><Item value="b" />`

## Checking code and code style

**After creating or changing code:** run `pnpm check` — it formats with
Prettier, fixes ESLint problems, and then checks for type errors (tsc). Note:
it modifies files (auto-fix).

**Available single scripts (check-only, no auto-fix):**

- `check:ts`: checks type errors only (tsc)
- `check:prettier`: checks formatting only (Prettier)
- `check:lint`: checks linting rules only (ESLint)

## Important patterns: TanStack Query

(Applies from the GraphQL integration onward — TanStack Query is part of the
decided stack.)

Write `queryFn` in `queryOptions` as a **method**, not as an arrow-function
property:

```ts
// ✅ correct
queryOptions({
  queryKey: ["posts", "list"],
  async queryFn() {
    // ...
  },
});

// ❌ wrong
queryOptions({
  queryKey: ["posts", "list"],
  queryFn: async () => {
    // ...
  },
});
```

In `onSuccess` of `useMutation`, get the query client from the context (4th
method parameter), not via `useQueryClient`. Return the promise of
`invalidateQueries` from `onSuccess`; when calling it multiple times, combine
the promises with `Promise.all`:

```ts
onSuccess: (_data, _vars, _result, context) => {
  return Promise.all([
    context.client.invalidateQueries({ queryKey: ["posts"] }),
    context.client.invalidateQueries({ queryKey: ["stats"] }),
  ]);
};
```

## GraphQL

- Endpoint: fixed relative `/graphql` (dev proxy to the MCP server, target
  overridable via `VITE_GRAPHQL_PROXY_TARGET` — see `vite.config.ts`).
- Write GraphQL documents inline with `graphql()` from `@/generated/graphql`;
  no separate `.graphql` files.
- Query modules under `src/queries/`: document + `queryOptions` factory in the
  same module; components import only the factory
  (`useQuery(rootNodeQueryOptions())`). Variables as factory parameters — they
  belong in the `queryKey`. Requests go through `execute()` from
  `src/lib/graphql-client.ts`.
- Generated code lives in `src/generated/graphql/` (committed; excluded from
  ESLint/Prettier, checked by tsc) — **never edit by hand**. After each
  new/changed document: first `pnpm codegen`, then typecheck
  (`pnpm codegen:watch` for ongoing work; schema changes require a watch
  restart).

## Testing

- Test config lives in `vite.config.ts` (`test.projects`) — there is no separate
  `vitest.config.ts`. Two projects, selected by filename:
  - `unit` — `*.test.{ts,tsx}`, `node` environment, browser-independent code
    only (logic, algorithms); no DOM.
  - `browser` — `*.browsertest.{ts,tsx}`, Vitest browser mode (Playwright/
    Chromium), for components and MSW-backed integration tests.
- Run tests: `pnpm test` (both projects once, headless).
- Watch mode: `pnpm test:watch`; single project: `pnpm test:unit` /
  `pnpm test:browser`.
- Browser tests are **headless by default** (CI/sandbox have no display). For a
  visible browser window run `pnpm test:headed` (sets `HG_HEADED=true`).
- `toMatchScreenshot` baselines go to `__screenshots__/` (committed); failure
  screenshots go to `__artifacts__/` (gitignored). Update baselines with
  `pnpm test:browser:update`.
- Strategy and constraints: `docs/testing-strategy.md`
- Fixtures are recorded JSON files in `src/testing/fixtures/` — re-record with
  `pnpm fixtures:record` when the GraphQL schema or fixture-app data changes.
  Never edit fixture files by hand.
- Use `renderWithQueryClient` from `@/testing/render` for any component that
  needs `QueryClient`. MSW intercepts all GraphQL requests automatically.
- `src/testing/public/mockServiceWorker.js` is generated — never edit.

## Routing (TanStack Router, file-based)

- The generated route tree `src/routeTree.gen.ts` is **never** created or edited
  by hand (committed; excluded from ESLint/Prettier, checked by tsc).
- Create new routes as **empty** files under `src/routes/`, then run the
  generator — `tsr generate` writes boilerplate (`createFileRoute`) only into
  empty files. Afterwards fill the file with the actual content. `__root.tsx`
  is the special case (`createRootRoute`).
- After **every** creation, rename, or deletion of route files, run the
  generator: `pnpm routes:generate` (runs `tsr generate`). The Vite dev server
  is not necessarily running, so this step is mandatory — before the typecheck.
