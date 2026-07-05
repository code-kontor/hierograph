import { describe, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { DependencyDetailsPanel } from "@/dependency-details/DependencyDetailsPanel";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

// Renders DependencyDetailsPanel directly for the rel.source -> rel.target cell.
// Node ids are resolved from the recorded fixtures by fqn (see resolveNodeId):
// they shift whenever the fixture-app grows/shrinks, so hard-coding them would
// make this test break on every re-record. The full DSM-click chain that
// produces such a cell selection is covered by DsmCanvas.test.tsx.
//
// Note: headless-tree (asyncDataLoaderFeature) treats rootItemId as a hidden root
// and only renders its children via getItems(), so we assert children, not the root label.
// Children are auto-loaded on first render — no explicit expand click needed.

const SOURCE_ID = resolveNodeId("org.hg.fixture.basic.rel.source");
const TARGET_ID = resolveNodeId("org.hg.fixture.basic.rel.target");

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
        labelFormat="full"
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
        labelFormat="full"
      />,
    );

    for (const text of EXPECTED_TARGET_CHILDREN) {
      await expect.element(page.getByText(text, { exact: true })).toBeVisible();
    }
  });

  // SubClass extends BaseClass; clicking SubClass in the Source tree must mark
  // BaseClass in the Target tree, which renders the "◆ marked" badge.
  it("marks the referenced target type when a source type is selected", async () => {
    await renderWithQueryClient(
      <DependencyDetailsPanel
        sourceNodeId={SOURCE_ID}
        targetNodeId={TARGET_ID}
        labelFormat="full"
      />,
    );

    const subClassRow = page.getByText(
      "org.hg.fixture.basic.rel.source.SubClass",
      { exact: true },
    );
    await expect.element(subClassRow).toBeVisible();
    await userEvent.click(subClassRow);

    await expect
      .poll(
        () =>
          page
            .getByText("org.hg.fixture.basic.rel.target.BaseClass", {
              exact: true,
            })
            .element()
            .closest("div")?.textContent,
      )
      .toContain("◆ marked");
  });

  // Reverse direction: clicking BaseClass in the Target tree must mark the
  // referencing SubClass in the Source tree. The Source tree has no badge, so
  // assert the marked styling class on the SubClass row instead.
  it("marks the referencing source type when a target type is selected", async () => {
    await renderWithQueryClient(
      <DependencyDetailsPanel
        sourceNodeId={SOURCE_ID}
        targetNodeId={TARGET_ID}
        labelFormat="full"
      />,
    );

    const baseClassRow = page.getByText(
      "org.hg.fixture.basic.rel.target.BaseClass",
      { exact: true },
    );
    await expect.element(baseClassRow).toBeVisible();
    await userEvent.click(baseClassRow);

    await expect
      .poll(
        () =>
          page
            .getByText("org.hg.fixture.basic.rel.source.SubClass", {
              exact: true,
            })
            .element()
            .closest("div")?.className,
      )
      .toContain("text-state-marked-fg");
  });
});
