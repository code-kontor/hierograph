import type { QueryClient } from "@tanstack/react-query";
import {
  createRootRouteWithContext,
  Link,
  Outlet,
  useLocation,
} from "@tanstack/react-router";
import { PanelRight } from "lucide-react";

import lupeUrl from "@/assets/hierograph-lupe.svg";
import {
  NodeDetailsWidgetProvider,
  SelectionProvider,
  useNodeDetailsWidget,
} from "@/selection/SelectionContext";

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()(
  {
    component: RootLayout,
  },
);

function OpenNodeDetailsButton() {
  const { setOpen } = useNodeDetailsWidget();

  return (
    <button
      type="button"
      onClick={() => setOpen(true)}
      aria-label="Open Node Details"
      title="Open Node Details (dev)"
      className="text-fg-muted hover:text-fg hover:bg-panel-header border-border flex h-7 items-center gap-1.5 rounded-[6px] border px-2 text-[12px] font-normal transition-colors"
    >
      <PanelRight className="h-[14px] w-[14px]" />
      Node Details
    </button>
  );
}

function RootLayout() {
  const location = useLocation();

  return (
    <NodeDetailsWidgetProvider>
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
              to="/dependencies"
              className="text-fg-muted hover:text-fg after:bg-primary relative flex h-full items-center px-1 text-[13px] font-normal transition-colors after:absolute after:inset-x-0 after:-bottom-px after:h-0.5 after:rounded-[2px] after:content-['']"
              activeProps={{
                className: "text-primary font-semibold",
              }}
              inactiveProps={{
                className: "after:hidden",
              }}
            >
              Dependencies
            </Link>
            <Link
              to="/cross-references"
              className="text-fg-muted hover:text-fg after:bg-primary relative flex h-full items-center px-1 text-[13px] font-normal transition-colors after:absolute after:inset-x-0 after:-bottom-px after:h-0.5 after:rounded-[2px] after:content-['']"
              activeProps={{
                className: "text-primary font-semibold",
              }}
              inactiveProps={{
                className: "after:hidden",
              }}
            >
              Cross References
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
              to="/xref"
              className="text-fg-muted hover:text-fg after:bg-primary relative flex h-full items-center px-1 text-[13px] font-normal transition-colors after:absolute after:inset-x-0 after:-bottom-px after:h-0.5 after:rounded-[2px] after:content-['']"
              activeProps={{
                className: "text-primary font-semibold",
              }}
              inactiveProps={{
                className: "after:hidden",
              }}
            >
              Cross-Reference (Prototype)
            </Link>
          </nav>
          <div className="flex-1" />
          {import.meta.env.DEV && <OpenNodeDetailsButton />}
          <div className="text-fg-subtle font-mono text-[11px] font-normal">
            {location.pathname}
          </div>
        </header>
        <main className="min-h-0 flex-1 p-3">
          <SelectionProvider>
            <Outlet />
          </SelectionProvider>
        </main>
      </div>
    </NodeDetailsWidgetProvider>
  );
}
