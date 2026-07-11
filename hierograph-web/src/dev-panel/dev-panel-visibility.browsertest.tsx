import { useState } from "react";
import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { DevPanel } from "@/dev-panel/DevPanel";
import { DevPanelProvider, useDevPanel } from "@/dev-panel/DevPanelContext";
import { FocusBridgeProvider } from "@/selection/FocusBridge";
import { SelectionProvider } from "@/selection/SelectionContext";
import { renderWithQueryClient } from "@/testing/render";

beforeEach(() => {
  // Reset panel position/collapse so the floating panel doesn't overlap
  // the viewport and block clicks.
  localStorage.clear();
});

// Reopens the panel from outside it — mirrors the top-level navbar button.
function ReopenButton() {
  const { setOpen } = useDevPanel();
  return <button onClick={() => setOpen(true)}>reopen-widget</button>;
}

// Mounts/unmounts the panel without touching the provider above it — mirrors a
// route switch, where the outlet content (and the panel) remounts while the
// root-level DevPanelProvider stays alive.
function RemountHarness() {
  const [mounted, setMounted] = useState(true);
  return (
    <>
      <button onClick={() => setMounted((m) => !m)}>toggle-mount</button>
      {mounted && <DevPanel />}
    </>
  );
}

it("keeps the active tab across a remount (route switch)", async () => {
  await renderWithQueryClient(
    <DevPanelProvider>
      <FocusBridgeProvider>
        <SelectionProvider>
          <RemountHarness />
        </SelectionProvider>
      </FocusBridgeProvider>
    </DevPanelProvider>,
  );

  await userEvent.click(page.getByRole("tab", { name: "Queries" }));
  await expect
    .element(page.getByRole("tab", { name: "Queries" }))
    .toHaveAttribute("data-state", "active");

  // Remount the panel; the tab must not snap back to Details.
  await userEvent.click(page.getByText("toggle-mount"));
  await expect.element(page.getByLabelText("DevPanel")).not.toBeInTheDocument();
  await userEvent.click(page.getByText("toggle-mount"));

  await expect
    .element(page.getByRole("tab", { name: "Queries" }))
    .toHaveAttribute("data-state", "active");
});

it("reopens from an external trigger after being closed", async () => {
  await renderWithQueryClient(
    <DevPanelProvider>
      <FocusBridgeProvider>
        <SelectionProvider>
          <ReopenButton />
          <DevPanel />
        </SelectionProvider>
      </FocusBridgeProvider>
    </DevPanelProvider>,
  );

  await expect.element(page.getByLabelText("DevPanel")).toBeVisible();

  await userEvent.click(page.getByRole("button", { name: "Close" }));
  await expect.element(page.getByLabelText("DevPanel")).not.toBeInTheDocument();

  await userEvent.click(page.getByText("reopen-widget"));
  await expect.element(page.getByLabelText("DevPanel")).toBeVisible();
});
