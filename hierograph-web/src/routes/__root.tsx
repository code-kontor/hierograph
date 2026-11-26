import type { QueryClient } from "@tanstack/react-query";
import {
  createRootRouteWithContext,
  Outlet,
  retainSearchParams,
} from "@tanstack/react-router";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
import { STATUS_TABS, type StatusTab } from "@/frame/ServerStatus";
import { parseEnum } from "@/routing/searchCodec";

// The screens of the UI, in navigation order. A new screen adds its route and
// an entry here; the frame never imports a screen.
const SCREENS: readonly Screen[] = [
  { path: "/", label: "Dependencies", needsGraph: false },
  { path: "/structure", label: "Structure", needsGraph: true },
];

// Frame-level search params only: `view`, the hierarchy view every screen
// answers in, and `status`, the open tab of the header's status panel
// (`graph`, `workspace`, `server`, `jvm`; the panel is closed without it, and
// an unknown value is dropped). Each screen validates its own params in its
// route.
type RootSearch = {
  view?: string;
  status?: StatusTab;
};

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()(
  {
    validateSearch: (search: Record<string, unknown>): RootSearch => ({
      view: parseView(search.view),
      status: parseEnum(search.status, STATUS_TABS),
    }),
    search: {
      // The hierarchy view survives switching screens.
      middlewares: [retainSearchParams(["view"])],
    },
    component: RootLayout,
  },
);

function parseView(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

function RootLayout() {
  return (
    <AppFrame screens={SCREENS}>
      <Outlet />
    </AppFrame>
  );
}
