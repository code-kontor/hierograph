import { describe, expect, it, vi } from "vitest";
import { page, userEvent } from "vitest/browser";

import nodeChildrenFixture from "@/testing/fixtures/NodeChildren.json";
import { renderWithQueryClient } from "@/testing/render";

import { AsyncTree, type TreeNodeData } from "./AsyncTree";

// Build parentId -> children[] from the real recorded fixture-app tree.
const CHILDREN = new Map<string, TreeNodeData[]>();
for (const entry of nodeChildrenFixture.entries as Array<{
  variables: { id?: string };
  data: {
    hierarchicalGraph: {
      node: { children: { nodes: TreeNodeData[] } } | null;
    } | null;
  };
}>) {
  const id = entry.variables.id;
  const node = entry.data.hierarchicalGraph?.node;
  if (id == null || !node) continue;
  CHILDREN.set(id, node.children.nodes);
}

const ROOT: TreeNodeData = {
  id: "-1",
  text: "",
  type: "Unknown",
  hasChildren: true,
};

describe("AsyncTree auto-expand single children", () => {
  it("chains across module boundaries down to the first branching package", async () => {
    // Simulate real network latency so the async chaining is exercised.
    const loadChildren = vi.fn(async (id: string) => {
      await new Promise((r) => setTimeout(r, 30));
      return CHILDREN.get(id) ?? [];
    });
    await renderWithQueryClient(
      <AsyncTree
        rootNode={ROOT}
        loadChildren={loadChildren}
        onSelectedIdsChange={vi.fn()}
        label="auto-expand"
        settings={{
          showIndentGuides: true,
          autoExpandSingleChildren: true,
          preserveSelectionOnCollapse: false,
          labelFormat: "full",
        }}
      />,
    );

    // Expanding the project module must chain through the jar module and the
    // single-child packages (org > org.hg > org.hg.fixture > org.hg.fixture.basic)
    // in one action, revealing the branching package's children.
    const projectRow = page.getByText(
      "io.hierograph.examples:fixture-app:1.0.0",
      { exact: true },
    );
    await userEvent.click(projectRow);
    await userEvent.keyboard("{ArrowRight}");

    await expect
      .element(page.getByText("org.hg.fixture.basic.core", { exact: true }))
      .toBeInTheDocument();
  });
});
