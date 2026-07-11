import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { DevPanel } from "@/dev-panel/DevPanel";
import { DevPanelProvider } from "@/dev-panel/DevPanelContext";
import { FocusBridgeProvider } from "@/selection/FocusBridge";
import { SelectionProvider, useSelection } from "@/selection/SelectionContext";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

const NODE_ID = resolveNodeId("org.hg.fixture.basic.rel.source");

// Mirrors a screen's page-level SelectionProvider writing focus via
// useSelection — the same call HierarchyTree makes.
function SetFocusButton() {
  const { setFocusedId, setFocusedName } = useSelection();
  return (
    <button
      onClick={() => {
        setFocusedId(NODE_ID);
        setFocusedName("rel.source");
      }}
    >
      set-focus
    </button>
  );
}

beforeEach(() => {
  localStorage.clear();
});

it("DevPanel reads the focus set inside a sibling, nested SelectionProvider via the FocusBridge (#0096 regression)", async () => {
  // Mirrors the real root topology: a single FocusBridgeProvider wraps both
  // the outlet subtree (here: a nested, page-level SelectionProvider) and the
  // DevPanel, which sits outside that SelectionProvider as a sibling.
  await renderWithQueryClient(
    <DevPanelProvider>
      <FocusBridgeProvider>
        <SelectionProvider>
          <SetFocusButton />
        </SelectionProvider>
        <DevPanel />
      </FocusBridgeProvider>
    </DevPanelProvider>,
  );

  await expect.element(page.getByLabelText("DevPanel")).toBeVisible();
  await userEvent.click(page.getByRole("tab", { name: "Node Details" }));

  await userEvent.click(page.getByText("set-focus"));

  await expect.element(page.getByText("java.package")).toBeVisible();
  await expect.element(page.getByText(NODE_ID, { exact: true })).toBeVisible();

  const link = page.getByRole("link", { name: "Open in GraphiQL" });
  const href = link.element().getAttribute("href");
  const url = new URL(href ?? "", location.href);
  expect(url.pathname).toBe("/graphiql.html");
  expect(url.searchParams.get("query")).toContain("NodeExplore");
  expect(url.searchParams.get("variables")).toContain(NODE_ID);
});
