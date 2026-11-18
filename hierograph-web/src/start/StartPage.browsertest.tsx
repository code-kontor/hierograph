import { graphql, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
import { fixtureData, handlersFor } from "@/testing/msw/handlers";
import { worker } from "@/testing/msw/worker";
import { renderWithRouter } from "@/testing/render";

import { formatTime } from "./format";
import { StartPage } from "./StartPage";

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

describe("StartPage", () => {
  it("points to the agent session for opening screens", async () => {
    const screen = await renderStart();

    await expect
      .element(screen.getByText(/Screens are opened from your agent session/))
      .toBeVisible();
  });

  it("shows the workspace, its graph source and the artifacts not compiled", async () => {
    const screen = await renderStart();
    const workspace = block(screen, "Workspace");

    await expect
      .element(workspace.getByRole("heading", { name: "fixture-app" }))
      .toBeVisible();
    await expect
      .element(workspace.getByText("/fixture-app").first())
      .toBeVisible();
    await expect.element(workspace.getByText("Build manifest")).toBeVisible();
    await expect
      .element(workspace.getByText("/fixture-app/.hierograph/manifest.json"))
      .toBeVisible();
    await expect.element(workspace.getByText("polling")).toBeVisible();
    await expect.element(workspace.getByText("notify")).toBeVisible();
    await expect
      .element(
        workspace.getByText("io.hierograph.examples:fixture-app:1.0.0:test"),
      )
      .toBeVisible();
    await expect
      .element(workspace.getByText(/missing from the graph/))
      .toBeVisible();
  });

  it("shows the graph with its generation, load time and counts", async () => {
    const screen = await renderStart();
    const graph = block(screen, "Graph");

    await expect
      .element(graph.getByText("Loaded", { exact: true }))
      .toBeVisible();
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
      .element(block(screen, "Graph").getByText("Updating (rescanning)"))
      .toBeVisible();
  });

  it("without a graph still shows the workspace and the server", async () => {
    worker.use(...handlersFor("no-graph"));
    const screen = await renderStart();

    await expect
      .element(block(screen, "Graph").getByText("No graph loaded"))
      .toBeVisible();
    await expect
      .element(
        block(screen, "Workspace").getByRole("heading", {
          name: "fixture-app",
        }),
      )
      .toBeVisible();
    await expect
      .element(block(screen, "Server").getByText("0.0.0-fixture"))
      .toBeVisible();
  });

  it("shows the server's version, commit, start time and URLs", async () => {
    const screen = await renderStart();
    const server = block(screen, "Server");

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
        block(screen, "Server").getByText("0000000 (uncommitted changes)"),
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
    await expect.element(views.getByText(/2 groups/)).toBeVisible();
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

  it("sets the hierarchy view when one is chosen, and drops it again", async () => {
    const screen = await renderStart();
    const views = block(screen, "Hierarchy views");

    await views.getByRole("link", { name: "layers" }).click();

    await expect.element(views.getByText("current")).toBeVisible();
    expect(screen.router.state.location.search).toEqual({ view: "layers" });
    await expect
      .element(screen.getByRole("banner").getByText("layers"))
      .toBeVisible();

    await views
      .getByRole("link", { name: "Show without a hierarchy view" })
      .click();

    await expect.element(views.getByText("current")).not.toBeInTheDocument();
    expect(screen.router.state.location.search).not.toHaveProperty("view");
  });

  it("says when the server can't be reached", async () => {
    worker.use(graphql.query("StartScreen", () => HttpResponse.error()));
    const screen = await renderStart();

    await expect
      .element(screen.getByText("The Hierograph server can't be reached"))
      .toBeVisible();
  });
});
