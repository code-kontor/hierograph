import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { onTestFinished } from "vitest";
import { render } from "vitest-browser-react";

import { DevPanelProvider } from "@/selection/SelectionContext";

export async function renderWithQueryClient(ui: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  // Cancel in-flight queries when the test finishes so pending requests don't
  // outlive the MSW service worker and fall through to the Vite proxy.
  onTestFinished(() => queryClient.cancelQueries());
  const result = await render(
    <QueryClientProvider client={queryClient}>
      {/* Mirror the app's root-level provider so the dev DevPanel (rendered
          by the panes under test) has its visibility/tab context. */}
      <DevPanelProvider>{ui}</DevPanelProvider>
    </QueryClientProvider>,
  );
  return { ...result, queryClient };
}
