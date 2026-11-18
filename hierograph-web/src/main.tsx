import "./index.css";
import "@fontsource/ibm-plex-sans/400.css";
import "@fontsource/ibm-plex-sans/500.css";
import "@fontsource/ibm-plex-sans/600.css";
import "@fontsource/ibm-plex-sans/700.css";
import "@fontsource/ibm-plex-mono/400.css";
import "@fontsource/ibm-plex-mono/500.css";
import "@fontsource/ibm-plex-mono/600.css";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRouter, RouterProvider } from "@tanstack/react-router";
import { createRoot } from "react-dom/client";

import {
  routerParseSearch,
  routerStringifySearch,
} from "@/routing/searchCodec";

import { routeTree } from "./routeTree.gen";

// The server answers a request from the graph that is live when it arrives,
// and the graph only changes when the server reloads it. So queries are cached
// aggressively: staleTime Infinity means a mounted query is never refetched
// (no window-focus / reconnect / remount refetch). Following a reload is the
// frame's job (the graph generation becomes part of the query keys). gcTime
// stays at the 5 min default on purpose: it is the memory bound — React Query
// has no max-entry limit, so gcTime reclaims scrolled-away nodes and keeps the
// cache from growing unbounded over a long session.
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: Infinity,
      gcTime: 5 * 60 * 1000,
      refetchOnWindowFocus: false,
      refetchOnReconnect: false,
    },
  },
});

const router = createRouter({
  routeTree,
  // The UI is served under `/ui/` (Vite `base`); the router uses the same path.
  basepath: import.meta.env.BASE_URL,
  context: { queryClient },
  parseSearch: routerParseSearch,
  stringifySearch: routerStringifySearch,
});

declare module "@tanstack/react-router" {
  // eslint-disable-next-line @typescript-eslint/consistent-type-definitions -- module augmentation requires interface merging; a type alias would not merge
  interface Register {
    router: typeof router;
  }
}

createRoot(document.getElementById("root")!).render(
  <QueryClientProvider client={queryClient}>
    <RouterProvider router={router} />
  </QueryClientProvider>,
);
