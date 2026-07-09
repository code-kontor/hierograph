import { createRef } from "react";
import { describe, expect, it, vi } from "vitest";
import { page } from "vitest/browser";

import { renderWithQueryClient } from "@/testing/render";

import {
  AsyncTree,
  type AsyncTreeHandle,
  type TreeNodeData,
} from "./AsyncTree";

// Synthetic multi-level tree, deep enough that the marked leaf's folder chain
// spans several levels — the basic fixture-app tree used elsewhere is too flat
// (rel.source/rel.target children are all top-level leaves) to exercise the
// level-by-level loadChildren/expand loop in revealMarked().
const ROOT: TreeNodeData = {
  id: "root",
  text: "",
  type: "Unknown",
  hasChildren: true,
};

const TREE = new Map<string, TreeNodeData[]>([
  ["root", [{ id: "a", text: "a", type: "Package", hasChildren: true }]],
  [
    "a",
    [
      { id: "a.b", text: "a.b", type: "Package", hasChildren: true },
      {
        id: "a.sibling",
        text: "a.sibling",
        type: "Package",
        hasChildren: false,
      },
    ],
  ],
  ["a.b", [{ id: "a.b.c", text: "a.b.c", type: "Package", hasChildren: true }]],
  [
    "a.b.c",
    [
      {
        id: "a.b.c.Leaf",
        text: "a.b.c.Leaf",
        type: "Class",
        hasChildren: false,
      },
    ],
  ],
]);

function makeLoadChildren() {
  // Simulate real network latency so the level-by-level await is exercised.
  return vi.fn(async (id: string) => {
    await new Promise((r) => setTimeout(r, 10));
    return TREE.get(id) ?? [];
  });
}

describe("AsyncTree revealMarked handle", () => {
  it("expands every marked ancestor folder so a deeply nested marked leaf becomes visible", async () => {
    const ref = createRef<AsyncTreeHandle>();
    const loadChildren = makeLoadChildren();

    await renderWithQueryClient(
      <AsyncTree
        ref={ref}
        rootNode={ROOT}
        loadChildren={loadChildren}
        onSelectedIdsChange={vi.fn()}
        // markedIds mirrors the real server contract (includedPredecessors:
        // true): every ancestor of a marked leaf is itself marked, all the
        // way up — "a" included, even though it's the root's only child.
        highlightedIds={["a", "a.b", "a.b.c", "a.b.c.Leaf"]}
        label="reveal-marked"
        settings={{
          showIndentGuides: true,
          autoExpandSingleChildren: false,
          preserveSelectionOnCollapse: false,
          labelFormat: "full",
        }}
      />,
    );

    // Only the top-level folder "a" is loaded/visible initially; the deeply
    // nested marked leaf is not yet in the document.
    await expect
      .element(page.getByText("a", { exact: true }))
      .toBeInTheDocument();
    expect(
      page.getByText("a.b.c.Leaf", { exact: true }).elements().length,
    ).toBe(0);

    ref.current?.revealMarked();

    await expect
      .element(page.getByText("a.b.c.Leaf", { exact: true }))
      .toBeVisible();
  });

  it("is a no-op when there is nothing marked", async () => {
    const ref = createRef<AsyncTreeHandle>();
    const loadChildren = makeLoadChildren();

    await renderWithQueryClient(
      <AsyncTree
        ref={ref}
        rootNode={ROOT}
        loadChildren={loadChildren}
        onSelectedIdsChange={vi.fn()}
        highlightedIds={[]}
        label="reveal-marked-empty"
        settings={{
          showIndentGuides: true,
          autoExpandSingleChildren: false,
          preserveSelectionOnCollapse: false,
          labelFormat: "full",
        }}
      />,
    );

    await expect
      .element(page.getByText("a", { exact: true }))
      .toBeInTheDocument();

    ref.current?.revealMarked();

    // Give any accidental async work a chance to run, then assert the deep
    // leaf still hasn't been loaded/expanded into view.
    await new Promise((r) => setTimeout(r, 50));
    expect(
      page.getByText("a.b.c.Leaf", { exact: true }).elements().length,
    ).toBe(0);
  });
});
