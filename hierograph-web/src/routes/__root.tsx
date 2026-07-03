import { createRootRoute, Link, Outlet } from "@tanstack/react-router";

export const Route = createRootRoute({
  component: RootLayout,
});

function RootLayout() {
  return (
    <div className="flex h-svh flex-col">
      <header className="flex h-14 shrink-0 items-center gap-6 border-b px-4">
        <Link to="/" className="text-lg font-bold tracking-tight">
          hierograph
        </Link>
        <nav className="flex items-center gap-4 text-sm">
          <Link
            to="/dependencies"
            className="text-muted-foreground hover:text-foreground transition-colors"
            activeProps={{
              className:
                "font-semibold text-foreground underline underline-offset-4",
            }}
          >
            Dependencies
          </Link>
          <Link
            to="/xref"
            className="text-muted-foreground hover:text-foreground transition-colors"
            activeProps={{
              className:
                "font-semibold text-foreground underline underline-offset-4",
            }}
          >
            Cross-Reference
          </Link>
        </nav>
      </header>
      <main className="min-h-0 flex-1">
        <Outlet />
      </main>
    </div>
  );
}
