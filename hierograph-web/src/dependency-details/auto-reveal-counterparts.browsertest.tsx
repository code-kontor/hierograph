import { graphql, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { SelectionProvider, useSelection } from "@/selection/SelectionContext";
import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

// The locations.app -> locations.lib cell has real package nesting on both
// sides (unlike the flat rel.source/rel.target cell). Selecting the `web`
// package marks the target class OrderService, which sits under the collapsed
// `lib.order` package — so its visibility is proof the target tree was
// auto-expanded down to the marked counterpart.
//
// Node ids shift whenever the fixture-app grows/shrinks; resolve them by fqn
// from the recorded fixtures instead of hard-coding (see resolveNodeId).
const SOURCE_ID = resolveNodeId("org.hg.fixture.locations.app");
const TARGET_ID = resolveNodeId("org.hg.fixture.locations.lib");
const WEB_PACKAGE = "org.hg.fixture.locations.app.web";
const NESTED_TARGET = "org.hg.fixture.locations.lib.order.OrderService";

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

describe("Auto-reveal counterparts", () => {
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

  it("expands the opposite tree to the marked counterparts only when the option is on", async () => {
    await renderWithQueryClient(
      <SelectionProvider>
        <SelectCellButton />
        <DependencyDetailsPane />
      </SelectionProvider>,
    );

    await userEvent.click(page.getByText("select-cell"));
    await userEvent.click(page.getByRole("tab", { name: "Locations" }));

    // Select the `web` package on the source side. This is a container node
    // (not a leaf class), so the whole subtree below it counts as selected.
    const webRow = page.getByText(WEB_PACKAGE, { exact: true });
    await expect.element(webRow).toBeVisible();
    await userEvent.click(webRow);

    // Marking is now active, but with the option off the target tree does not
    // open on its own: the nested OrderService stays inside the collapsed
    // lib.order package and never enters the DOM.
    await expect
      .element(page.getByText(NESTED_TARGET, { exact: true }))
      .not.toBeInTheDocument();

    // Turn on "Auto-reveal counterparts" via the Options menu checkbox.
    await userEvent.click(page.getByRole("button", { name: "Options" }));
    await userEvent.click(
      page.getByRole("menuitemcheckbox", { name: "Auto-reveal counterparts" }),
    );

    // The target tree now auto-expands down to the marked counterpart.
    await expect
      .element(page.getByText(NESTED_TARGET, { exact: true }))
      .toBeVisible();
  });
});
