import { describe, expect, it } from "vitest";
import { page } from "vitest/browser";

import { DependencyDetailsPanel } from "@/components/dependency-details/DependencyDetailsPanel";
import { renderWithQueryClient } from "@/testing/render";

// Fixture-based IDs: rel.source=127, rel.target=101 (from NodeAdjacencyMatrix for parent id=100)
// handleSelectCell flips axes: cellSelection.sourceNodeId = DSM targetNodeId = rel.source (127)
//                              cellSelection.targetNodeId = DSM sourceNodeId = rel.target (101)
// DependencyDetailsPanel receives: sourceNodeId="127" (rel.source), targetNodeId="101" (rel.target)
//
// Fallback approach: render DependencyDetailsPanel directly with fixture IDs,
// since the 7-level tree navigation required to reach org.hg.fixture.basic.rel is
// too fragile for a browser-mode integration test (root node has empty text).
// The DSM-click chain is covered by DsmCanvas.test.tsx.
//
// Note: headless-tree (asyncDataLoaderFeature) treats rootItemId as a hidden root
// and only renders its children via getItems(), so we assert children, not the root label.
// Children are auto-loaded on first render — no explicit expand click needed.

const SOURCE_ID = "127"; // org.hg.fixture.basic.rel.source
const TARGET_ID = "101"; // org.hg.fixture.basic.rel.target

// Expected filtered source children (from FilteredChildren fixture, parentNode=127, SOURCE)
const EXPECTED_SOURCE_CHILDREN = [
  "org.hg.fixture.basic.rel.source.SubClass",
  "org.hg.fixture.basic.rel.source.FieldAccessor",
  "org.hg.fixture.basic.rel.source.AnnotatedType",
  "org.hg.fixture.basic.rel.source.MethodInvoker",
  "org.hg.fixture.basic.rel.source.FieldTypeRef",
  "org.hg.fixture.basic.rel.source.ContractImpl",
];

// Expected filtered target children (from FilteredChildren fixture, parentNode=101, TARGET)
const EXPECTED_TARGET_CHILDREN = [
  "org.hg.fixture.basic.rel.target.TargetA",
  "org.hg.fixture.basic.rel.target.ValueHolder",
  "org.hg.fixture.basic.rel.target.BaseClass",
  "org.hg.fixture.basic.rel.target.TargetB",
  "org.hg.fixture.basic.rel.target.TargetContract",
  "org.hg.fixture.basic.rel.target.TargetMarker",
];

describe("DependencyDetailsPanel — filter correctness (rel.source → rel.target)", () => {
  it("shows all 6 filtered source children", async () => {
    await renderWithQueryClient(
      <DependencyDetailsPanel
        sourceNodeId={SOURCE_ID}
        targetNodeId={TARGET_ID}
      />,
    );

    for (const text of EXPECTED_SOURCE_CHILDREN) {
      await expect.element(page.getByText(text, { exact: true })).toBeVisible();
    }
  });

  it("shows all 6 filtered target children", async () => {
    await renderWithQueryClient(
      <DependencyDetailsPanel
        sourceNodeId={SOURCE_ID}
        targetNodeId={TARGET_ID}
      />,
    );

    for (const text of EXPECTED_TARGET_CHILDREN) {
      await expect.element(page.getByText(text, { exact: true })).toBeVisible();
    }
  });
});
