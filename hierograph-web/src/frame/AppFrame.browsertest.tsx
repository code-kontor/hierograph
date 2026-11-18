import { useQuery } from "@tanstack/react-query";
import { graphql, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { AppFrame } from "@/frame/AppFrame";
import { graphQueryKey, useGraphScope } from "@/frame/GraphScope";
import {
  GRAPH_STATUS_POLL_INTERVAL_MS,
  graphStatusQueryOptions,
} from "@/frame/queries";
import type { Screen } from "@/frame/screens";
import { fixtureData, handlersFor } from "@/testing/msw/handlers";
import { worker } from "@/testing/msw/worker";
import { renderWithRouter } from "@/testing/render";

let fetches = 0;

// Stands in for a screen: shows the graph scope and how often a graph query
// keyed by it was fetched.
function Probe() {
  const scope = useGraphScope();
  const query = useQuery({
    queryKey: scope ? graphQueryKey("probe", scope) : ["probe", "none"],
    queryFn: () => ++fetches,
    enabled: scope !== undefined,
    staleTime: Infinity,
  });
  const label = scope
    ? `scope ${scope.generation} ${scope.view ?? "-"}`
    : "no scope";
  return (
    <p>
      {label}; fetched {query.data ?? 0}
    </p>
  );
}

function screens(needsGraph: boolean, more = true) {
  const start = { path: "/", label: "Start", needsGraph };
  const other = { path: "/other", label: "Other", needsGraph: true };
  return (more ? [start, other] : [start]) as unknown as Screen[];
}

function renderFrame(needsGraph = false, search = "", more = true) {
  fetches = 0;
  return renderWithRouter(
    <AppFrame screens={screens(needsGraph, more)}>
      <Probe />
    </AppFrame>,
    "/",
    search,
  );
}

describe("AppFrame", () => {
  it("shows the navigation, the loaded graph and the screen", async () => {
    const screen = await renderFrame();

    await expect
      .element(screen.getByRole("link", { name: "Start" }))
      .toBeVisible();
    await expect.element(screen.getByText("Graph loaded")).toBeVisible();
    await expect
      .element(screen.getByText("scope 1 -; fetched 1"))
      .toBeVisible();
  });

  it("shows no navigation for a single screen", async () => {
    const screen = await renderFrame(false, "", false);

    await expect.element(screen.getByText("Graph loaded")).toBeVisible();
    await expect
      .element(screen.getByRole("navigation"))
      .not.toBeInTheDocument();
    await expect
      .element(screen.getByRole("link", { name: "hierograph" }))
      .toBeVisible();
  });

  it("shows the updater's phase while the graph is updated", async () => {
    worker.use(...handlersFor("updating"));
    const screen = await renderFrame();

    await expect
      .element(screen.getByText("Updating graph (rescanning)"))
      .toBeVisible();
    // The graph in service can still be queried meanwhile.
    await expect
      .element(screen.getByText("scope 1 -; fetched 1"))
      .toBeVisible();
  });

  it("without a graph shows a message instead of a screen that needs one", async () => {
    worker.use(...handlersFor("no-graph"));
    const screen = await renderFrame(true);

    await expect.element(screen.getByText("No graph loaded")).toBeVisible();
    await expect.element(screen.getByText("No graph is loaded")).toBeVisible();
    await expect.element(screen.getByText(/scope/)).not.toBeInTheDocument();
  });

  it("without a graph still shows a screen that doesn't need one", async () => {
    worker.use(...handlersFor("no-graph"));
    const screen = await renderFrame(false);

    await expect.element(screen.getByText("No graph loaded")).toBeVisible();
    await expect.element(screen.getByText("no scope; fetched 0")).toBeVisible();
  });

  it("says when the server can't be reached", async () => {
    worker.use(graphql.query("GraphStatus", () => HttpResponse.error()));
    const screen = await renderFrame(true);

    await expect
      .element(screen.getByText("Server not reachable"))
      .toBeVisible();
    await expect
      .element(screen.getByText("The Hierograph server can't be reached"))
      .toBeVisible();
  });

  it("refetches graph queries when the generation changes", async () => {
    const screen = await renderFrame();
    await expect
      .element(screen.getByText("scope 1 -; fetched 1"))
      .toBeVisible();

    const reloaded = structuredClone(fixtureData("GraphStatus", "default")) as {
      server: { graph: { generation: string } };
    };
    reloaded.server.graph.generation = "2";
    worker.use(
      graphql.query("GraphStatus", () => HttpResponse.json({ data: reloaded })),
    );
    await screen.queryClient.refetchQueries({
      queryKey: graphStatusQueryOptions().queryKey,
    });

    await expect
      .element(screen.getByText("scope 2 -; fetched 2"))
      .toBeVisible();
  });

  it("answers in a valid hierarchy view from the URL", async () => {
    const screen = await renderFrame(false, "?view=layers");

    await expect.element(screen.getByText("Hierarchy view")).toBeVisible();
    await expect.element(screen.getByText("layers")).toBeVisible();
    await expect
      .element(screen.getByText("scope 1 layers; fetched 1"))
      .toBeVisible();
  });

  it("names an unknown hierarchy view and offers to drop it", async () => {
    const screen = await renderFrame(false, "?view=nope");

    await expect
      .element(screen.getByText('There is no hierarchy view "nope"'))
      .toBeVisible();
    await expect.element(screen.getByText(/scope/)).not.toBeInTheDocument();

    await screen
      .getByRole("button", { name: "Show without a hierarchy view" })
      .click();

    await expect
      .element(screen.getByText("scope 1 -; fetched 1"))
      .toBeVisible();
    expect(screen.router.state.location.search).not.toHaveProperty("view");
  });

  it("shows why a hierarchy view is invalid", async () => {
    const screen = await renderFrame(false, "?view=broken");

    await expect
      .element(screen.getByText('The hierarchy view "broken" is invalid'))
      .toBeVisible();
    await expect
      .element(screen.getByText(/Invalid JSON at line 5/))
      .toBeVisible();
  });

  it("polls the graph status every 5 s, only while the page is visible", () => {
    const options = graphStatusQueryOptions();

    expect(GRAPH_STATUS_POLL_INTERVAL_MS).toBe(5000);
    expect(options.refetchInterval).toBe(5000);
    expect(options.refetchIntervalInBackground).toBe(false);
    expect(options.refetchOnWindowFocus).toBe(true);
    expect(options.staleTime).toBe(0);
  });
});
