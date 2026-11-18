import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext, Outlet } from "@tanstack/react-router";

import { AppFrame } from "@/frame/AppFrame";

// The root route renders the frame around the matched screen. Each screen
// validates its own search params; the root will own only the frame-level
// ones (the hierarchy view).
export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()(
  {
    component: RootLayout,
  },
);

function RootLayout() {
  return (
    <AppFrame>
      <Outlet />
    </AppFrame>
  );
}
