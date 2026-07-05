import tailwindcss from "@tailwindcss/vite";
import viteReact from "@vitejs/plugin-react";
import { playwright } from "@vitest/browser-playwright";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [tailwindcss(), viteReact()],
  publicDir: "src/testing/public",
  resolve: { tsconfigPaths: true },
  optimizeDeps: {
    include: [
      "@headless-tree/core",
      "@headless-tree/react",
      "@tanstack/react-query",
      "graphql-request",
      "lucide-react",
      "tailwind-merge",
      "clsx",
    ],
  },
  test: {
    include: ["src/**/*.test.{ts,tsx}"],
    setupFiles: ["./src/testing/setup.ts"],
    browser: {
      enabled: true,
      headless: true,
      provider: playwright(),
      instances: [{ browser: "chromium" }],
    },
  },
});
