import { graphql, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
import { fixtureData, handlersFor } from "@/testing/msw/handlers";
import { worker } from "@/testing/msw/worker";
import { renderWithRouter } from "@/testing/render";

import { formatTime } from "./format";
import { CHOOSE_HINT, StartPage } from "./StartPage";

const SCREENS = [
  { path: "/", label: "Start", needsGraph: false },
] as unknown as Screen[];

// The start screen inside the frame, as the app renders it: the frame provides
// the graph scope and checks the hierarchy view.
function renderStart(search = "") {
  return renderWithRouter(
    <AppFrame screens={SCREENS}>
      <StartPage />
    </AppFrame>,
    "/",
    search,
  );
}

type StartRender = Awaited<ReturnType<typeof renderStart>>;

function block(screen: StartRender, name: string) {
  return screen.getByRole("region", { name });
}

// Opens a tab of the server pane and returns its panel.
async function tab(screen: StartRender, name: string) {
  await screen.getByRole("tab", { name }).click();
  return screen.getByRole("tabpanel", { name });
}

describe("StartPage", () => {
  it("groups the workspace, the graph, the server and the JVM in tabs, the workspace first", async () => {
    const screen = await renderStart();
    await expect
      .element(screen.getByRole("tab", { name: "Workspace" }))
      .toHaveAttribute("aria-selected", "true");

    const names = screen
      .getByRole("tab")
      .elements()
      .map((element) => element.textContent);
    expect(names).toEqual(["Workspace", "Graph", "Server", "JVM"]);
    await expect
      .element(screen.getByRole("tabpanel", { name: "Workspace" }))
      .toBeVisible();
  });

  it("opens the tab the URL names", async () => {
    const screen = await renderStart("?tab=jvm");

    await expect
      .element(screen.getByRole("tab", { name: "JVM" }))
      .toHaveAttribute("aria-selected", "true");
    await expect
      .element(screen.getByRole("tabpanel", { name: "JVM" }))
      .toBeVisible();
  });

  it("shows the first tab for an unknown tab in the URL", async () => {
    const screen = await renderStart("?tab=nope");

    await expect
      .element(screen.getByRole("tab", { name: "Workspace" }))
      .toHaveAttribute("aria-selected", "true");
  });

  it("puts the chosen tab into the URL, replacing the history entry", async () => {
    const screen = await renderStart("?view=layers");
    await expect
      .element(screen.getByRole("tab", { name: "Workspace" }))
      .toBeVisible();
    const length = screen.router.history.length;

    await screen.getByRole("tab", { name: "Graph" }).click();

    await expect
      .element(screen.getByRole("tabpanel", { name: "Graph" }))
      .toBeVisible();
    expect(screen.router.state.location.search).toEqual({
      view: "layers",
      tab: "graph",
    });
    expect(screen.router.history.length).toBe(length);

    // The first tab is the default and leaves no parameter behind.
    await screen.getByRole("tab", { name: "Workspace" }).click();

    await expect
      .element(screen.getByRole("tabpanel", { name: "Workspace" }))
      .toBeVisible();
    expect(screen.router.state.location.search).toEqual({ view: "layers" });
  });

  it("keeps the tab when a hierarchy view is chosen", async () => {
    const screen = await renderStart("?tab=graph");

    await block(screen, "Hierarchy views")
      .getByRole("link", { name: "layers" })
      .click();

    await expect
      .element(screen.getByRole("tabpanel", { name: "Graph" }))
      .toBeVisible();
    expect(screen.router.state.location.search).toEqual({
      tab: "graph",
      view: "layers",
    });
  });

  it("shows the workspace, its graph source and the artifacts not compiled", async () => {
    const screen = await renderStart();
    const workspace = await tab(screen, "Workspace");

    await expect
      .element(workspace.getByText("/fixture-app").first())
      .toBeVisible();
    await expect.element(workspace.getByText("Build manifest")).toBeVisible();
    // Paths below the workspace are relative; the full path is the tooltip.
    await expect
      .element(
        workspace.getByText(".hierograph/manifest.json", { exact: true }),
      )
      .toHaveAttribute("title", "/fixture-app/.hierograph/manifest.json");
    await expect
      .element(workspace.getByText("1 of 2 scanned, 1 not compiled yet"))
      .toBeVisible();
    // The summary is neutral; the note below carries the warning.
    await expect
      .element(workspace.getByText("1 of 2 scanned, 1 not compiled yet"))
      .not.toHaveClass(/text-status/);
    // File watching says what happens, not the watcher's name alone.
    await expect
      .element(workspace.getByText(/checked for changes periodically/))
      .toBeVisible();
    await expect
      .element(workspace.getByText(/reported as out of date/))
      .toBeVisible();
    await expect
      .element(
        workspace.getByText("io.hierograph.examples:fixture-app:1.0.0:test"),
      )
      .toBeVisible();
    await expect
      .element(workspace.getByText(/Not compiled yet, so not in the graph/))
      .toBeVisible();
    await expect
      .element(workspace.getByText(/→ target\/test-classes/))
      .toBeVisible();
  });

  it("shows the graph with its generation, load time and counts", async () => {
    const screen = await renderStart();
    const graph = await tab(screen, "Graph");

    // In the colour of the header's status dot.
    await expect
      .element(graph.getByText("Loaded", { exact: true }))
      .toHaveClass(/text-status-success/);
    await expect.element(graph.getByText("Generation")).toBeVisible();
    await expect
      .element(graph.getByText(formatTime("2026-01-01T00:00:00Z")))
      .toBeVisible();
    await expect.element(graph.getByText("26", { exact: true })).toBeVisible();
    await expect.element(graph.getByText("42", { exact: true })).toBeVisible();
  });

  it("shows the updater's phase while the graph is updated", async () => {
    worker.use(...handlersFor("updating"));
    const screen = await renderStart();

    await expect
      .element((await tab(screen, "Graph")).getByText("Updating (rescanning)"))
      .toHaveClass(/text-status-warning/);
  });

  it("without a graph still shows the workspace and the server", async () => {
    worker.use(...handlersFor("no-graph"));
    const screen = await renderStart();

    await expect
      .element((await tab(screen, "Graph")).getByText("No graph loaded"))
      .toHaveClass(/text-status-error/);
    await expect
      .element(
        (await tab(screen, "Workspace")).getByText("/fixture-app").first(),
      )
      .toBeVisible();
    await expect
      .element((await tab(screen, "Server")).getByText("0.0.0-fixture"))
      .toBeVisible();
  });

  it("shows the server's version, commit, start time and URLs", async () => {
    const screen = await renderStart();
    const server = await tab(screen, "Server");

    await expect.element(server.getByText("0.0.0-fixture")).toBeVisible();
    await expect.element(server.getByText("0000000")).toBeVisible();
    await expect
      .element(server.getByText(formatTime("2026-01-01T00:00:00Z")))
      .toBeVisible();
    await expect
      .element(server.getByText("http://localhost:8765/mcp"))
      .toBeVisible();
    await expect
      .element(server.getByText("http://localhost:8765/ui/"))
      .toBeVisible();
  });

  it("marks a server built from uncommitted changes", async () => {
    const data = structuredClone(fixtureData("StartScreen", "default")) as {
      server: { commit: { dirty: boolean } };
    };
    data.server.commit.dirty = true;
    worker.use(graphql.query("StartScreen", () => HttpResponse.json({ data })));
    const screen = await renderStart();

    await expect
      .element(
        (await tab(screen, "Server")).getByText(
          "0000000 (uncommitted changes)",
        ),
      )
      .toBeVisible();
  });

  it("lists the hierarchy views, an invalid one with its problems", async () => {
    const screen = await renderStart();
    const views = block(screen, "Hierarchy views");

    await expect
      .element(views.getByRole("link", { name: "layers" }))
      .toBeVisible();
    await expect
      .element(views.getByText(/application on top of the library/))
      .toBeVisible();
    await expect
      .element(views.getByText("project · 2 groups", { exact: true }))
      .toBeVisible();
    // An invalid view can't be chosen.
    await expect
      .element(views.getByRole("link", { name: "broken" }))
      .not.toBeInTheDocument();
    await expect
      .element(views.getByText("invalid", { exact: true }))
      .toBeVisible();
    await expect
      .element(views.getByText(/Invalid JSON at line 5/))
      .toBeVisible();
  });

  it("lists the scanned hierarchy first, current without a hierarchy view", async () => {
    const screen = await renderStart();
    const views = block(screen, "Hierarchy views");

    // Says what clicking a view does.
    await expect
      .element(views.getByText(/this UI only, not the agent/))
      .toBeVisible();
    // The project's name is in the header, not here.
    await expect.element(views.getByRole("heading")).not.toBeInTheDocument();

    const first = views.getByRole("listitem").first();
    await expect
      .element(first.getByRole("link", { name: "default" }))
      .toBeVisible();
    // The same scheme as every view: source · count.
    await expect
      .element(first.getByText("scanned · 1 module", { exact: true }))
      .toBeVisible();
    await expect
      .element(first.getByText("The hierarchy as scanned from the build."))
      .toBeVisible();
    await expect
      .element(first.getByRole("link"))
      .toHaveAttribute("aria-current", "page");
    await expect.element(first.getByText("current")).toBeVisible();
    // Hovering says what choosing does.
    await expect
      .element(first.getByRole("link"))
      .toHaveAttribute("title", CHOOSE_HINT);
  });

  it("sets the hierarchy view when one is chosen, and drops it with the default", async () => {
    const screen = await renderStart();
    const views = block(screen, "Hierarchy views");
    const currentRow = () =>
      views.getByRole("link").filter({ hasText: "current" });

    await views.getByRole("link", { name: "layers" }).click();

    await expect.element(currentRow()).toHaveTextContent(/^layers/);
    await expect
      .element(views.getByRole("link", { name: "layers" }))
      .toHaveAttribute("aria-current", "page");
    await expect
      .element(views.getByRole("link", { name: "default" }))
      .not.toHaveAttribute("aria-current");
    expect(screen.router.state.location.search).toEqual({ view: "layers" });
    await expect
      .element(screen.getByRole("banner").getByText("layers"))
      .toBeVisible();

    await views.getByRole("link", { name: "default" }).click();

    await expect.element(currentRow()).toHaveTextContent(/^default/);
    expect(screen.router.state.location.search).not.toHaveProperty("view");
  });

  it("shows the JVM's heap, threads, CPU load and garbage collection", async () => {
    const screen = await renderStart();
    const jvm = await tab(screen, "JVM");

    await expect
      .element(jvm.getByRole("meter", { name: "Heap" }))
      .toHaveAttribute("aria-valuenow", String(512 * 1024 * 1024));
    await expect
      .element(jvm.getByText("512 MiB used of 4 GiB, 1 GiB committed"))
      .toBeVisible();
    await expect.element(jvm.getByText("256 MiB")).toBeVisible();
    await expect
      .element(jvm.getByText("128 MiB used, 160 MiB committed, no limit"))
      .toBeVisible();
    await expect.element(jvm.getByText("5%")).toBeVisible();
    await expect
      .element(jvm.getByText("42 live, 48 peak, 30 daemon"))
      .toBeVisible();
    await expect.element(jvm.getByText("1 h 0 min")).toBeVisible();
    await expect
      .element(jvm.getByText("21.0.0 (Fixture Vendor)"))
      .toBeVisible();
    await expect.element(jvm.getByText("G1 Young Generation")).toBeVisible();
    await expect.element(jvm.getByText("12 collections, 340 ms")).toBeVisible();
  });

  it("shows a JVM without a heap limit, a collection or CPU load", async () => {
    const data = structuredClone(fixtureData("StartScreen", "default")) as {
      server: {
        jvm: {
          heap: { maxBytes: number | null; usedAfterGcBytes: number | null };
          processCpuLoad: number | null;
        };
      };
    };
    data.server.jvm.heap.maxBytes = null;
    data.server.jvm.heap.usedAfterGcBytes = null;
    data.server.jvm.processCpuLoad = null;
    worker.use(graphql.query("StartScreen", () => HttpResponse.json({ data })));
    const screen = await renderStart();
    const jvm = await tab(screen, "JVM");

    await expect
      .element(jvm.getByText("512 MiB used, 1 GiB committed, no limit"))
      .toBeVisible();
    // Measured against the committed heap instead.
    await expect
      .element(jvm.getByRole("meter", { name: "Heap" }))
      .toHaveAttribute("aria-valuemax", String(1024 * 1024 * 1024));
    await expect.element(jvm.getByText("—")).toBeVisible();
    await expect.element(jvm.getByText("no collection yet")).toBeVisible();
  });

  it("says when the server can't be reached", async () => {
    worker.use(graphql.query("StartScreen", () => HttpResponse.error()));
    const screen = await renderStart();

    await expect
      .element(screen.getByText("The Hierograph server can't be reached"))
      .toBeVisible();
  });
});
