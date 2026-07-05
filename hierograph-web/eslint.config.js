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
      reactHooks.configs.flat.recommended,
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
        { type: "cross-reference", pattern: "src/cross-reference" },
        { type: "dependency-details", pattern: "src/dependency-details" },
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
            // routes → verticals via index.ts, design-system
            {
              from: { type: "routes" },
              allow: {
                to: [
                  { type: "dependencies", internalPath: "index.ts" },
                  { type: "cross-reference", internalPath: "index.ts" },
                  { type: "dependency-details", internalPath: "index.ts" },
                  { type: "hierarchy", internalPath: "index.ts" },
                  { type: "selection", internalPath: "index.ts" },
                  { type: "tree", internalPath: "index.ts" },
                  { type: "graph", internalPath: "index.ts" },
                  { type: "design-system" },
                ],
              },
            },
            // screen verticals → shared panes + shared verticals via index.ts, design-system, graphql
            {
              from: { type: ["dependencies", "cross-reference"] },
              allow: {
                to: [
                  { type: "hierarchy", internalPath: "index.ts" },
                  { type: "dependency-details", internalPath: "index.ts" },
                  { type: "selection", internalPath: "index.ts" },
                  { type: "tree", internalPath: "index.ts" },
                  { type: "graph", internalPath: "index.ts" },
                  { type: "design-system" },
                  { type: "graphql" },
                ],
              },
            },
            // shared panes → shared verticals via index.ts, design-system, graphql
            {
              from: { type: ["dependency-details", "hierarchy"] },
              allow: {
                to: [
                  { type: "selection", internalPath: "index.ts" },
                  { type: "tree", internalPath: "index.ts" },
                  { type: "graph", internalPath: "index.ts" },
                  { type: "design-system" },
                  { type: "graphql" },
                ],
              },
            },
            // tree → graph via index.ts, design-system
            {
              from: { type: "tree" },
              allow: {
                to: [
                  { type: "graph", internalPath: "index.ts" },
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
