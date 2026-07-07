import { describe, expect, it, vi } from "vitest";
import { page } from "vitest/browser";

import { renderWithQueryClient } from "@/testing/render";

import { AsyncTree, type TreeNodeData } from "./AsyncTree";

const TREE_SETTINGS = {
  showIndentGuides: true,
  // Deliberately off: the root drill must run independently of this setting.
  autoExpandSingleChildren: false,
  preserveSelectionOnCollapse: false,
  labelFormat: "full",
} as const;

// A single-child chain that branches only at the last level, so the drill
// stops there: r -> a -> a.b -> a.b.c -> [a.b.c.x, a.b.c.y].
const SINGLE_CHILD_ROOT: TreeNodeData = {
  id: "r",
  text: "",
  type: "Unknown",
  hasChildren: true,
};

const SINGLE_CHILD_TREE = new Map<string, TreeNodeData[]>([
  ["r", [{ id: "a", text: "a", type: "Package", hasChildren: true }]],
  ["a", [{ id: "a.b", text: "a.b", type: "Package", hasChildren: true }]],
  ["a.b", [{ id: "a.b.c", text: "a.b.c", type: "Package", hasChildren: true }]],
  [
    "a.b.c",
    [
      { id: "a.b.c.x", text: "a.b.c.x", type: "Package", hasChildren: false },
      { id: "a.b.c.y", text: "a.b.c.y", type: "Package", hasChildren: false },
    ],
  ],
]);

// A root that branches immediately into two children: the drill must be a no-op.
const BRANCHING_ROOT: TreeNodeData = {
  id: "root",
  text: "",
  type: "Unknown",
  hasChildren: true,
};

const BRANCHING_TREE = new Map<string, TreeNodeData[]>([
  [
    "root",
    [
      { id: "one", text: "one", type: "Package", hasChildren: true },
      { id: "two", text: "two", type: "Package", hasChildren: true },
    ],
  ],
  [
    "one",
    [
      {
        id: "one.child",
        text: "one.child",
        type: "Package",
        hasChildren: false,
      },
    ],
  ],
  [
    "two",
    [
      {
        id: "two.child",
        text: "two.child",
        type: "Package",
        hasChildren: false,
      },
    ],
  ],
]);

function makeLoadChildren(tree: Map<string, TreeNodeData[]>) {
  // Simulate real network latency so the async chaining is exercised.
  return vi.fn(async (id: string) => {
    await new Promise((r) => setTimeout(r, 30));
    return tree.get(id) ?? [];
  });
}

describe("AsyncTree auto-expand root chain on load", () => {
  it("drills a single-child root chain down to the first branch without a click", async () => {
    const loadChildren = makeLoadChildren(SINGLE_CHILD_TREE);
    const onSelectedIdsChange = vi.fn();

    await renderWithQueryClient(
      <AsyncTree
        rootNode={SINGLE_CHILD_ROOT}
        loadChildren={loadChildren}
        onSelectedIdsChange={onSelectedIdsChange}
        label="root-drill"
        autoExpandOnLoad="root-chain"
        settings={TREE_SETTINGS}
      />,
    );

    // The last drilled row must be visible directly after mount, no interaction.
    await expect
      .element(page.getByText("a.b.c", { exact: true }))
      .toBeInTheDocument();

    // No auto-selection: every reported selection stays empty.
    for (const call of onSelectedIdsChange.mock.calls) {
      expect(call[0]).toEqual([]);
    }
  });

  it("does not expand or select when the root branches immediately", async () => {
    const loadChildren = makeLoadChildren(BRANCHING_TREE);
    const onSelectedIdsChange = vi.fn();

    await renderWithQueryClient(
      <AsyncTree
        rootNode={BRANCHING_ROOT}
        loadChildren={loadChildren}
        onSelectedIdsChange={onSelectedIdsChange}
        label="root-branch"
        autoExpandOnLoad="root-chain"
        settings={TREE_SETTINGS}
      />,
    );

    // Both root children are rendered by the tree's own child loader.
    await expect
      .element(page.getByText("one", { exact: true }))
      .toBeInTheDocument();
    await expect
      .element(page.getByText("two", { exact: true }))
      .toBeInTheDocument();

    // But no grandchild is expanded (the drill was a no-op on a branching root)
    // and nothing was auto-selected.
    await expect
      .element(page.getByText("one.child", { exact: true }))
      .not.toBeInTheDocument();
    for (const call of onSelectedIdsChange.mock.calls) {
      expect(call[0]).toEqual([]);
    }
  });
});
