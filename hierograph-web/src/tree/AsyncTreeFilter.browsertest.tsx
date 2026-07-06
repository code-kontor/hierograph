import { describe, expect, it, vi } from "vitest";
import { page } from "vitest/browser";

import { renderWithQueryClient } from "@/testing/render";

import { AsyncTree, type TreeNodeData } from "./AsyncTree";

const TREE_SETTINGS = {
  showIndentGuides: true,
  autoExpandSingleChildren: false,
  preserveSelectionOnCollapse: false,
  labelFormat: "full",
} as const;

// r -> [a, b]; a -> [a.x(leaf), a.y]; a.y -> [a.y.1(leaf)]; b -> [b.z(leaf)].
// Only a.y.1 is a hit, so its full path is a / a.y / a.y.1. The sibling leaf
// a.x and the entire b subtree are not hits.
const FILTER_ROOT: TreeNodeData = {
  id: "r",
  text: "",
  type: "Unknown",
  hasChildren: true,
};

const FILTER_TREE = new Map<string, TreeNodeData[]>([
  [
    "r",
    [
      { id: "a", text: "a", type: "Package", hasChildren: true },
      { id: "b", text: "b", type: "Package", hasChildren: true },
    ],
  ],
  [
    "a",
    [
      { id: "a.x", text: "a.x", type: "Package", hasChildren: false },
      { id: "a.y", text: "a.y", type: "Package", hasChildren: true },
    ],
  ],
  [
    "a.y",
    [{ id: "a.y.1", text: "a.y.1", type: "Package", hasChildren: false }],
  ],
  ["b", [{ id: "b.z", text: "b.z", type: "Package", hasChildren: false }]],
]);

function makeLoadChildren(tree: Map<string, TreeNodeData[]>) {
  return vi.fn(async (id: string) => {
    await new Promise((r) => setTimeout(r, 30));
    return tree.get(id) ?? [];
  });
}

describe("AsyncTree filter mode", () => {
  it("shows only hit paths and auto-expands surviving folders", async () => {
    const loadChildren = makeLoadChildren(FILTER_TREE);
    const onSelectedIdsChange = vi.fn();

    await renderWithQueryClient(
      <AsyncTree
        rootNode={FILTER_ROOT}
        loadChildren={loadChildren}
        onSelectedIdsChange={onSelectedIdsChange}
        label="filter-tree"
        filterIds={["a", "a.y", "a.y.1"]}
        settings={TREE_SETTINGS}
      />,
    );

    // Surviving folders auto-expanded down to the hit leaf.
    await expect
      .element(page.getByText("a.y.1", { exact: true }))
      .toBeInTheDocument();
    await expect
      .element(page.getByText("a.y", { exact: true }))
      .toBeInTheDocument();
    await expect
      .element(page.getByText("a", { exact: true }))
      .toBeInTheDocument();

    // Non-hit leaf sibling and the whole non-hit subtree stay filtered out.
    await expect
      .element(page.getByText("a.x", { exact: true }))
      .not.toBeInTheDocument();
    await expect
      .element(page.getByText("b", { exact: true }))
      .not.toBeInTheDocument();
  });
});
