/// <reference types="vitest/config" />
import tailwindcss from "@tailwindcss/vite";
import viteReact from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  resolve: { tsconfigPaths: true },
  server: { port: 3080 },
  plugins: [tailwindcss(), viteReact()],
  test: {
    passWithNoTests: true,
    projects: [
      {
        extends: true,
        test: {
          name: "unit",
          environment: "jsdom",
          setupFiles: ["src/test-setup.ts"],
          include: ["src/**/*.test.?(c|m)[jt]s?(x)"],
        },
      },
    ],
  },
});
