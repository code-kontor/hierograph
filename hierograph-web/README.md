# hierograph-web

The Hierograph web UI: visualisation and exploration of the graph a Hierograph
server serves. It is the companion to the agent session, which stays the
primary interface: the agent opens the UI with links into a given state.

The Hierograph server serves the built UI at `http://127.0.0.1:8765/ui/`.

The header's menu chooses the hierarchy view every screen shows (`view=`),
and its graph status opens a panel with, live, what the server serves: the
workspace and its graph source, the graph, and the server with its JVM's
metrics (`status=`). The start screen is the hierarchy tree.

## Prerequisites

- **Node.js 22+** — install from [nodejs.org](https://nodejs.org/) or via a
  version manager such as [nvm](https://github.com/nvm-sh/nvm).
- **pnpm**, in the version pinned in `package.json`. Without a global
  installation, run it through corepack, which ships with Node.js
  (`corepack pnpm …`); see https://pnpm.io/installation#using-corepack.

## Quick start

```bash
# 1. Install the frontend dependencies (reads pnpm-lock.yaml)
pnpm install

# 2. Start the dev server
pnpm dev
```

- The dev server runs on **http://localhost:3080/ui/**.
- It expects a running Hierograph server on **http://localhost:8765** — the
  dev server proxies `/graphql` there. Override the target with
  `VITE_GRAPHQL_PROXY_TARGET` (see [GraphQL](#graphql)). See the repository's
  `BUILD.md` for how to start the server.

## Maven build

The Maven build (`mvn install` at the repository root, or
`mvn install -pl hierograph-web`) builds, checks and tests the UI; there is no
separate frontend build to run. It installs its own Node and pnpm (versions in
`hierograph-parent/pom.xml`), runs `pnpm install`, builds the UI into the jar
under `META-INF/resources/ui`, and in the `test` phase runs the lint, Prettier
and architecture checks and the unit and browser tests.

- `-DskipTests` skips the checks and tests, as for the Java modules.
- `-Dhierograph.web.skip` skips the web build entirely, for fast backend-only
  builds. The jar is still built, without the UI.
- Offline (`mvn -o`) works once Node, pnpm, the pnpm store and Playwright's
  Chromium are cached. The first build, or a changed lockfile or Playwright
  version, needs network once.

## Scripts

| Script                    | What it does                                               |
| ------------------------- | ---------------------------------------------------------- |
| `pnpm dev`                | Start the Vite dev server (port 3080)                      |
| `pnpm routes:generate`    | Regenerate the route tree after adding or renaming a route |
| `pnpm codegen`            | Run GraphQL Code Generator (client preset) once            |
| `pnpm codegen:watch`      | Run GraphQL Code Generator in watch mode                   |
| `pnpm build`              | Type-check (`tsc`) and create a production build (`dist/`) |
| `pnpm preview`            | Serve the production build locally                         |
| `pnpm check`              | Prettier write + ESLint fix + type-check (modifies files)  |
| `pnpm check:ts`           | Type-check only                                            |
| `pnpm check:lint`         | ESLint only (no fix)                                       |
| `pnpm check:prettier`     | Prettier check only (no write)                             |
| `pnpm check:architecture` | Dependency-cruiser check (no cycles)                       |
| `pnpm test`               | Unit and browser tests, headless                           |

## GraphQL

The app talks to the Hierograph GraphQL API via the relative path `/graphql`.
The server serves the UI and `/graphql` from the same origin. In development
the Vite dev server proxies `/graphql` to the server — default target
`http://localhost:8765`, override with `VITE_GRAPHQL_PROXY_TARGET` (e.g. in
`.env.local`).

Typed documents are generated with GraphQL Code Generator (`client-preset`)
from the schema files in
`../hierograph-server/io.hierograph.server.graphql/src/main/resources/graphql/`
into `src/graphql/generated/` (committed — do not edit by hand). After adding
or changing a `graphql()` document, run `pnpm codegen` (or keep
`pnpm codegen:watch` running). Note: the schema is pre-processed in
`codegen.ts` (empty type bodies are stripped for graphql-js); schema changes
require a codegen re-run or watch restart.
