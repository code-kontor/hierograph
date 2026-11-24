import { useQuery } from "@tanstack/react-query";
import {
  Link,
  useLocation,
  useNavigate,
  useSearch,
} from "@tanstack/react-router";
import { type ReactNode, useState } from "react";

import lupeUrl from "@/assets/hierograph-lupe.svg";
import { Message } from "@/design-system/ui/message";
import { useNoNativeContextMenu } from "@/design-system/useNoNativeContextMenu";
import { type GraphScope, GraphScopeProvider } from "@/frame/GraphScope";
import { GraphStatusIndicator } from "@/frame/GraphStatusIndicator";
import { HierarchyViewMenu } from "@/frame/HierarchyViewMenu";
import {
  graphStatusQueryOptions,
  hierarchyViewsQueryOptions,
} from "@/frame/queries";
import { currentScreen, type Screen } from "@/frame/screens";
import { isStatusTab, type StatusTab } from "@/frame/ServerStatus";
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
// (`GraphScope`), and the page's context menus: the app's own, never the
// browser's. Instead of a screen it shows a message when the screen can't
// work: no graph for a screen that needs one, the server not reachable, or an
// unknown or invalid hierarchy view. It also remembers the params each screen
// was last left with, per hierarchy view, so going back to a screen finds it
// as it was: the structure map with its boxes expanded and its arrows pinned.
export function AppFrame({ screens, children }: AppFrameProps) {
  useNoNativeContextMenu();
  const status = useQuery(graphStatusQueryOptions());
  const search: Record<string, unknown> & {
    view?: string;
    status?: unknown;
  } = useSearch({
    strict: false,
  });
  const view = search.view;
  const pathname = useLocation({ select: (location) => location.pathname });
  const screen = currentScreen(screens, withoutBase(pathname));

  const [remembered, setRemembered] = useState<ScreenParams>({});
  const own = screenParams(search);
  const key = screen && rememberKey(screen, view);
  if (key && JSON.stringify(remembered[key]) !== JSON.stringify(own)) {
    setRemembered({ ...remembered, [key]: own });
  }

  const graph = status.data?.server.graph;
  const workspace = status.data?.server.workspace;
  const viewsVersion = status.data?.server.viewsVersion ?? null;
  const unreachable = status.isError;
  const views = useQuery({
    ...hierarchyViewsQueryOptions(graph?.generation ?? null, viewsVersion),
    enabled: status.isSuccess,
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

  // Another hierarchy view on the same screen, as it was last left in that
  // view (or as it starts), with the params that don't depend on the view.
  const navigate = useNavigate();
  const chooseView = async (next: string | undefined) => {
    const target = screen ?? screens[0];
    const kept = Object.fromEntries(
      Object.entries(own).filter(([name]) =>
        screen?.keepOnViewChange?.includes(name),
      ),
    );
    await navigate({
      to: target?.path ?? "/",
      search: {
        ...(target && remembered[rememberKey(target, next)]),
        ...kept,
        view: next,
      },
    } as never);
  };

  // The status panel's tab (`status=`), set while the panel is open; opening,
  // switching and closing replace the history entry.
  const showStatus = async (tab: StatusTab | undefined) => {
    await navigate({
      to: ".",
      search: (previous: Record<string, unknown>) => ({
        ...previous,
        status: tab,
      }),
      replace: true,
    } as never);
  };

  const screenLinks = screens.map((entry) => (
    <ScreenLink
      key={String(entry.path)}
      screen={entry}
      search={{ ...remembered[rememberKey(entry, view)], view }}
    />
  ));

  // The scope covers the header too: its status panel answers in it.
  return (
    <GraphScopeProvider scope={scope}>
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
            <nav className="flex h-full items-stretch gap-0.5">
              {screenLinks}
            </nav>
          )}
          <div className="flex-1" />
          <HierarchyViewMenu
            view={view}
            views={views.data}
            onChoose={chooseView}
          />
          <GraphStatusIndicator
            server={status.data?.server}
            unreachable={unreachable}
            tab={isStatusTab(search.status) ? search.status : undefined}
            onTab={showStatus}
          />
        </header>
        <main className="min-h-0 flex-1 p-3">{content}</main>
      </div>
    </GraphScopeProvider>
  );
}

// A screen's own params, per screen and hierarchy view.
type ScreenParams = Record<string, Record<string, unknown>>;

function rememberKey(screen: Screen, view: string | undefined): string {
  return `${String(screen.path)} ${view ?? ""}`;
}

// The params of the current screen: all but the frame's `view`.
function screenParams(search: Record<string, unknown>) {
  return Object.fromEntries(
    Object.entries(search).filter(
      ([name]) => name !== "view" && name !== "status",
    ),
  );
}

type ScreenLinkProps = {
  screen: Screen;
  search: Record<string, unknown>;
};

function ScreenLink({ screen, search }: ScreenLinkProps) {
  return (
    <Link
      to={screen.path}
      search={search as never}
      className="text-fg-muted hover:text-fg after:bg-primary relative flex h-full items-center px-1 text-[13px] font-normal transition-colors after:absolute after:inset-x-0 after:-bottom-px after:h-0.5 after:rounded-[2px] after:content-['']"
      activeOptions={{ exact: screen.path === "/" }}
      activeProps={{ className: "text-primary font-semibold" }}
      inactiveProps={{ className: "after:hidden" }}
    >
      {screen.label}
    </Link>
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
