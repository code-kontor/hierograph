import { describe, expect, it, vi } from "vitest";
import { page, userEvent } from "vitest/browser";

import { renderWithQueryClient } from "@/testing/render";

import { AsyncTree, type TreeNodeData } from "./AsyncTree";

const TREE_SETTINGS = {
  showIndentGuides: true,
  autoExpandSingleChildren: false,
  preserveSelectionOnCollapse: false,
  labelFormat: "full",
} as const;

// A root with one folder `p` (children p.a, p.b, p.c, all leaves) and one
// leaf sibling `q` — enough shape to exercise click, modifier-click, range
// selection, hotkeys, and collapse-prune without any single-child chaining.
const ROOT: TreeNodeData = {
  id: "root",
  text: "",
  type: "Unknown",
  hasChildren: true,
};

const TREE = new Map<string, TreeNodeData[]>([
  [
    "root",
    [
      { id: "p", text: "p", type: "Package", hasChildren: true },
      { id: "q", text: "q", type: "Class", hasChildren: false },
    ],
  ],
  [
    "p",
    [
      { id: "p.a", text: "p.a", type: "Class", hasChildren: false },
      { id: "p.b", text: "p.b", type: "Class", hasChildren: false },
      { id: "p.c", text: "p.c", type: "Class", hasChildren: false },
    ],
  ],
]);

function makeLoadChildren() {
  // Simulate real network latency so async loads are actually exercised.
  return vi.fn(async (id: string) => {
    await new Promise((r) => setTimeout(r, 30));
    return TREE.get(id) ?? [];
  });
}

async function renderTree(
  onSelectedIdsChange: (ids: string[]) => void,
  onFocusedIdChange?: (
    id: string | null,
    name: string | null,
    type: string | null,
  ) => void,
) {
  await renderWithQueryClient(
    <AsyncTree
      rootNode={ROOT}
      loadChildren={makeLoadChildren()}
      onSelectedIdsChange={onSelectedIdsChange}
      onFocusedIdChange={onFocusedIdChange}
      label="selection"
      settings={TREE_SETTINGS}
    />,
  );
  await expect
    .element(page.getByText("p", { exact: true }))
    .toBeInTheDocument();
  await expect
    .element(page.getByText("q", { exact: true }))
    .toBeInTheDocument();
}

async function expandP() {
  await userEvent.click(page.getByText("p", { exact: true }));
  await userEvent.keyboard("{ArrowRight}");
  await expect
    .element(page.getByText("p.a", { exact: true }))
    .toBeInTheDocument();
  await expect
    .element(page.getByText("p.c", { exact: true }))
    .toBeInTheDocument();
}

describe("AsyncTree selection and focus notifications", () => {
  it("plain click selects and focuses exactly the clicked row", async () => {
    const onSelectedIdsChange = vi.fn();
    const onFocusedIdChange = vi.fn();
    await renderTree(onSelectedIdsChange, onFocusedIdChange);

    await userEvent.click(page.getByText("q", { exact: true }));

    expect(onSelectedIdsChange.mock.lastCall?.[0]).toEqual(["q"]);
    expect(onFocusedIdChange.mock.lastCall).toEqual(["q", "q", "Class"]);
  });

  it("ctrl/meta-click adds to the selection, a second ctrl-click toggles it off", async () => {
    const onSelectedIdsChange = vi.fn();
    await renderTree(onSelectedIdsChange);

    await userEvent.click(page.getByText("q", { exact: true }));
    await userEvent.keyboard("{Control>}");
    await userEvent.click(page.getByText("p", { exact: true }));
    await userEvent.keyboard("{/Control}");

    expect(onSelectedIdsChange.mock.lastCall?.[0]).toEqual(["q", "p"]);

    await userEvent.keyboard("{Control>}");
    await userEvent.click(page.getByText("p", { exact: true }));
    await userEvent.keyboard("{/Control}");

    expect(onSelectedIdsChange.mock.lastCall?.[0]).toEqual(["q"]);
  });

  it("first shift-click sets the anchor and selects only that row; second shift-click selects the range", async () => {
    const onSelectedIdsChange = vi.fn();
    await renderTree(onSelectedIdsChange);
    await expandP();

    await userEvent.keyboard("{Shift>}");
    await userEvent.click(page.getByText("p.a", { exact: true }));
    await userEvent.keyboard("{/Shift}");

    expect(onSelectedIdsChange.mock.lastCall?.[0]).toEqual(["p.a"]);

    await userEvent.keyboard("{Shift>}");
    await userEvent.click(page.getByText("p.c", { exact: true }));
    await userEvent.keyboard("{/Shift}");

    expect(onSelectedIdsChange.mock.lastCall?.[0]).toEqual([
      "p.a",
      "p.b",
      "p.c",
    ]);
  });

  it("Shift+ArrowDown extends the selection, Control+Space toggles the focused row", async () => {
    const onSelectedIdsChange = vi.fn();
    await renderTree(onSelectedIdsChange);

    await userEvent.click(page.getByText("p", { exact: true }));
    await userEvent.keyboard("{Shift>}{ArrowDown}{/Shift}");

    expect(onSelectedIdsChange.mock.lastCall?.[0]).toEqual(["p", "q"]);

    await userEvent.keyboard("{Control>} {/Control}");

    expect(onSelectedIdsChange.mock.lastCall?.[0]).toEqual(["p"]);
  });

  it("collapsing a folder prunes a selected descendant from the selection", async () => {
    const onSelectedIdsChange = vi.fn();
    await renderTree(onSelectedIdsChange);
    await expandP();

    await userEvent.click(page.getByText("p.a", { exact: true }));
    expect(onSelectedIdsChange.mock.lastCall?.[0]).toEqual(["p.a"]);

    // Move focus back up to the (still expanded) parent without touching
    // the selection, then collapse it.
    await userEvent.keyboard("{ArrowLeft}");
    await userEvent.keyboard("{ArrowLeft}");

    expect(onSelectedIdsChange.mock.lastCall?.[0]).toEqual([]);
  });

  it("does not notify on mount, before any interaction", async () => {
    const onSelectedIdsChange = vi.fn();
    const onFocusedIdChange = vi.fn();
    await renderTree(onSelectedIdsChange, onFocusedIdChange);

    expect(onSelectedIdsChange).not.toHaveBeenCalled();
    expect(onFocusedIdChange).not.toHaveBeenCalled();
  });

  it("collapsing a folder without selected descendants does not notify", async () => {
    const onSelectedIdsChange = vi.fn();
    await renderTree(onSelectedIdsChange);
    await expandP();

    // Select and focus `p` itself (not a descendant), then collapse it —
    // nothing under `p` is selected, so the choke point must not fire.
    await userEvent.click(page.getByText("p", { exact: true }));
    const callsBeforeCollapse = onSelectedIdsChange.mock.calls.length;

    await userEvent.keyboard("{ArrowLeft}");

    expect(onSelectedIdsChange.mock.calls.length).toBe(callsBeforeCollapse);
  });
});
