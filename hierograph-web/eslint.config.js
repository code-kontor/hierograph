//  @ts-check

import js from "@eslint/js";
import { defineConfig, globalIgnores } from "eslint/config";
import boundaries from "eslint-plugin-boundaries";
import importPlugin from "eslint-plugin-import";
import reactHooks from "eslint-plugin-react-hooks";
import simpleImportSort from "eslint-plugin-simple-import-sort";
import unusedImports from "eslint-plugin-unused-imports";
import globals from "globals";
import tseslint from "typescript-eslint";

export default defineConfig([
  globalIgnores([
    "dist",
    "eslint.config.js",
    "prettier.config.js",
    "src/graphql/generated",
    "src/routeTree.gen.ts",
    "src/testing/public",
  ]),
  {
    files: ["**/*.{ts,tsx}"],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat["recommended-latest"],
    ],
    languageOptions: {
      globals: globals.browser,
    },
    plugins: {
      "unused-imports": unusedImports,
      "simple-import-sort": simpleImportSort,
      import: importPlugin,
    },
    rules: {
      "unused-imports/no-unused-imports": "error",
      "simple-import-sort/imports": "error",
      "import/first": "error",
      "import/newline-after-import": "error",
      "import/no-duplicates": "error",
      // React Compiler is enabled (vite.config.ts) — manual memoization is
      // unnecessary; see CLAUDE.md, "Code Style". A genuine need for manual
      // memoization semantics requires an inline disable + justification.
      "no-restricted-syntax": [
        "error",
        {
          selector: "CallExpression[callee.name='useMemo']",
          message:
            "React Compiler is enabled — useMemo is unnecessary; write a plain expression (see CLAUDE.md, Code Style).",
        },
        {
          selector: "CallExpression[callee.name='useCallback']",
          message:
            "React Compiler is enabled — useCallback is unnecessary; write a plain function (see CLAUDE.md, Code Style).",
        },
        {
          selector:
            "CallExpression[callee.name='memo'], CallExpression[callee.object.name='React'][callee.property.name=/^(memo|useMemo|useCallback)$/]",
          message:
            "React Compiler is enabled — React.memo/manual memoization is unnecessary (see CLAUDE.md, Code Style).",
        },
      ],
    },
  },
  {
    files: ["src/**/*.{ts,tsx}"],
    plugins: { boundaries },
    settings: {
      "import/resolver": {
        typescript: { project: "./tsconfig.json" },
      },
      "boundaries/include": ["src/**/*"],
      "boundaries/ignore": ["src/assets/**/*"],
      "boundaries/elements": [
        {
          type: "test",
          mode: "full",
          pattern: ["src/**/*.test.*", "src/**/*.browsertest.*"],
        },
        { type: "testing", pattern: "src/testing" },
        { type: "routes", pattern: "src/routes" },
        { type: "dependencies", pattern: "src/dependencies" },
        {
          type: "cross-reference-explorer",
          pattern: "src/cross-reference-explorer",
        },
        { type: "dependency-details", pattern: "src/dependency-details" },
        { type: "dev-panel", pattern: "src/dev-panel" },
        { type: "hierarchy", pattern: "src/hierarchy" },
        { type: "selection", pattern: "src/selection" },
        { type: "tree", pattern: "src/tree" },
        { type: "graph", pattern: "src/graph" },
        { type: "design-system", pattern: "src/design-system" },
        { type: "graphql", pattern: "src/graphql" },
        {
          type: "app",
          mode: "full",
          pattern: ["src/main.tsx", "src/routeTree.gen.ts"],
        },
      ],
    },
    rules: {
      "boundaries/no-unknown-files": "error",
      "boundaries/dependencies": [
        "error",
        {
          default: "disallow",
          message:
            "${file.type} must not import ${dependency.type} (dependency rules: docs/architecture.md)",
          rules: [
            // app → app (main.tsx imports routeTree.gen.ts), routes, graphql, design-system
            {
              from: { type: "app" },
              allow: {
                to: [
                  { type: "app" },
                  { type: "routes" },
                  { type: "graphql" },
                  { type: "design-system" },
                ],
              },
            },
            // routes → verticals via public files, design-system
            {
              from: { type: "routes" },
              allow: {
                to: [
                  {
                    type: "dependencies",
                    internalPath: [
                      "DependenciesPage.tsx",
                      "DependencyMatrix.tsx",
                    ],
                  },
                  {
                    type: "cross-reference-explorer",
                    internalPath: [
                      "CrossReferenceExplorerView.tsx",
                      "CrossReferenceExplorerPage.tsx",
                    ],
                  },
                  {
                    type: "dependency-details",
                    internalPath: [
                      "DependencyDetailsPane.tsx",
                      "DependencyDetailsPanel.tsx",
                    ],
                  },
                  { type: "hierarchy", internalPath: ["HierarchyTree.tsx"] },
                  {
                    type: "selection",
                    internalPath: ["SelectionContext.tsx", "FocusBridge.tsx"],
                  },
                  {
                    type: "tree",
                    internalPath: [
                      "AsyncTree.tsx",
                      "TreeSettingsMenu.tsx",
                      "useTreeSettings.ts",
                    ],
                  },
                  {
                    type: "graph",
                    internalPath: [
                      "queries.ts",
                      "nodeIcon.ts",
                      "NodeInfoTooltip.tsx",
                      "nodeLabel.ts",
                    ],
                  },
                  { type: "design-system" },
                  {
                    type: "dev-panel",
                    internalPath: ["DevPanel.tsx", "DevPanelContext.tsx"],
                  },
                ],
              },
            },
            // screen verticals → shared panes + shared verticals via public files, design-system, graphql
            {
              from: {
                type: ["dependencies", "cross-reference-explorer"],
              },
              allow: {
                to: [
                  { type: "hierarchy", internalPath: ["HierarchyTree.tsx"] },
                  {
                    type: "dependency-details",
                    internalPath: [
                      "DependencyDetailsPane.tsx",
                      "DependencyDetailsPanel.tsx",
                    ],
                  },
                  { type: "selection", internalPath: ["SelectionContext.tsx"] },
                  {
                    type: "tree",
                    internalPath: [
                      "AsyncTree.tsx",
                      "TreeSettingsMenu.tsx",
                      "useTreeSettings.ts",
                    ],
                  },
                  {
                    type: "graph",
                    internalPath: [
                      "queries.ts",
                      "nodeIcon.ts",
                      "NodeInfoTooltip.tsx",
                      "nodeLabel.ts",
                    ],
                  },
                  { type: "design-system" },
                  { type: "graphql" },
                ],
              },
            },
            // shared panes → shared verticals via public files, design-system, graphql
            {
              from: { type: ["dependency-details", "hierarchy"] },
              allow: {
                to: [
                  { type: "selection", internalPath: ["SelectionContext.tsx"] },
                  {
                    type: "tree",
                    internalPath: [
                      "AsyncTree.tsx",
                      "TreeSettingsMenu.tsx",
                      "useTreeSettings.ts",
                    ],
                  },
                  {
                    type: "graph",
                    internalPath: [
                      "queries.ts",
                      "nodeIcon.ts",
                      "NodeInfoTooltip.tsx",
                      "nodeLabel.ts",
                    ],
                  },
                  { type: "design-system" },
                  { type: "graphql" },
                ],
              },
            },
            // dev-panel → selection, graph, design-system, graphql
            {
              from: { type: "dev-panel" },
              allow: {
                to: [
                  {
                    type: "selection",
                    internalPath: ["SelectionContext.tsx", "FocusBridge.tsx"],
                  },
                  {
                    type: "graph",
                    internalPath: ["queries.ts", "nodeIcon.ts"],
                  },
                  { type: "design-system" },
                  { type: "graphql" },
                ],
              },
            },
            // tree → graph via public files, design-system
            {
              from: { type: "tree" },
              allow: {
                to: [
                  {
                    type: "graph",
                    internalPath: [
                      "queries.ts",
                      "nodeIcon.ts",
                      "NodeInfoTooltip.tsx",
                      "nodeLabel.ts",
                    ],
                  },
                  { type: "design-system" },
                ],
              },
            },
            // graph → graphql, design-system
            {
              from: { type: "graph" },
              allow: {
                to: [{ type: "graphql" }, { type: "design-system" }],
              },
            },
            // design-system → design-system
            {
              from: { type: "design-system" },
              allow: { to: { type: "design-system" } },
            },
            // test/testing → anything
            {
              from: { type: ["test", "testing"] },
              allow: { to: { type: "*" } },
            },
          ],
        },
      ],
    },
  },
]);
