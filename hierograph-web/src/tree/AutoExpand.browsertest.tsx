import { describe, expect, it, vi } from "vitest";
import { page, userEvent } from "vitest/browser";

import nodeChildrenFixture from "@/testing/fixtures/NodeChildren.json";
import { resolveNodeId } from "@/testing/nodeLookup";
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

const PROJECT_MODULE = "io.hierograph.examples:fixture-app:1.0.0";

// Descend from `startFqn` through single-child nodes (the auto-expand chain)
// and return the children of the first branching node — the ones auto-expand
// reveals in a single action. Computed from the recorded tree so that adding or
// removing a fixture tenant (which moves where the branch is) never touches this
// test: with only `basic`, the branch is `org.hg.fixture.basic`; adding
// `locations` moves it up to `org.hg.fixture` (children basic + locations).
function firstBranchChildren(startFqn: string): TreeNodeData[] {
  let id = resolveNodeId(startFqn);
  for (;;) {
    const kids = CHILDREN.get(id) ?? [];
    if (kids.length === 1 && kids[0].hasChildren) {
      id = kids[0].id;
      continue;
    }
    return kids;
  }
}

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
    // single-child packages (org > org.hg > …) in one action, revealing the
    // first branching package's children.
    const projectRow = page.getByText(PROJECT_MODULE, { exact: true });
    await userEvent.click(projectRow);
    await userEvent.keyboard("{ArrowRight}");

    const branchChildren = firstBranchChildren(PROJECT_MODULE);
    expect(branchChildren.length).toBeGreaterThan(1);
    for (const child of branchChildren) {
      await expect
        .element(page.getByText(child.text, { exact: true }))
        .toBeInTheDocument();
    }
  });
});
