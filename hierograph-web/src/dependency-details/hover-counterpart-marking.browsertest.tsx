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

describe("Hover counterpart marking", () => {
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

  it("only previews on hover once 'Highlight on hover' is enabled", async () => {
    await renderWithQueryClient(
      <SelectionProvider>
        <SelectCellButton />
        <DependencyDetailsPane />
      </SelectionProvider>,
    );

    await userEvent.click(page.getByText("select-cell"));
    await userEvent.click(page.getByText("Locations"));

    const sourceRow = page.getByText(
      "org.hg.fixture.basic.rel.source.SubClass",
      { exact: true },
    );
    await expect.element(sourceRow).toBeVisible();

    const markedBadge = () => page.getByText("◆ marked");

    // Default is off: hovering must not trigger the debounced marking query, so
    // the badge never appears. Wait well past the ~200ms debounce, then assert.
    await userEvent.hover(sourceRow);
    await new Promise((r) => setTimeout(r, 400));
    await expect.element(markedBadge()).not.toBeInTheDocument();
    await userEvent.unhover(sourceRow);

    // Enable "Highlight on hover" via the Options menu checkbox.
    await userEvent.click(page.getByRole("button", { name: "Options" }));
    await userEvent.click(
      page.getByRole("menuitemcheckbox", { name: "Highlight on hover" }),
    );
    // Close the menu so it does not overlay the tree rows.
    await userEvent.keyboard("{Escape}");

    // Now hovering previews the counterpart marking (debounced ~200ms).
    await userEvent.hover(sourceRow);
    await expect.element(markedBadge()).toBeVisible();

    await userEvent.unhover(sourceRow);
    await expect.element(markedBadge()).not.toBeInTheDocument();
  });
});
