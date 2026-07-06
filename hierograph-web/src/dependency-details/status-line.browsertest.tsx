import { graphql, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { SelectionProvider, useSelection } from "@/selection/SelectionContext";
import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

// The locations.app -> locations.lib cell drives the status line through its
// three states: no selection, source selected, and source selected + filtered.
//
// Node ids shift whenever the fixture-app grows/shrinks; resolve them by fqn
// from the recorded fixtures instead of hard-coding (see resolveNodeId).
const SOURCE_ID = resolveNodeId("org.hg.fixture.locations.app");
const TARGET_ID = resolveNodeId("org.hg.fixture.locations.lib");
const BATCH = "org.hg.fixture.locations.app.batch";

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

describe("Locations status line", () => {
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

  it("reflects the committed selection and filter state", async () => {
    await renderWithQueryClient(
      <SelectionProvider>
        <SelectCellButton />
        <DependencyDetailsPane />
      </SelectionProvider>,
    );

    await userEvent.click(page.getByText("select-cell"));
    await userEvent.click(page.getByRole("tab", { name: "Locations" }));

    const status = () => page.getByTestId("locations-status");

    // No selection.
    await expect
      .element(status())
      .toHaveTextContent(/Showing all \d+ dependencies\./);
    await expect.element(status()).toHaveTextContent(/Select a node/);

    // Select the `batch` container on the source side.
    await userEvent.click(page.getByText(BATCH, { exact: true }));
    await expect.element(status()).toHaveTextContent(/→ \d+ types? in/);
    await expect.element(status()).toHaveTextContent(/marked on the right/);

    // Enable the counterpart filter.
    await userEvent.click(
      page.getByRole("button", { name: "Filter counterparts" }),
    );
    await expect
      .element(status())
      .toHaveTextContent(/right side filtered to matches/);
  });
});
