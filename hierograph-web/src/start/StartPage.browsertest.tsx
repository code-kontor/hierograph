import { describe, expect, it } from "vitest";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
import dependencyEnds from "@/testing/fixtures/DependencyEnds.json";
import { renderWithRouter } from "@/testing/render";

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

describe("StartPage", () => {
  it("shows the hierarchy tree and the dependency tabs, incoming first", async () => {
    const screen = await renderStart();

    await expect.element(screen.getByRole("tree")).toBeVisible();
    const names = screen
      .getByRole("tab")
      .elements()
      .map((element) => element.textContent);
    expect(names).toEqual(["Incoming dependencies", "Outgoing dependencies"]);
    await expect
      .element(screen.getByRole("tab", { name: "Incoming dependencies" }))
      .toHaveAttribute("aria-selected", "true");
    // Nothing selected yet: says what to do.
    await expect
      .element(
        screen.getByText(
          "Select a node in the hierarchy to see what depends on it.",
        ),
      )
      .toBeVisible();
  });

  it("puts the chosen dependency tab into the URL, replacing the history entry", async () => {
    const lib = dependencyEnds.entries[0].variables.id;
    const screen = await renderStart(`?selected_id=${lib}`);
    await expect.element(screen.getByRole("tree").first()).toBeVisible();
    const length = screen.router.history.length;

    await screen.getByRole("tab", { name: "Outgoing dependencies" }).click();

    await expect
      .poll(() => screen.router.state.location.search)
      .toEqual({ selected_id: Number(lib), dependencies: "outgoing" });
    expect(screen.router.history.length).toBe(length);
    await expect
      .element(
        screen
          .getByRole("tabpanel", { name: "Outgoing dependencies" })
          .getByText(/depends on nothing outside itself/),
      )
      .toBeVisible();

    // The first tab is the default and leaves no parameter behind.
    await screen.getByRole("tab", { name: "Incoming dependencies" }).click();

    await expect
      .poll(() => screen.router.state.location.search)
      .toEqual({ selected_id: Number(lib) });
  });

  it("opens the dependency tab the URL names", async () => {
    const screen = await renderStart("?dependencies=outgoing");

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
});
