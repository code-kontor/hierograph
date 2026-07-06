import { graphql, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { SelectionProvider, useSelection } from "@/selection/SelectionContext";
import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

// The locations.app -> locations.lib cell has real package nesting on both
// sides. Both trees must auto-drill to their first branching level on load, so
// content is visible without any top-level click.
//
// Node ids shift whenever the fixture-app grows/shrinks; resolve them by fqn
// from the recorded fixtures instead of hard-coding (see resolveNodeId).
const SOURCE_ID = resolveNodeId("org.hg.fixture.locations.app");
const TARGET_ID = resolveNodeId("org.hg.fixture.locations.lib");
const APP_WEB = "org.hg.fixture.locations.app.web";
const LIB_ORDER = "org.hg.fixture.locations.lib.order";

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

describe("Initial auto-expand (both sides)", () => {
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

  it("shows initial content on both trees without a top-level click", async () => {
    await renderWithQueryClient(
      <SelectionProvider>
        <SelectCellButton />
        <DependencyDetailsPane />
      </SelectionProvider>,
    );

    await userEvent.click(page.getByText("select-cell"));
    await userEvent.click(page.getByRole("tab", { name: "Locations" }));

    // No expand interaction: both trees drill to their first branch on load.
    await expect
      .element(page.getByText(APP_WEB, { exact: true }))
      .toBeVisible();
    await expect
      .element(page.getByText(LIB_ORDER, { exact: true }))
      .toBeVisible();
  });
});
