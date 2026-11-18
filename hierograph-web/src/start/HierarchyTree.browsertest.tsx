import { describe, expect, it } from "vitest";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
import { handlersFor } from "@/testing/msw/handlers";
import { worker } from "@/testing/msw/worker";
import { renderWithRouter } from "@/testing/render";

import { HierarchyTree } from "./HierarchyTree";

const SCREENS = [
  { path: "/", label: "Start", needsGraph: false },
] as unknown as Screen[];

// The tree inside the frame, which provides the graph scope.
function renderTree(search = "") {
  return renderWithRouter(
    <AppFrame screens={SCREENS}>
      <HierarchyTree />
    </AppFrame>,
    "/",
    search,
  );
}

type TreeRender = Awaited<ReturnType<typeof renderTree>>;

function item(screen: TreeRender, name: string) {
  return screen.getByRole("treeitem", { name, exact: true });
}

// Expands the items along `path`, each found by its label.
async function expand(screen: TreeRender, ...path: string[]) {
  for (const name of path) {
    await item(screen, name).getByRole("button").first().click();
    await expect
      .element(item(screen, name))
      .toHaveAttribute("aria-expanded", "true");
  }
}

describe("HierarchyTree", () => {
  it("shows the top level of the scanned hierarchy, collapsed", async () => {
    const screen = await renderTree();

    await expect.element(screen.getByRole("tree")).toBeVisible();
    await expect
      .element(item(screen, "fixture-app"))
      .toHaveAttribute("aria-expanded", "false");
    // External Types last, below a separator, so the modules are counted
    // apart from it.
    await expect.element(item(screen, "External Types")).toBeVisible();
    const top = screen
      .getByRole("tree")
      .getByRole("treeitem")
      .elements()
      .map((element) => element.getAttribute("aria-label"));
    expect(top).toEqual(["fixture-app", "External Types"]);
    await expect
      .element(item(screen, "External Types"))
      .toHaveClass(/border-t/);
    // The full name on hover.
    await expect
      .element(item(screen, "fixture-app").getByRole("button").first())
      .toHaveAttribute("title", "io.hierograph.examples:fixture-app:1.0.0");
  });

  it("loads a node's children when it is expanded, named relative to it", async () => {
    const screen = await renderTree();

    await expand(
      screen,
      "fixture-app",
      "org",
      "hg",
      "fixture",
      "basic",
      "core",
    );

    await expect.element(item(screen, "PlainClass")).toBeVisible();
    await expect.element(item(screen, "Pair")).toBeVisible();
  });

  it("collapses an expanded node", async () => {
    const screen = await renderTree();
    await expand(screen, "fixture-app");
    await expect.element(item(screen, "org")).toBeVisible();

    await item(screen, "fixture-app").getByRole("button").first().click();

    await expect
      .element(item(screen, "fixture-app"))
      .toHaveAttribute("aria-expanded", "false");
    await expect.element(item(screen, "org")).not.toBeInTheDocument();
  });

  it("shows a hierarchy view's groups, with their members named relative to the level above", async () => {
    const screen = await renderTree("?view=layers");

    await expect
      .element(screen.getByRole("banner").getByText("layers"))
      .toBeVisible();
    await expand(screen, "fixture-app", "org", "hg", "fixture", "locations");

    await expect.element(item(screen, "application")).toBeVisible();
    await expect.element(item(screen, "library")).toBeVisible();

    await expand(screen, "application");

    await expect.element(item(screen, "app")).toBeVisible();
  });

  it("says when there is no graph", async () => {
    worker.use(...handlersFor("no-graph"));
    const screen = await renderTree();

    await expect.element(screen.getByText(/No graph is loaded/)).toBeVisible();
    await expect.element(screen.getByRole("tree")).not.toBeInTheDocument();
  });
});
