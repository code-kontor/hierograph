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
      "boundaries/no-unknown-files": "warn",
      "boundaries/element-types": [
        "warn",
        {
          default: "disallow",
          message:
            "${file.type} must not import ${dependency.type} (dependency rules: docs/architecture.md)",
          rules: [
            { from: "app", allow: ["routes", "graphql", "design-system"] },
            {
              from: "routes",
              allow: [
                "dependencies",
                "cross-reference",
                "dependency-details",
                "hierarchy",
                "selection",
                "graph",
                "design-system",
              ],
            },
            {
              from: [
                "dependencies",
                "cross-reference",
                "dependency-details",
                "hierarchy",
              ],
              allow: ["selection", "tree", "graph", "design-system", "graphql"],
            },
            { from: "tree", allow: ["graph", "design-system"] },
            { from: "graph", allow: ["graphql", "design-system"] },
            { from: ["design-system"], allow: ["design-system"] },
            { from: ["test", "testing"], allow: ["*"] },
          ],
        },
      ],
      "boundaries/entry-point": [
        "warn",
        {
          default: "disallow",
          rules: [
            {
              target: [
                "dependencies",
                "cross-reference",
                "dependency-details",
                "hierarchy",
                "selection",
                "tree",
                "graph",
              ],
              allow: "index.ts",
            },
            { target: ["design-system", "graphql", "testing"], allow: "**" },
          ],
        },
      ],
    },
  },
]);
