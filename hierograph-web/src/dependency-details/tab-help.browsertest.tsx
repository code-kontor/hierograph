import { describe, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { SelectionProvider } from "@/selection/SelectionContext";
import { renderWithQueryClient } from "@/testing/render";

// The help button lives in the always-rendered titleBar, so no cell selection
// (and therefore no DependencyEdges stub) is needed. Radix Popover portals its
// content and mounts/unmounts it on open/close.
async function renderPane() {
  await renderWithQueryClient(
    <SelectionProvider>
      <DependencyDetailsPane />
    </SelectionProvider>,
  );
}

describe("tab help overlay", () => {
  it("opens and closes the Usages help popover", async () => {
    await renderPane();

    // Default tab is "usages" → the Usages help button is present.
    const trigger = page.getByRole("button", { name: "About the Usages tab" });
    await userEvent.click(trigger);
    await expect
      .element(
        page.getByText("Usages lists the concrete references", {
          exact: false,
        }),
      )
      .toBeVisible();

    // Esc closes; Radix unmounts the portalled content.
    await userEvent.keyboard("{Escape}");
    await expect
      .element(
        page.getByText("Usages lists the concrete references", {
          exact: false,
        }),
      )
      .not.toBeInTheDocument();
  });

  it("shows the Paths help on the Paths tab", async () => {
    await renderPane();

    await userEvent.click(page.getByRole("tab", { name: "Paths" }));
    const trigger = page.getByRole("button", { name: "About the Paths tab" });
    await userEvent.click(trigger);
    await expect
      .element(page.getByText("Paths lets you click a type", { exact: false }))
      .toBeVisible();
  });
});
