import { describe, expect, it, onTestFinished } from "vitest";
import { page } from "vitest/browser";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
import dependencySet from "@/testing/fixtures/DependencySet.json";
import { renderWithRouter } from "@/testing/render";

import { StartPage } from "./StartPage";

const SCREENS = [
  { path: "/", label: "Dependencies", needsGraph: false },
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

describe("StartPage", () => {
  it("shows the hierarchy tree, the dependencies and their list", async () => {
    const screen = await renderStart();

    await expect.element(screen.getByRole("tree")).toBeVisible();
    // Nothing selected yet: says what to do.
    await expect
      .element(
        screen.getByText(
          "Select a node in the hierarchy to see its dependencies.",
        ),
      )
      .toBeVisible();
    await expect
      .element(
        screen.getByText(
          "Select a node in the hierarchy to list its dependencies.",
        ),
      )
      .toBeVisible();
  });

  it("drops the picks in the dependency trees when the selection changes", async () => {
    const lib = dependencySet.entries[0].variables.ids[0];
    const screen = await renderStart(
      `?selected_ids=${lib}&selection_part_id=${lib}`,
    );
    await expect.element(screen.getByRole("tree").first()).toBeVisible();

    await screen.getByRole("button", { name: "Clear selection" }).click();

    await expect.poll(() => screen.router.state.location.search).toEqual({});
  });

  it("opens the dependency list's tab the URL names", async () => {
    const screen = await renderStart("?dependency_list=outgoing");

    await expect
      .element(screen.getByRole("tab", { name: "Outgoing dependencies" }))
      .toHaveAttribute("aria-selected", "true");
  });

  it("drops the tree's state when another hierarchy view is chosen", async () => {
    const screen = await renderStart("?expanded_ids=1,2");
    await expect.element(screen.getByRole("tree")).toBeVisible();

    await screen.getByRole("button", { name: /^Hierarchy view/ }).click();
    // Clicked near its start: without the stylesheet the menu is wider than
    // the page.
    await screen
      .getByRole("menuitemradio", { name: /^layers/ })
      .click({ position: { x: 8, y: 8 } });

    await expect
      .poll(() => screen.router.state.location.search)
      .toEqual({ view: "layers" });
  });

  it("collapses the hierarchy to a rail on a wide screen, and brings it back", async () => {
    // Wide enough for the side-by-side layout; the split is remembered by
    // the browser, so start from none.
    localStorage.clear();
    await page.viewport(1400, 900);
    onTestFinished(async () => {
      localStorage.clear();
      await page.viewport(414, 896);
    });
    const lib = dependencySet.entries[0].variables.ids[0];
    const screen = await renderStart(`?selected_ids=${lib}`);
    await expect
      .element(screen.getByRole("tree", { name: "Hierarchy" }))
      .toBeVisible();

    await screen
      .getByRole("button", { name: "Collapse the hierarchy" })
      .click();

    await expect
      .element(screen.getByRole("tree", { name: "Hierarchy" }))
      .not.toBeInTheDocument();
    await expect
      .poll(() => screen.router.state.location.search)
      .toHaveProperty("hierarchy_collapsed", true);
    const rail = screen.getByRole("button", { name: "Expand the hierarchy" });
    // The rail names the selection the dependencies are of.
    await expect
      .element(rail.getByTestId("rail-selection"))
      .toHaveTextContent("org.hg.fixture.locations.lib");
    // The dependencies stay.
    await expect
      .element(screen.getByRole("region", { name: "Selection" }))
      .toBeVisible();

    await rail.click();

    await expect
      .element(screen.getByRole("tree", { name: "Hierarchy" }))
      .toBeVisible();
    await expect.element(rail).not.toBeInTheDocument();
    await expect
      .poll(() => screen.router.state.location.search)
      .not.toHaveProperty("hierarchy_collapsed");
  });

  it("opens with the hierarchy collapsed when the URL says so", async () => {
    localStorage.clear();
    await page.viewport(1400, 900);
    onTestFinished(async () => {
      localStorage.clear();
      await page.viewport(414, 896);
    });
    const screen = await renderStart("?hierarchy_collapsed=true");

    await expect
      .element(screen.getByRole("button", { name: "Expand the hierarchy" }))
      .toBeVisible();
    await expect
      .element(screen.getByRole("tree", { name: "Hierarchy" }))
      .not.toBeInTheDocument();
  });
});
