# Frontend Architecture

The frontend is organised into feature _verticals_ as top-level folders under
`src/`. Each vertical owns a specific screen or shared concern. Dependencies
between verticals flow only in the directions listed in the table below — the
dependency graph is a DAG (directed acyclic graph), i.e. the DSM is
lower-triangular.

## Verticals

| Vertical             | Scope                                                     |
| -------------------- | --------------------------------------------------------- |
| `dependencies`       | DSM screen (`/dependencies`)                              |
| `cross-reference`    | cross-reference screen                                    |
| `dependency-details` | shared inspector pane                                     |
| `hierarchy`          | hierarchy tree browser                                    |
| `selection`          | cross-view workbench selection state                      |
| `tree`               | async tree widget and settings                            |
| `graph`              | shared node-domain concepts (icons, labels, node queries) |
| `design-system`      | domain-agnostic UI components (depends on no vertical)    |
| `graphql`            | data-access platform (Apollo client and generated code)   |
| `routes`             | thin route wiring                                         |
| `testing`            | test infrastructure                                       |

## Allowed Dependency Direction (DAG)

| From                                                                    | May import                                                                                                  |
| ----------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| app (`main.tsx`, `routeTree.gen.ts`)                                    | `routes`, `graphql`, `design-system`                                                                        |
| `routes`                                                                | `dependencies`, `cross-reference`, `dependency-details`, `hierarchy`, `selection`, `graph`, `design-system` |
| `dependencies` / `cross-reference` / `dependency-details` / `hierarchy` | `selection`, `tree`, `graph`, `design-system`, `graphql`                                                    |
| `tree`                                                                  | `graph`, `design-system`                                                                                    |
| `graph`                                                                 | `graphql`, `design-system`                                                                                  |
| `selection`                                                             | (nothing internal)                                                                                          |
| `design-system`                                                         | (nothing internal — only itself)                                                                            |
| `graphql`                                                               | (nothing internal)                                                                                          |
| `test` / `testing`                                                      | anything                                                                                                    |

## Public-API Rule

Verticals (`dependencies`, `cross-reference`, `dependency-details`,
`hierarchy`, `selection`, `tree`, `graph`) are entered only through their
`index.ts`. The platform layers (`design-system`, `graphql`, `testing`) expose
their files directly (shadcn convention / generated client).

## Tooling

These rules are enforced by ESLint (`eslint-plugin-boundaries`) and by
`dependency-cruiser` (cycle backstop) — see `eslint.config.js` and
`.dependency-cruiser.cjs`.
