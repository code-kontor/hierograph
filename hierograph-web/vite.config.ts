import tailwindcss from "@tailwindcss/vite";
import viteReact from "@vitejs/plugin-react";
import { defineConfig, loadEnv } from "vite";

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
  };
});
