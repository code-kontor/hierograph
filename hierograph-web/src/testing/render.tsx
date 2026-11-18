import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import type { ReactNode } from "react";
import { onTestFinished } from "vitest";
import { render } from "vitest-browser-react";

import {
  routerParseSearch,
  routerStringifySearch,
} from "@/routing/searchCodec";

export async function renderWithQueryClient(ui: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  // Cancel in-flight queries when the test finishes so pending requests don't
  // outlive the MSW service worker and fall through to the Vite proxy.
  onTestFinished(() => queryClient.cancelQueries());
  const result = await render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>,
  );
  return { ...result, queryClient };
}

// Render `ui` as the component of a memory-history route mounted at `path`,
// using the app's custom comma/enum search codec. Components that read/write
// search params (`useSearch`/`useNavigate`) need a live router — this gives
// them one in browser tests without pulling in the whole generated route tree.
// `validateSearch` passes the parsed search through untouched (the codec has
// already coerced comma id lists and enums); pass `initialSearch` (e.g.
// `"?center_ids=1,2"`) to exercise a deep-link start state.
export async function renderWithRouter(
  ui: ReactNode,
  path: string,
  initialSearch = "",
) {
  const rootRoute = createRootRoute();
  const route = createRoute({
    getParentRoute: () => rootRoute,
    path,
    validateSearch: (search: Record<string, unknown>) => search,
    component: () => ui,
  });
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  onTestFinished(() => queryClient.cancelQueries());
  const router = createRouter({
    routeTree: rootRoute.addChildren([route]),
    history: createMemoryHistory({
      initialEntries: [`${path}${initialSearch}`],
    }),
    parseSearch: routerParseSearch,
    stringifySearch: routerStringifySearch,
  });
  const result = await render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { ...result, queryClient, router };
}
