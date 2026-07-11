import type { QueryClient } from "@tanstack/react-query";
import {
  createRootRouteWithContext,
  Link,
  Outlet,
  retainSearchParams,
  stripSearchParams,
  useLocation,
} from "@tanstack/react-router";
import { PanelRight } from "lucide-react";
import { twMerge } from "tailwind-merge";

import lupeUrl from "@/assets/hierograph-lupe.svg";
import { DevPanel } from "@/dev-panel/DevPanel";
import { DevPanelProvider, useDevPanel } from "@/dev-panel/DevPanelContext";
import {
  parseEnum,
  parseIdList,
  parseSingleId,
  type Side,
  SIDES,
  type Tab,
  TABS,
} from "@/routing/searchCodec";
import { FocusBridgeProvider } from "@/selection/FocusBridge";

// Union of every screen's search params, all optional. The root only coerces
// types via the shared codec — the cascade/validation rules live in the leaf
// routes (`/dsm`, `/cross-reference-explorer`, `/dependency-diagram`).
// `retainSearchParams(true)` keeps all param sets across route switches so the
// header `<Link>`s need not carry `search`; `stripSearchParams` drops the
// `tab` default from the URL.
type RootSearch = {
  subject_ids?: string[];
  from_id?: string;
  to_id?: string;
  tab?: Tab;
  center_ids?: string[];
  side?: Side;
  aggregated?: Side;
  drill_ids?: string[];
  expanded_ids?: string[];
};

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()(
  {
    validateSearch: (search: Record<string, unknown>): RootSearch => ({
      subject_ids: parseIdList(search.subject_ids),
      from_id: parseSingleId(search.from_id),
      to_id: parseSingleId(search.to_id),
      tab: parseEnum(search.tab, TABS),
      center_ids: parseIdList(search.center_ids),
      side: parseEnum(search.side, SIDES),
      aggregated: parseEnum(search.aggregated, SIDES),
      drill_ids: parseIdList(search.drill_ids),
      expanded_ids: parseIdList(search.expanded_ids),
    }),
    search: {
      middlewares: [
        retainSearchParams(true),
        stripSearchParams({ tab: "usages" }),
      ],
    },
    component: RootLayout,
  },
);

function OpenDevPanelButton() {
  const { open, setOpen } = useDevPanel();

  return (
    <button
      type="button"
      onClick={() => setOpen(!open)}
      aria-pressed={open}
      aria-label={open ? "Close Dev Panel" : "Open Dev Panel"}
      title="Toggle Dev Panel (dev)"
      className={twMerge(
        "text-fg-muted hover:text-fg hover:bg-panel-header border-border flex h-7 items-center gap-1.5 rounded-[6px] border px-2 text-[12px] font-normal transition-colors",
        open && "bg-panel-header text-fg border-border-strong",
      )}
    >
      <PanelRight className="h-[14px] w-[14px]" />
      Dev Panel
    </button>
  );
}

function RootLayout() {
  const location = useLocation();

  return (
    <DevPanelProvider>
      <div className="flex h-svh flex-col">
        <header className="border-border-strong bg-panel flex h-[52px] shrink-0 items-center gap-7 border-b px-[18px]">
          <Link to="/" className="flex items-center gap-[9px]">
            <img src={lupeUrl} alt="" className="block h-[26px] w-auto" />
            <span className="text-fg font-sans text-[18px] font-bold tracking-[-0.01em]">
              hierograph
            </span>
          </Link>
          <nav className="flex h-full items-stretch gap-0.5">
            <Link
              to="/dsm"
              className="text-fg-muted hover:text-fg after:bg-primary relative flex h-full items-center px-1 text-[13px] font-normal transition-colors after:absolute after:inset-x-0 after:-bottom-px after:h-0.5 after:rounded-[2px] after:content-['']"
              activeProps={{
                className: "text-primary font-semibold",
              }}
              inactiveProps={{
                className: "after:hidden",
              }}
            >
              DSM
            </Link>
            <Link
              to="/cross-reference-explorer"
              className="text-fg-muted hover:text-fg after:bg-primary relative flex h-full items-center px-1 text-[13px] font-normal transition-colors after:absolute after:inset-x-0 after:-bottom-px after:h-0.5 after:rounded-[2px] after:content-['']"
              activeProps={{
                className: "text-primary font-semibold",
              }}
              inactiveProps={{
                className: "after:hidden",
              }}
            >
              Cross-Reference Explorer
            </Link>
            <Link
              to="/dependency-diagram"
              className="text-fg-muted hover:text-fg after:bg-primary relative flex h-full items-center px-1 text-[13px] font-normal transition-colors after:absolute after:inset-x-0 after:-bottom-px after:h-0.5 after:rounded-[2px] after:content-['']"
              activeProps={{
                className: "text-primary font-semibold",
              }}
              inactiveProps={{
                className: "after:hidden",
              }}
            >
              Dependency Diagram
            </Link>
          </nav>
          <div className="flex-1" />
          {import.meta.env.DEV && <OpenDevPanelButton />}
          <div className="text-fg-subtle font-mono text-[11px] font-normal">
            {location.pathname}
          </div>
        </header>
        <main className="min-h-0 flex-1 p-3">
          <FocusBridgeProvider>
            <Outlet />
            {import.meta.env.DEV && <DevPanel />}
          </FocusBridgeProvider>
        </main>
      </div>
    </DevPanelProvider>
  );
}
