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
| `pnpm build`          | Type-check (`tsc`) and create a production build (`dist/`) |
| `pnpm preview`        | Serve the production build locally                         |
| `pnpm lint`           | Run ESLint                                                 |
| `pnpm check`          | Prettier write + ESLint fix + type-check (modifies files)  |
| `pnpm check:ts`       | Type-check only                                            |
| `pnpm check:lint`     | ESLint only (no fix)                                       |
| `pnpm check:prettier` | Prettier check only (no write)                             |

## UI components

shadcn/ui (style `new-york`, base color `neutral`, CSS variables). Add
components with `pnpm dlx shadcn@latest add <component>`; they are generated
into `src/components/ui/`.
