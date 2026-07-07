import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { NodeDetailsWidget } from "@/dependency-details/NodeDetailsWidget";
import { SelectionProvider, useSelection } from "@/selection/SelectionContext";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

const NODE_ID = resolveNodeId("org.hg.fixture.basic.rel.source");

// Mirrors how HierarchyTree sets focusedId — fires the NodeDetail query,
// which populates the dev query log.
function SetFocusButton() {
  const { setFocusedId } = useSelection();
  return <button onClick={() => setFocusedId(NODE_ID)}>set-focus</button>;
}

beforeEach(() => {
  // Reset widget position/collapse so the floating widget doesn't overlap
  // the viewport and block clicks.
  localStorage.clear();
});

it("Queries tab lists the recorded NodeDetail query with a working GraphiQL deep link", async () => {
  await renderWithQueryClient(
    <SelectionProvider>
      <SetFocusButton />
      <NodeDetailsWidget />
    </SelectionProvider>,
  );

  await userEvent.click(page.getByText("set-focus"));
  // Wait for the Details tab to have loaded data before switching tabs.
  await expect.element(page.getByText("java.package")).toBeVisible();

  await userEvent.click(page.getByRole("tab", { name: "Queries" }));

  // Both the operation name and the trigger badge fall back to "NodeDetail"
  // (no entry in the trigger map) — assert at least one occurrence renders.
  await expect
    .element(page.getByText("NodeDetail", { exact: true }).first())
    .toBeVisible();

  const link = page.getByRole("link", { name: "Open in GraphiQL" });
  await expect.element(link).toHaveAttribute("target", "_blank");
  await expect.element(link).toHaveAttribute("rel", "noreferrer");

  const href = link.element().getAttribute("href");
  expect(href).toBeTruthy();
  const url = new URL(href ?? "", location.href);
  expect(url.pathname).toBe("/graphiql.html");
  expect(url.searchParams.get("query")).toContain("NodeDetail");
  expect(url.searchParams.get("variables")).toContain(NODE_ID);
});

it("Details tab stays the default and shows the previous content", async () => {
  await renderWithQueryClient(
    <SelectionProvider>
      <SetFocusButton />
      <NodeDetailsWidget />
    </SelectionProvider>,
  );

  await userEvent.click(page.getByText("set-focus"));

  await expect
    .element(page.getByRole("tab", { name: "Details" }))
    .toHaveAttribute("data-state", "active");
  await expect.element(page.getByText("java.package")).toBeVisible();
});
