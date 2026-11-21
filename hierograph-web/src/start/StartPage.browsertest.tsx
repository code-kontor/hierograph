import { graphql, HttpResponse } from "msw";
import { afterEach, describe, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

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

  it("shows the views as cards, or as a list as chosen in the block's corner", async () => {
    try {
      const screen = await renderStart();
      const views = block(screen, "Hierarchy views");
      const list = views.getByRole("button", { name: "Show as a list" });
      const cards = views.getByRole("button", { name: "Show as cards" });
      await expect.element(cards).toHaveAttribute("aria-pressed", "true");
      await expect.element(list).toHaveAttribute("aria-pressed", "false");
      // Every row as tall as the tallest card: all of one size.
      await expect
        .element(views.getByRole("list").first())
        .toHaveClass("grid", "auto-rows-fr");

      await list.click();

      await expect.element(list).toHaveAttribute("aria-pressed", "true");
      await expect.element(cards).toHaveAttribute("aria-pressed", "false");
      await expect
        .element(views.getByRole("list").first())
        .not.toHaveClass("grid");
      // Kept by the browser.
      expect(localStorage.getItem("start-views-layout")).toBe('"list"');
    } finally {
      localStorage.clear();
    }
  });

  it("lists the scanned hierarchy first, current without a hierarchy view", async () => {
    const screen = await renderStart();
    const views = block(screen, "Hierarchy views");

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
    // Marked by its highlight only, no badge.
    await expect
      .element(first.getByText("current", { exact: true }))
      .not.toBeInTheDocument();
    // Hovering says what choosing does.
    await expect
      .element(first.getByRole("link"))
      .toHaveAttribute("title", CHOOSE_HINT);
  });

  it("sets the hierarchy view when one is chosen, and drops it with the default", async () => {
    const screen = await renderStart();
    const views = block(screen, "Hierarchy views");
    await views.getByRole("link", { name: "layers" }).click();

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

    await expect
      .element(views.getByRole("link", { name: "default" }))
      .toHaveAttribute("aria-current", "page");
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

  it("runs a garbage collection and shows what it kept", async () => {
    const collected = structuredClone(
      fixtureData("StartScreen", "default"),
    ) as { server: { jvm: { heap: { usedAfterGcBytes: number } } } };
    collected.server.jvm.heap.usedAfterGcBytes = 64 * 1024 * 1024;
    let runs = 0;
    worker.use(
      graphql.mutation("RunGarbageCollection", () => {
        runs++;
        // From now on, the server reports the collection.
        worker.use(
          graphql.query("StartScreen", () =>
            HttpResponse.json({ data: collected }),
          ),
        );
        return HttpResponse.json({
          data: {
            runGarbageCollection: {
              heap: { usedAfterGcBytes: 64 * 1024 * 1024 },
            },
          },
        });
      }),
    );
    const screen = await renderStart();
    const jvm = await tab(screen, "JVM");
    await expect.element(jvm.getByText("256 MiB")).toBeVisible();

    await jvm.getByRole("button", { name: "Run GC" }).click();

    await expect.element(jvm.getByText("64 MiB")).toBeVisible();
    expect(runs).toBe(1);
  });

  it("says when a garbage collection fails", async () => {
    worker.use(
      graphql.mutation("RunGarbageCollection", () =>
        HttpResponse.json({ errors: [{ message: "refused" }] }),
      ),
    );
    const screen = await renderStart();
    const jvm = await tab(screen, "JVM");

    await jvm.getByRole("button", { name: "Run GC" }).click();

    await expect
      .element(jvm.getByText(/The collection failed: refused/))
      .toBeVisible();
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

  describe("on a wide screen", () => {
    afterEach(async () => {
      localStorage.clear();
      await page.viewport(414, 896);
    });

    it("puts the hierarchy on the left, the split moved by the user", async () => {
      await page.viewport(1400, 900);
      const screen = await renderStart();
      const split = screen.getByRole("separator", {
        name: "Resize the hierarchy",
        exact: true,
      });
      // Without the stylesheet the handle has no width: there, not visible.
      await expect.element(split).toBeInTheDocument();
      await expect.element(screen.getByRole("tree")).toBeVisible();
      const tree = screen.getByRole("tree").element();
      const views = block(screen, "Hierarchy views").element();
      expect(tree.getBoundingClientRect().right).toBeLessThanOrEqual(
        views.getBoundingClientRect().left,
      );
      const before = Number(split.element().getAttribute("aria-valuenow"));

      (split.element() as HTMLElement).focus();
      await userEvent.keyboard("{ArrowRight}");

      await expect
        .poll(() => Number(split.element().getAttribute("aria-valuenow")))
        .toBeGreaterThan(before);
    });

    it("puts the hierarchy views above the server's tabs, the split moved by the user", async () => {
      await page.viewport(1400, 900);
      const screen = await renderStart();
      const split = screen.getByRole("separator", {
        name: "Resize the hierarchy views",
      });
      await expect.element(split).toBeInTheDocument();
      const views = block(screen, "Hierarchy views").element();
      const tabs = screen.getByRole("tablist").element();
      // Without the stylesheet a box doesn't clip its content: tops only.
      expect(views.getBoundingClientRect().top).toBeLessThan(
        tabs.getBoundingClientRect().top,
      );
      const before = Number(split.element().getAttribute("aria-valuenow"));

      (split.element() as HTMLElement).focus();
      await userEvent.keyboard("{ArrowUp}");

      await expect
        .poll(() => Number(split.element().getAttribute("aria-valuenow")))
        .toBeLessThan(before);
    });
  });

  it("says when the server can't be reached", async () => {
    worker.use(graphql.query("StartScreen", () => HttpResponse.error()));
    const screen = await renderStart();

    await expect
      .element(screen.getByText("The Hierograph server can't be reached"))
      .toBeVisible();
  });
});
