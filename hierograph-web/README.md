# hierograph-web

Web frontend for Hierograph: Vite, React, TypeScript (strict), Tailwind CSS v4,
shadcn/ui. A standalone pnpm project — intentionally **not** part of the Maven
build.

## Prerequisites

- **Node.js 24+**
- **pnpm** via Corepack: `corepack enable` (the version is pinned through the
  `packageManager` field in `package.json`)

## Getting started

```bash
pnpm install
pnpm dev        # dev server on http://localhost:3080
```

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

## UI components

shadcn/ui (style `new-york`, base color `neutral`, CSS variables). Add
components with `pnpm dlx shadcn@latest add <component>`; they are generated
into `src/components/ui/`.
