import { describe, expect, it } from "vitest";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
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
  it("shows the hierarchy tree, the server's state only in the header", async () => {
    const screen = await renderStart();

    await expect.element(screen.getByRole("tree")).toBeVisible();
    await expect.element(screen.getByRole("tablist")).not.toBeInTheDocument();
  });

  it("drops the tree's state when another hierarchy view is chosen", async () => {
    const screen = await renderStart("?expanded_ids=1,2&selected_id=2");
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
