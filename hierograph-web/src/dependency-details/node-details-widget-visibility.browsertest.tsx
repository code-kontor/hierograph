import { useState } from "react";
import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { NodeDetailsWidget } from "@/dependency-details/NodeDetailsWidget";
import {
  NodeDetailsWidgetProvider,
  SelectionProvider,
  useNodeDetailsWidget,
} from "@/selection/SelectionContext";
import { renderWithQueryClient } from "@/testing/render";

beforeEach(() => {
  // Reset widget position/collapse so the floating widget doesn't overlap
  // the viewport and block clicks.
  localStorage.clear();
});

// Reopens the widget from outside it — mirrors the top-level navbar button.
function ReopenButton() {
  const { setOpen } = useNodeDetailsWidget();
  return <button onClick={() => setOpen(true)}>reopen-widget</button>;
}

// Mounts/unmounts the widget without touching the provider above it — mirrors a
// route switch, where the outlet content (and the widget) remounts while the
// root-level NodeDetailsWidgetProvider stays alive.
function RemountHarness() {
  const [mounted, setMounted] = useState(true);
  return (
    <>
      <button onClick={() => setMounted((m) => !m)}>toggle-mount</button>
      {mounted && <NodeDetailsWidget />}
    </>
  );
}

it("keeps the active tab across a remount (route switch)", async () => {
  await renderWithQueryClient(
    <NodeDetailsWidgetProvider>
      <SelectionProvider>
        <RemountHarness />
      </SelectionProvider>
    </NodeDetailsWidgetProvider>,
  );

  await userEvent.click(page.getByRole("tab", { name: "Queries" }));
  await expect
    .element(page.getByRole("tab", { name: "Queries" }))
    .toHaveAttribute("data-state", "active");

  // Remount the widget; the tab must not snap back to Details.
  await userEvent.click(page.getByText("toggle-mount"));
  await expect
    .element(page.getByLabelText("NodeDetailsWidget"))
    .not.toBeInTheDocument();
  await userEvent.click(page.getByText("toggle-mount"));

  await expect
    .element(page.getByRole("tab", { name: "Queries" }))
    .toHaveAttribute("data-state", "active");
});

it("reopens from an external trigger after being closed", async () => {
  await renderWithQueryClient(
    <NodeDetailsWidgetProvider>
      <SelectionProvider>
        <ReopenButton />
        <NodeDetailsWidget />
      </SelectionProvider>
    </NodeDetailsWidgetProvider>,
  );

  await expect.element(page.getByLabelText("NodeDetailsWidget")).toBeVisible();

  await userEvent.click(page.getByRole("button", { name: "Close" }));
  await expect
    .element(page.getByLabelText("NodeDetailsWidget"))
    .not.toBeInTheDocument();

  await userEvent.click(page.getByText("reopen-widget"));
  await expect.element(page.getByLabelText("NodeDetailsWidget")).toBeVisible();
});
