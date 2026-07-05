import {
  createRootRoute,
  Link,
  Outlet,
  useLocation,
} from "@tanstack/react-router";

import lupeUrl from "@/assets/hierograph-lupe.svg";

export const Route = createRootRoute({
  component: RootLayout,
});

function RootLayout() {
  const location = useLocation();

  return (
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
            to="/xref"
            className="text-fg-muted hover:text-fg after:bg-primary relative flex h-full items-center px-1 text-[13px] font-normal transition-colors after:absolute after:inset-x-0 after:-bottom-px after:h-0.5 after:rounded-[2px] after:content-['']"
            activeProps={{
              className: "text-primary font-semibold",
            }}
            inactiveProps={{
              className: "after:hidden",
            }}
          >
            Cross-Reference
          </Link>
        </nav>
        <div className="flex-1" />
        <div className="text-fg-subtle font-mono text-[11px] font-normal">
          {location.pathname}
        </div>
      </header>
      <main className="min-h-0 flex-1 p-3">
        <Outlet />
      </main>
    </div>
  );
}
