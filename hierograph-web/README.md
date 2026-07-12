# hierograph-web

Web frontend for Hierograph. **Work in progress**

## Prerequisites

- **Node.js 24+** — install from [nodejs.org](https://nodejs.org/) or via a
  version manager such as [nvm](https://github.com/nvm-sh/nvm).
- Package mananger **pnpm** — Installation guide: https://pnpm.io/installation#using-corepack

## Quick start

```bash
# 1. Install the frontend dependencies (reads pnpm-lock.yaml)
pnpm install

# 2. Start the dev server
pnpm dev
```

- The frontend runs on **http://localhost:3080**.
- It expects the hierograph GraphQL backend (the MCP server) on
  **http://localhost:8080** — the Vite dev server proxies `/graphql` there.
  Override the target with `VITE_GRAPHQL_PROXY_TARGET` (see [GraphQL](#graphql)).
  See the workspace README for how to start the backend.

## Views

The app opens on the DSM view (`/` redirects to `/dsm`). Three views are
available from the top navigation:

- **DSM** — the Design Structure Matrix: the primary view for exploring the
  hierarchical dependency model (tree + matrix + dependency inspector).
- **Cross-Reference Explorer** — explores cross references between nodes.
  ⚠️ Still needs further conceptual/domain clarification before it settles.
- **Dependency Diagram** — a node-graph visualization. ⚠️ Prototype / work in
  progress; it exists only to evaluate the feature and is not production-ready.

## Scripts

| Script                | What it does                                               |
| --------------------- | ---------------------------------------------------------- |
| `pnpm dev`            | Start the Vite dev server (port 3080)                      |
| `pnpm codegen`        | Run GraphQL Code Generator (client preset) once            |
| `pnpm codegen:watch`  | Run GraphQL Code Generator in watch mode                   |
| `pnpm build`          | Type-check (`tsc`) and create a production build (`dist/`) |
| `pnpm preview`        | Serve the production build locally                         |
| `pnpm lint`           | Run ESLint                                                 |
| `pnpm check`          | Prettier write + ESLint fix + type-check (modifies files)  |
| `pnpm check:ts`       | Type-check only                                            |
| `pnpm check:lint`     | ESLint only (no fix)                                       |
| `pnpm check:prettier` | Prettier check only (no write)                             |

## GraphQL

The app talks to the hierograph GraphQL API via the relative path `/graphql`.
The Vite dev server proxies `/graphql` to the MCP server — default target
`http://localhost:8080`, override with `VITE_GRAPHQL_PROXY_TARGET` (e.g. in
`.env.local`).

Typed documents are generated with GraphQL Code Generator (`client-preset`)
from the SDL files in
`../hierograph-mcp/io.hierograph.graphql/src/main/resources/graphql/` into
`src/generated/graphql/` (committed — do not edit by hand). After adding or changing a
`graphql()` document, run `pnpm codegen` (or keep `pnpm codegen:watch`
running). Note: the schema is pre-processed in `codegen.ts` (empty type
bodies are stripped for graphql-js); schema changes require a codegen re-run
or watch restart.
