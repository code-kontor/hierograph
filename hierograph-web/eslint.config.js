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

// Public files per guarded feature vertical (cross-vertical import surface).
// Platform layers (design-system, graphql, testing) expose all files by
// convention and are granted as whole verticals, not routed through PUBLIC.
const PUBLIC = {
  routing: ["searchCodec.ts"],
  frame: ["AppFrame.tsx"],
  start: ["StartPage.tsx"],
};

// Grant helper: full public surface of `type`, or an explicit subset for edges
// that intentionally consume only some of a vertical's public files.
const to = (type, only) => ({ type, internalPath: only ?? PUBLIC[type] });

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
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: {
      "unused-imports": unusedImports,
      "simple-import-sort": simpleImportSort,
      import: importPlugin,
    },
    rules: {
      "unused-imports/no-unused-imports": "error",
      "simple-import-sort/imports": "error",
      "@typescript-eslint/consistent-type-definitions": ["error", "type"],
      "@typescript-eslint/no-floating-promises": [
        "error",
        { ignoreVoid: false },
      ],
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
        { type: "routing", pattern: "src/routing" },
        { type: "frame", pattern: "src/frame" },
        { type: "start", pattern: "src/start" },
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
            // app → app (main.tsx imports routeTree.gen.ts), routes, routing
            // (custom search codec), graphql, design-system
            {
              from: { type: "app" },
              allow: {
                to: [
                  { type: "app" },
                  { type: "routes" },
                  to("routing"),
                  { type: "graphql" },
                  { type: "design-system" },
                ],
              },
            },
            // routes → frame and screens via public files, routing
            // (validateSearch codec helpers), design-system
            {
              from: { type: "routes" },
              allow: {
                to: [
                  to("frame"),
                  to("start"),
                  to("routing"),
                  { type: "design-system" },
                ],
              },
            },
            // screens → frame via public files, graphql, design-system.
            // Screens never import each other; the frame never imports a screen.
            {
              from: { type: "start" },
              allow: {
                to: [
                  to("frame"),
                  { type: "graphql" },
                  { type: "design-system" },
                ],
              },
            },
            // frame → routing via public files, graphql, design-system
            {
              from: { type: "frame" },
              allow: {
                to: [
                  to("routing"),
                  { type: "graphql" },
                  { type: "design-system" },
                ],
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
