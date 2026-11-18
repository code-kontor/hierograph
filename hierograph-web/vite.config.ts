/// <reference types="vitest/config" />
import babel from "@rolldown/plugin-babel";
import tailwindcss from "@tailwindcss/vite";
import viteReact, { reactCompilerPreset } from "@vitejs/plugin-react";
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
    // The Hierograph server serves the UI under /ui/, next to /graphql.
    base: "/ui/",
    resolve: { tsconfigPaths: true },
    build: {
      // The Maven build writes the UI straight into the jar's resources
      // (META-INF/resources/ui); a plain `pnpm build` writes to dist/.
      outDir: process.env.HIEROGRAPH_WEB_OUT_DIR ?? "dist",
      emptyOutDir: true,
    },
    server: {
      port: 3080,
      proxy: {
        "/graphql": {
          target: env.VITE_GRAPHQL_PROXY_TARGET ?? "http://localhost:8765",
          changeOrigin: true,
        },
      },
    },
    plugins: [
      tailwindcss(),
      viteReact(),
      babel({
        presets: [reactCompilerPreset()],
      }),
    ],
    optimizeDeps: {
      // Restrict the dependency scanner to the real app entry. By default Vite
      // crawls every *.html under the root (e.g. browser-test artifacts).
      entries: ["index.html"],
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
            // Run browser test files sequentially. In parallel, Vitest spins up
            // one Chromium instance per file at once; on memory-constrained hosts
            // (containers, CI, the sandbox — ~2GB free) that OOM-crashes mid-run
            // with "Browser connection was closed" / "[birpc] rpc is closed" on a
            // random file. The suite is small (~20s sequential), so the lost
            // parallelism costs little and buys a deterministic green run.
            fileParallelism: false,
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
              // https://vitest.dev/guide/browser/playwright
              // Containers (Docker default) cap /dev/shm at 64MB, which
              // Chromium exhausts under parallel test files and crashes with
              // "Browser connection was closed" — route its shared memory to
              // /tmp instead. Launch options belong on the provider, not on the
              // instance; chromium is the only configured browser.
              provider: playwright({
                launchOptions: { args: ["--disable-dev-shm-usage"] },
              }),
              instances: browsers.map((browser) => ({ browser })),
            },
          },
        },
      ],
    },
  };
});
