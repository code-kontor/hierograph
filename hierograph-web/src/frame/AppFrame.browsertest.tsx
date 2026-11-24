import { useQuery } from "@tanstack/react-query";
import { graphql, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { AppFrame } from "@/frame/AppFrame";
import { graphQueryKey, useGraphScope } from "@/frame/GraphScope";
import { CHOOSE_HINT } from "@/frame/HierarchyViewMenu";
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
    ["/other"],
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

  it("keeps the browser's context menu closed, except in text fields", async () => {
    const screen = await renderFrame();
    const link = screen.getByRole("link", { name: "Start" });
    await expect.element(link).toBeVisible();
    const field = document.body.appendChild(document.createElement("input"));
    // Whether the browser would open its own menu on a right click.
    const opensNative = (target: Element) =>
      target.dispatchEvent(
        new MouseEvent("contextmenu", { bubbles: true, cancelable: true }),
      );

    try {
      expect(opensNative(link.element())).toBe(false);
      expect(opensNative(field)).toBe(true);
    } finally {
      field.remove();
    }
  });

  it("names the project the server serves, with its path on hover", async () => {
    const screen = await renderFrame();

    const brand = screen.getByRole("banner").getByRole("link").first();
    await expect.element(brand).toHaveTextContent("hierograph – fixture-app");
    await expect
      .element(brand)
      .toHaveAttribute("title", "hierograph: /fixture-app");
  });

  it('shows "hierograph" while the server can\'t be reached', async () => {
    worker.use(graphql.query("GraphStatus", () => HttpResponse.error()));
    const screen = await renderFrame();

    await expect
      .element(screen.getByRole("banner").getByRole("link").first())
      .toHaveTextContent(/^hierograph$/);
  });

  it("shows no navigation for a single screen", async () => {
    const screen = await renderFrame(false, "", false);

    await expect.element(screen.getByText("Graph loaded")).toBeVisible();
    await expect
      .element(screen.getByRole("navigation"))
      .not.toBeInTheDocument();
    await expect
      .element(screen.getByRole("link", { name: "hierograph – fixture-app" }))
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

  it("accepts a hierarchy view defined after the page was loaded, without a reload", async () => {
    const screen = await renderFrame(false, "?view=added");
    await expect
      .element(screen.getByText('There is no hierarchy view "added"'))
      .toBeVisible();

    // The agent defines the view: the views' version changes, and the list
    // the frame checks `view` against has it.
    const status = structuredClone(fixtureData("GraphStatus", "default")) as {
      server: { viewsVersion: string };
    };
    status.server.viewsVersion = "after-define";
    const views = structuredClone(fixtureData("HierarchyViews", "default")) as {
      views: { name: string; valid: boolean; problems: string[] }[];
    };
    views.views.push({ name: "added", valid: true, problems: [] });
    worker.use(
      graphql.query("GraphStatus", () => HttpResponse.json({ data: status })),
      graphql.query("HierarchyViews", () => HttpResponse.json({ data: views })),
    );
    await screen.queryClient.refetchQueries({
      queryKey: graphStatusQueryOptions().queryKey,
    });

    await expect
      .element(screen.getByText("scope 1 added; fetched 1"))
      .toBeVisible();
  });

  it("refetches graph queries in a view when a view document changes", async () => {
    const screen = await renderFrame(false, "?view=layers");
    await expect
      .element(screen.getByText("scope 1 layers; fetched 1"))
      .toBeVisible();

    const status = structuredClone(fixtureData("GraphStatus", "default")) as {
      server: { viewsVersion: string };
    };
    status.server.viewsVersion = "after-redefine";
    worker.use(
      graphql.query("GraphStatus", () => HttpResponse.json({ data: status })),
    );
    await screen.queryClient.refetchQueries({
      queryKey: graphStatusQueryOptions().queryKey,
    });

    await expect
      .element(screen.getByText("scope 1 layers; fetched 2"))
      .toBeVisible();
  });

  it("brings a screen back as it was left, per hierarchy view", async () => {
    const screen = await renderFrame(false, "?view=layers");
    const go = async (name: string) => {
      await screen.getByRole("link", { name }).click();
      await expect.element(screen.getByText(/scope/)).toBeVisible();
    };

    await go("Other");
    await screen.router.navigate({
      to: "/other",
      search: { view: "layers", expanded_ids: ["7"] },
    } as never);
    await go("Start");
    await go("Other");
    expect(screen.router.state.location.search).toEqual({
      view: "layers",
      expanded_ids: ["7"],
    });

    // In another view the screen starts afresh.
    await screen.router.navigate({ to: "/", search: {} } as never);
    await go("Other");
    expect(screen.router.state.location.search).toEqual({});
  });

  it("lists the hierarchy views in the header's menu, the scanned hierarchy first", async () => {
    const screen = await renderFrame();
    const trigger = screen.getByRole("button", {
      name: "Hierarchy view: default",
    });
    await expect.element(trigger).toBeVisible();

    await trigger.click();

    const menu = screen.getByRole("menu");
    await expect.element(menu.getByText(CHOOSE_HINT)).toBeVisible();
    const items = menu.getByRole("menuitemradio");
    expect(items.elements().map((item) => item.textContent)).toEqual([
      expect.stringMatching(/^default/),
      expect.stringMatching(/^layers/),
      expect.stringMatching(/^locations/),
    ]);
    // Under their sources' labels; a source without views has none.
    await expect
      .element(menu.getByText("Built-in", { exact: true }))
      .toBeVisible();
    await expect
      .element(menu.getByText("Project views", { exact: true }))
      .toBeVisible();
    await expect
      .element(menu.getByText("Runtime views", { exact: true }))
      .not.toBeInTheDocument();
    // Current without a view; every row with the same fields: its groups and
    // what it excludes, by the kind of the outermost excluded nodes.
    await expect
      .element(menu.getByRole("menuitemradio", { name: /^default/ }))
      .toHaveAttribute("aria-checked", "true");
    await expect
      .element(menu.getByText("no groups · nothing excluded", { exact: true }))
      .toBeVisible();
    await expect
      .element(
        menu.getByText("The hierarchy as scanned from the build: 1 module."),
      )
      .toBeVisible();
    await expect
      .element(menu.getByText(/application on top of the library/))
      .toBeVisible();
    await expect
      .element(menu.getByText("2 groups · nothing excluded", { exact: true }))
      .toBeVisible();
    await expect
      .element(
        menu.getByText("no groups · excludes 1 package", { exact: true }),
      )
      .toBeVisible();
    // An invalid view is listed with its problems, but can't be chosen.
    await expect
      .element(menu.getByRole("menuitemradio", { name: /^broken/ }))
      .not.toBeInTheDocument();
    await expect.element(menu.getByText("broken")).toBeVisible();
    await expect
      .element(menu.getByText("invalid", { exact: true }))
      .toBeVisible();
    await expect
      .element(menu.getByText(/Invalid JSON at line 5/))
      .toBeVisible();
  });

  it("sets the hierarchy view chosen in the menu, and drops it with the default", async () => {
    const screen = await renderFrame();
    // Without the stylesheet the menu is as wide as its longest line, the
    // middle of an item off the page: clicked near its start.
    const choose = async (name: RegExp) => {
      await screen.getByRole("button", { name: /^Hierarchy view/ }).click();
      await screen
        .getByRole("menuitemradio", { name })
        .click({ position: { x: 8, y: 8 } });
    };

    await choose(/^layers/);

    await expect.element(screen.getByText(/scope 1 layers/)).toBeVisible();
    expect(screen.router.state.location.search).toEqual({ view: "layers" });
    await expect
      .element(screen.getByRole("button", { name: "Hierarchy view: layers" }))
      .toBeVisible();

    await choose(/^default/);

    await expect.element(screen.getByText("scope 1 -;")).toBeVisible();
    expect(screen.router.state.location.search).not.toHaveProperty("view");
  });

  it("brings a screen back as it was left in the view chosen in the menu", async () => {
    const screen = await renderFrame(false, "?view=layers");
    await expect.element(screen.getByText(/scope 1 layers/)).toBeVisible();
    await screen.router.navigate({
      to: "/",
      search: { view: "layers", expanded_ids: ["7"] },
    } as never);
    const choose = async (name: RegExp) => {
      await screen.getByRole("button", { name: /^Hierarchy view/ }).click();
      await screen
        .getByRole("menuitemradio", { name })
        .click({ position: { x: 8, y: 8 } });
    };

    await choose(/^locations/);
    await expect
      .poll(() => screen.router.state.location.search)
      .toEqual({ view: "locations" });
    await choose(/^layers/);

    await expect
      .poll(() => screen.router.state.location.search)
      .toEqual({
        view: "layers",
        expanded_ids: ["7"],
      });
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
