import { graphql, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { userEvent } from "vitest/browser";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
import { fixtureData, handlersFor } from "@/testing/msw/handlers";
import { worker } from "@/testing/msw/worker";
import { renderWithRouter } from "@/testing/render";

import { formatTime } from "./format";

const SCREENS = [
  { path: "/", label: "Start", needsGraph: false },
] as unknown as Screen[];

// The frame with a stand-in screen; `search` opens the status panel.
function renderStatus(search = "") {
  return renderWithRouter(
    <AppFrame screens={SCREENS}>
      <p>the screen</p>
    </AppFrame>,
    "/",
    search,
  );
}

type StatusRender = Awaited<ReturnType<typeof renderStatus>>;

// Opens the status panel on a tab and returns the tab's content.
async function tab(screen: StatusRender, name: string) {
  if (!screen.getByRole("tablist").query()) {
    await screen
      .getByRole("button", { name: /Show the server's status/ })
      .click();
  }
  await screen.getByRole("tab", { name }).click();
  return screen.getByRole("tabpanel", { name });
}

describe("ServerStatus", () => {
  it("opens from the graph status in the header, on the graph tab", async () => {
    const screen = await renderStatus();
    await expect.element(screen.getByRole("tablist")).not.toBeInTheDocument();
    const length = screen.router.history.length;

    await screen
      .getByRole("button", { name: /Show the server's status/ })
      .click();

    const names = screen
      .getByRole("tab")
      .elements()
      .map((element) => element.textContent);
    expect(names).toEqual(["Graph", "Workspace", "Server", "JVM"]);
    await expect
      .element(screen.getByRole("tab", { name: "Graph" }))
      .toHaveAttribute("aria-selected", "true");
    // Open is in the URL, so a link can open it; no history entry.
    expect(screen.router.state.location.search).toEqual({
      status: "graph",
    });
    expect(screen.router.history.length).toBe(length);
  });

  it("opens on the tab the URL names, and drops an unknown one", async () => {
    const screen = await renderStatus("?status=jvm");
    await expect
      .element(screen.getByRole("tab", { name: "JVM" }))
      .toHaveAttribute("aria-selected", "true");
    await screen.unmount();

    const unknown = await renderStatus("?status=nope");
    await expect.element(unknown.getByText("the screen")).toBeVisible();
    await expect.element(unknown.getByRole("tablist")).not.toBeInTheDocument();
  });

  it("puts the chosen tab into the URL, and drops it when closed", async () => {
    const screen = await renderStatus("?view=layers&status=server");
    const length = screen.router.history.length;

    await screen.getByRole("tab", { name: "Graph" }).click();

    await expect
      .poll(() => screen.router.state.location.search)
      .toEqual({ view: "layers", status: "graph" });
    expect(screen.router.history.length).toBe(length);

    // Escape closes it, as a click outside does.
    await userEvent.keyboard("{Escape}");

    await expect
      .poll(() => screen.router.state.location.search)
      .toEqual({ view: "layers" });
    await expect.element(screen.getByRole("tablist")).not.toBeInTheDocument();
  });

  it("marks the graph status when something needs attention", async () => {
    const screen = await renderStatus();
    // The fixture-app declares a test artifact it hasn't compiled.
    const button = screen.getByRole("button", {
      name: "Graph loaded: 1 artifact not compiled yet. Show the server's status",
    });
    await expect.element(button).toBeVisible();
    await expect
      .element(screen.getByTestId("status-attention"))
      .toBeInTheDocument();
    await screen.unmount();

    const data = structuredClone(fixtureData("GraphStatus", "default")) as {
      server: { artifacts: { notCompiled: unknown[] } };
    };
    data.server.artifacts.notCompiled = [];
    worker.use(graphql.query("GraphStatus", () => HttpResponse.json({ data })));
    const clean = await renderStatus();
    await expect
      .element(
        clean.getByRole("button", {
          name: "Graph loaded. Show the server's status",
        }),
      )
      .toBeVisible();
    await expect
      .element(clean.getByTestId("status-attention"))
      .not.toBeInTheDocument();
  });

  it("shows the workspace, its graph source and the artifacts not compiled", async () => {
    const screen = await renderStatus();
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
    const notice = workspace.getByRole("region", { name: "Not compiled yet" });
    await expect
      .element(notice.getByRole("heading"))
      .toHaveTextContent(/^Not compiled yet$/);
    await expect
      .element(notice.getByText(/Not in the graph: they are scanned/))
      .toBeVisible();
    await expect
      .element(workspace.getByText(/→ target\/test-classes/))
      .toBeVisible();
  });

  it("shows the graph with its generation, load time and counts", async () => {
    const screen = await renderStatus();
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

  it("shows below the graph's facts, marked as excluded, what the hierarchy view excludes", async () => {
    const screen = await renderStatus("?view=locations");
    const graph = await tab(screen, "Graph");
    const section = graph.getByRole("region", { name: "Excluded by the view" });

    await expect
      .element(section.getByRole("heading"))
      .toHaveTextContent(/^Excluded$/);
    await expect
      .element(section.getByText(/The view\s*locations\s*leaves out 1 node/))
      // the module's 102 nodes, and 4 of External Types only it referenced
      .toHaveTextContent(/106 nodes in all/);
    // The list only on demand: it can name hundreds of nodes.
    await expect.element(section.getByRole("listitem")).not.toBeInTheDocument();

    await section.getByRole("button", { name: "Show them" }).click();

    await expect
      .element(section.getByRole("listitem"))
      .toHaveTextContent("org.hg.fixture.basic");
    await expect
      .element(section.getByRole("button", { name: "Hide them" }))
      .toHaveAttribute("aria-expanded", "true");
    // The graph's own facts stay above it.
    await expect.element(graph.getByText("Generation")).toBeVisible();
  });

  it("shows nothing excluded for a view without exclusions, nor without a view", async () => {
    for (const search of ["?view=layers", ""]) {
      const screen = await renderStatus(search);
      const graph = await tab(screen, "Graph");
      await expect.element(graph.getByText("Generation")).toBeVisible();

      await expect
        .element(graph.getByRole("region", { name: "Excluded by the view" }))
        .not.toBeInTheDocument();
      await screen.unmount();
    }
  });

  it("shows the updater's phase while the graph is updated", async () => {
    worker.use(...handlersFor("updating"));
    const screen = await renderStatus();

    await expect
      .element((await tab(screen, "Graph")).getByText("Updating (rescanning)"))
      .toHaveClass(/text-status-warning/);
  });

  it("without a graph still shows the workspace and the server", async () => {
    worker.use(...handlersFor("no-graph"));
    const screen = await renderStatus();

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
    const screen = await renderStatus();
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
    const data = structuredClone(fixtureData("ServerStatus", "default")) as {
      server: { commit: { dirty: boolean } };
    };
    data.server.commit.dirty = true;
    worker.use(
      graphql.query("ServerStatus", () => HttpResponse.json({ data })),
    );
    const screen = await renderStatus();

    await expect
      .element(
        (await tab(screen, "Server")).getByText(
          "0000000 (uncommitted changes)",
        ),
      )
      .toBeVisible();
  });

  it("shows the JVM's heap, threads, CPU load and garbage collection", async () => {
    const screen = await renderStatus();
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
      fixtureData("ServerStatus", "default"),
    ) as { server: { jvm: { heap: { usedAfterGcBytes: number } } } };
    collected.server.jvm.heap.usedAfterGcBytes = 64 * 1024 * 1024;
    let runs = 0;
    worker.use(
      graphql.mutation("RunGarbageCollection", () => {
        runs++;
        // From now on, the server reports the collection.
        worker.use(
          graphql.query("ServerStatus", () =>
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
    const screen = await renderStatus();
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
    const screen = await renderStatus();
    const jvm = await tab(screen, "JVM");

    await jvm.getByRole("button", { name: "Run GC" }).click();

    await expect
      .element(jvm.getByText(/The collection failed: refused/))
      .toBeVisible();
  });

  it("shows a JVM without a heap limit, a collection or CPU load", async () => {
    const data = structuredClone(fixtureData("ServerStatus", "default")) as {
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
    worker.use(
      graphql.query("ServerStatus", () => HttpResponse.json({ data })),
    );
    const screen = await renderStatus();
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

  it("says in the panel when the server can't be reached", async () => {
    worker.use(graphql.query("ServerStatus", () => HttpResponse.error()));
    const screen = await renderStatus("?status=workspace");

    await expect
      .element(screen.getByText(/The Hierograph server can't be reached/))
      .toBeVisible();
  });
});
