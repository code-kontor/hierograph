/// <reference types="vitest/config" />
import tailwindcss from "@tailwindcss/vite";
import viteReact from "@vitejs/plugin-react";
import { playwright } from "@vitest/browser-playwright";
import { defineConfig, loadEnv } from "vite";

// Browser tests run headless by default so that CI, the sandbox, and other
// display-less environments work with zero configuration. Opt into a visible
// browser window locally with `HG_HEADED=true pnpm test:headed`.
const headless = process.env.HG_HEADED !== "true";

// Playwright trace of the browser tests. On in CI so a trace is always kept as
// a build artifact when a test breaks; off locally to keep runs fast.
const trace = process.env.CI ? "on" : "off";

const browsers: ("chromium" | "firefox" | "webkit")[] = ["chromium"];

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".");

  return {
    resolve: { tsconfigPaths: true },
    server: {
      port: 3080,
      proxy: {
        "/graphql": {
          target: env.VITE_GRAPHQL_PROXY_TARGET ?? "http://localhost:8080",
          changeOrigin: true,
        },
      },
    },
    plugins: [tailwindcss(), viteReact()],
    optimizeDeps: {
      include: ["tslib"],
    },
    test: {
      projects: [
        {
          extends: true,
          test: {
            // Browser-independent code only (logic, algorithms) — no DOM.
            name: "unit",
            environment: "node",
            include: ["src/**/*.test.{ts,tsx}"],
          },
        },
        {
          extends: true,
          // Serve the generated MSW worker during browser tests without
          // affecting the dev/build public directory.
          publicDir: "src/testing/public",
          // Pre-bundle test dependencies so the Vite optimizer does not
          // discover them mid-run (which would pull in a second React copy).
          optimizeDeps: {
            include: [
              "@headless-tree/core",
              "@headless-tree/react",
              "@tanstack/react-query",
              "graphql-request",
              "lucide-react",
              "tailwind-merge",
              "clsx",
              "vitest-browser-react",
              "msw/browser",
            ],
          },
          test: {
            name: "browser",
            include: ["src/**/*.browsertest.{ts,tsx}"],
            setupFiles: ["./src/testing/setup.ts"],
            browser: {
              enabled: true,
              headless,
              trace,
              // Send failure screenshots to a throwaway __artifacts__ folder,
              // while resolveScreenshotPath keeps toMatchScreenshot baselines
              // in __screenshots__ so that folder holds only reference images.
              screenshotDirectory: "__artifacts__",
              expect: {
                toMatchScreenshot: {
                  resolveScreenshotPath: ({
                    root,
                    testFileDirectory,
                    testFileName,
                    arg,
                    browserName,
                    platform,
                    ext,
                  }) =>
                    `${root}/${testFileDirectory}/__screenshots__/${testFileName}/${arg}-${browserName}-${platform}${ext}`,
                },
              },
              provider: playwright(),
              // https://vitest.dev/guide/browser/playwright
              instances: browsers.map((browser) => ({ browser })),
            },
          },
        },
      ],
    },
  };
});
