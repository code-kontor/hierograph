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

// Render `ui` in a memory-history router at `path`,
// using the app's custom comma/enum search codec. Components that read/write
// search params (`useSearch`/`useNavigate`) need a live router — this gives
// them one in browser tests without pulling in the whole generated route tree.
// `validateSearch` passes the parsed search through untouched (the codec has
// already coerced comma id lists and enums); pass `initialSearch` (e.g.
// `"?center_ids=1,2"`) to exercise a deep-link start state, and `otherPaths`
// for more paths to navigate to, as between screens.
export async function renderWithRouter(
  ui: ReactNode,
  path: string,
  initialSearch = "",
  otherPaths: string[] = [],
) {
  // The root renders `ui`, as the app's root renders the frame, so it stays
  // mounted while navigating between the paths.
  const rootRoute = createRootRoute({ component: () => ui });
  const routes = [path, ...otherPaths].map((routePath) =>
    createRoute({
      getParentRoute: () => rootRoute,
      path: routePath,
      validateSearch: (search: Record<string, unknown>) => search,
    }),
  );
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  onTestFinished(() => queryClient.cancelQueries());
  const router = createRouter({
    routeTree: rootRoute.addChildren(routes),
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
