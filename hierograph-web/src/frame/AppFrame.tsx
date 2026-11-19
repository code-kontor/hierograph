import { useQuery } from "@tanstack/react-query";
import {
  Link,
  useLocation,
  useNavigate,
  useSearch,
} from "@tanstack/react-router";
import type { ReactNode } from "react";

import lupeUrl from "@/assets/hierograph-lupe.svg";
import { Message } from "@/design-system/ui/message";
import { type GraphScope, GraphScopeProvider } from "@/frame/GraphScope";
import { GraphStatusIndicator } from "@/frame/GraphStatusIndicator";
import {
  graphStatusQueryOptions,
  hierarchyViewsQueryOptions,
} from "@/frame/queries";
import { currentScreen, type Screen } from "@/frame/screens";
import type { HierarchyViewsQuery } from "@/graphql/generated/graphql";

type AppFrameProps = {
  screens: readonly Screen[];
  children: ReactNode;
};

type ViewCheck =
  | { kind: "none" }
  | { kind: "pending" }
  | { kind: "valid" }
  | { kind: "unknown" }
  | { kind: "invalid"; problems: string[] };

function checkView(
  view: string | undefined,
  views: HierarchyViewsQuery["views"] | undefined,
): ViewCheck {
  if (view === undefined) return { kind: "none" };
  if (!views) return { kind: "pending" };
  const found = views.find((candidate) => candidate.name === view);
  if (!found) return { kind: "unknown" };
  return found.valid
    ? { kind: "valid" }
    : { kind: "invalid", problems: found.problems };
}

// The app shell every screen is rendered into. It owns what applies to all
// screens: the header and navigation, the graph status (polled), the
// hierarchy view (`view` in the URL), and the scope graph queries run in
// (`GraphScope`). Instead of a screen it shows a message when the screen
// can't work: no graph for a screen that needs one, the server not reachable,
// or an unknown or invalid hierarchy view.
export function AppFrame({ screens, children }: AppFrameProps) {
  const status = useQuery(graphStatusQueryOptions());
  const search: { view?: string } = useSearch({ strict: false });
  const view = search.view;
  const pathname = useLocation({ select: (location) => location.pathname });
  const screen = currentScreen(screens, withoutBase(pathname));

  const graph = status.data?.server.graph;
  const workspace = status.data?.server.workspace;
  const viewsVersion = status.data?.server.viewsVersion ?? null;
  const unreachable = status.isError;
  const views = useQuery({
    ...hierarchyViewsQueryOptions(graph?.generation ?? null, viewsVersion),
    enabled: view !== undefined && status.isSuccess,
  });
  const viewCheck = checkView(view, views.data?.views);

  const scope: GraphScope | undefined =
    graph?.generation &&
    graph.state !== "UNAVAILABLE" &&
    (viewCheck.kind === "none" || viewCheck.kind === "valid")
      ? { generation: graph.generation, view, viewsVersion }
      : undefined;

  const needsGraph = screen?.needsGraph ?? false;
  let content: ReactNode = children;
  if (viewCheck.kind === "unknown" || viewCheck.kind === "invalid") {
    content = <ViewProblem view={view ?? ""} check={viewCheck} />;
  } else if (needsGraph && unreachable) {
    content = (
      <Message variant="error" title="The Hierograph server can't be reached">
        The UI keeps checking and continues as soon as the server answers.
      </Message>
    );
  } else if (needsGraph && graph?.state === "UNAVAILABLE") {
    content = (
      <Message variant="info" title="No graph is loaded">
        The server has no graph to show yet. The status in the header follows
        it; this screen appears as soon as a graph is loaded.
      </Message>
    );
  }

  const screenLinks = screens.map((entry) => (
    <ScreenLink key={String(entry.path)} screen={entry} />
  ));

  return (
    <div className="flex h-svh flex-col">
      <header className="border-border-strong bg-panel flex h-[52px] shrink-0 items-center gap-7 border-b px-[18px]">
        {/* "hierograph – <project>": the project the server serves, by name,
            once the server has answered. */}
        <Link
          to="/"
          title={workspace ? `hierograph: ${workspace}` : "hierograph"}
          className="flex min-w-0 items-center gap-[9px]"
        >
          <img src={lupeUrl} alt="" className="block h-[26px] w-auto" />
          <span className="text-fg truncate font-sans text-[18px] font-bold tracking-[-0.01em]">
            hierograph
            {workspace && (
              <span className="text-fg-muted font-semibold">
                {` – ${workspaceName(workspace)}`}
              </span>
            )}
          </span>
        </Link>
        {/* A single screen needs no navigation: the brand links to it. */}
        {screens.length > 1 && (
          <nav className="flex h-full items-stretch gap-0.5">{screenLinks}</nav>
        )}
        <div className="flex-1" />
        {view !== undefined && <HierarchyViewLabel view={view} />}
        <GraphStatusIndicator status={graph} unreachable={unreachable} />
      </header>
      <main className="min-h-0 flex-1 p-3">
        <GraphScopeProvider scope={scope}>{content}</GraphScopeProvider>
      </main>
    </div>
  );
}

type ScreenLinkProps = {
  screen: Screen;
};

function ScreenLink({ screen }: ScreenLinkProps) {
  return (
    <Link
      to={screen.path}
      className="text-fg-muted hover:text-fg after:bg-primary relative flex h-full items-center px-1 text-[13px] font-normal transition-colors after:absolute after:inset-x-0 after:-bottom-px after:h-0.5 after:rounded-[2px] after:content-['']"
      activeOptions={{ exact: screen.path === "/" }}
      activeProps={{ className: "text-primary font-semibold" }}
      inactiveProps={{ className: "after:hidden" }}
    >
      {screen.label}
    </Link>
  );
}

type HierarchyViewLabelProps = {
  view: string;
};

function HierarchyViewLabel({ view }: HierarchyViewLabelProps) {
  return (
    <div className="text-fg-muted text-[12px]">
      Hierarchy view{" "}
      <span className="text-fg font-mono font-medium">{view}</span>
    </div>
  );
}

type ViewProblemProps = {
  view: string;
  check: { kind: "unknown" } | { kind: "invalid"; problems: string[] };
};

function ViewProblem({ view, check }: ViewProblemProps) {
  const navigate = useNavigate();
  const showWithout = async () => {
    await navigate({
      to: ".",
      search: (previous: Record<string, unknown>) => ({
        ...previous,
        view: undefined,
      }),
    } as never);
  };
  const title =
    check.kind === "unknown"
      ? `There is no hierarchy view "${view}"`
      : `The hierarchy view "${view}" is invalid`;
  return (
    <Message variant="error" title={title}>
      {check.kind === "invalid" && <ProblemList problems={check.problems} />}
      <button
        type="button"
        onClick={showWithout}
        className="text-primary mt-1 underline-offset-2 hover:underline"
      >
        Show without a hierarchy view
      </button>
    </Message>
  );
}

type ProblemListProps = {
  problems: string[];
};

function ProblemList({ problems }: ProblemListProps) {
  const items = problems.map((problem) => (
    <ProblemItem key={problem} problem={problem} />
  ));
  return <ul className="mb-1 list-disc pl-4">{items}</ul>;
}

type ProblemItemProps = {
  problem: string;
};

function ProblemItem({ problem }: ProblemItemProps) {
  return <li className="font-mono text-[12px]">{problem}</li>;
}

// The last segment of the workspace's path: the project's name.
function workspaceName(path: string): string {
  return path.split(/[/\\]/).filter(Boolean).at(-1) ?? path;
}

// The location's path without the base path the UI is served under (`/ui`).
function withoutBase(pathname: string): string {
  const base = import.meta.env.BASE_URL.replace(/\/$/, "");
  return base && pathname.startsWith(base)
    ? pathname.slice(base.length) || "/"
    : pathname;
}
