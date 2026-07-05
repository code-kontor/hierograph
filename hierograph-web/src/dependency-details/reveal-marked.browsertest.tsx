import { graphql, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { SelectionProvider, useSelection } from "@/selection/SelectionContext";
import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

// Node ids shift whenever the fixture-app grows/shrinks; resolve them by fqn
// from the recorded fixtures instead of hard-coding (see resolveNodeId).
const SOURCE_ID = resolveNodeId("org.hg.fixture.basic.rel.source");
const TARGET_ID = resolveNodeId("org.hg.fixture.basic.rel.target");

function SelectCellButton() {
  const { setCellSelection } = useSelection();
  return (
    <button
      onClick={() =>
        setCellSelection({ sourceNodeId: SOURCE_ID, targetNodeId: TARGET_ID })
      }
    >
      select-cell
    </button>
  );
}

describe("Reveal marked button", () => {
  // The sibling force-mounted "Usages" tab also fires its DependencyEdges
  // query (no fixture); stub it so it isn't an unhandled request.
  beforeEach(() => {
    worker.use(
      graphql.query("DependencyEdges", () =>
        HttpResponse.json({
          data: {
            hierarchicalGraph: {
              dependencySetForAggregatedDependency: {
                size: 0,
                dependencies: [],
              },
            },
          },
        }),
      ),
    );
  });

  it("is disabled without a selection and enabled once a row is selected", async () => {
    await renderWithQueryClient(
      <SelectionProvider>
        <SelectCellButton />
        <DependencyDetailsPane />
      </SelectionProvider>,
    );

    await userEvent.click(page.getByText("select-cell"));
    await userEvent.click(page.getByText("Locations"));

    const revealButtons = page.getByRole("button", { name: "Reveal marked" });
    for (const el of revealButtons.elements()) {
      expect(el).toBeDisabled();
    }

    const subClassRow = page.getByText(
      "org.hg.fixture.basic.rel.source.SubClass",
      { exact: true },
    );
    await expect.element(subClassRow).toBeVisible();
    await userEvent.click(subClassRow);

    // The rel.source/rel.target fixture cell is flat (all classes are direct
    // children of the package root, no intermediate folder level), so there is
    // no deeper folder chain to expand here. This still exercises the wiring
    // this task adds: the button becomes enabled once the opposite side has a
    // non-empty marking, and a click doesn't throw. Deep multi-level expansion
    // is covered by the synthetic-tree test in
    // `src/tree/RevealMarked.browsertest.tsx`.
    await expect
      .poll(() =>
        revealButtons
          .elements()
          .some((el) => !(el as HTMLButtonElement).disabled),
      )
      .toBe(true);

    const enabledButton = revealButtons
      .elements()
      .find((el) => !(el as HTMLButtonElement).disabled) as HTMLButtonElement;
    await userEvent.click(enabledButton);

    await expect
      .element(
        page.getByText("org.hg.fixture.basic.rel.target.BaseClass", {
          exact: true,
        }),
      )
      .toBeVisible();
  });
});
