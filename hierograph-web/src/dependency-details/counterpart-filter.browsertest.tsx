import { graphql, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { SelectionProvider, useSelection } from "@/selection/SelectionContext";
import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

// The locations.app -> locations.lib cell has real package nesting on both
// sides. Selecting the `batch` container marks the counterparts referenced by
// the whole subtree — which covers lib.order/report/customer but not lib.audit,
// and within lib.order hits OrderLine but not the non-referenced OrderService.
// That partial reference set is what makes the filter observable.
//
// Node ids shift whenever the fixture-app grows/shrinks; resolve them by fqn
// from the recorded fixtures instead of hard-coding (see resolveNodeId).
const SOURCE_ID = resolveNodeId("org.hg.fixture.locations.app");
const TARGET_ID = resolveNodeId("org.hg.fixture.locations.lib");
const BATCH = "org.hg.fixture.locations.app.batch";
const LIB_AUDIT = "org.hg.fixture.locations.lib.audit";
const LIB_ORDER = "org.hg.fixture.locations.lib.order";
const LIB_ORDER_LINE = "org.hg.fixture.locations.lib.order.detail.OrderLine";
const LIB_ORDER_SERVICE = "org.hg.fixture.locations.lib.order.OrderService";

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

describe("Counterpart filter", () => {
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

  it("filters the counterpart tree to the hit paths and restores it on toggle off", async () => {
    await renderWithQueryClient(
      <SelectionProvider>
        <SelectCellButton />
        <DependencyDetailsPane />
      </SelectionProvider>,
    );

    await userEvent.click(page.getByText("select-cell"));
    await userEvent.click(page.getByRole("tab", { name: "Locations" }));

    // `batch` is a first-level source package (auto-loaded).
    const batchRow = page.getByText(BATCH, { exact: true });
    await expect.element(batchRow).toBeVisible();

    // Selecting the container marks the whole subtree's counterparts. With the
    // filter off the full target tree is shown, so lib.audit is still visible.
    await userEvent.click(batchRow);
    await expect
      .element(page.getByText(LIB_AUDIT, { exact: true }))
      .toBeVisible();

    // Turn on the counterpart filter.
    await userEvent.click(
      page.getByRole("button", { name: "Filter counterparts" }),
    );

    // The target tree collapses to the hit paths: lib.audit (no reference) drops
    // out, lib.order stays and auto-expands down to the hit OrderLine, while the
    // non-referenced OrderService leaf under the same parent is filtered out.
    await expect
      .element(page.getByText(LIB_AUDIT, { exact: true }))
      .not.toBeInTheDocument();
    await expect
      .element(page.getByText(LIB_ORDER, { exact: true }))
      .toBeVisible();
    await expect
      .element(page.getByText(LIB_ORDER_LINE, { exact: true }))
      .toBeVisible();
    await expect
      .element(page.getByText(LIB_ORDER_SERVICE, { exact: true }))
      .not.toBeInTheDocument();

    // The filtered (target) side shows only matches, so its rows must not carry
    // the redundant amber marked highlight.
    expect(
      page.getByText(LIB_ORDER, { exact: true }).element().closest("div")
        ?.className ?? "",
    ).not.toContain("text-state-marked-fg");
    expect(
      page.getByText(LIB_ORDER_LINE, { exact: true }).element().closest("div")
        ?.className ?? "",
    ).not.toContain("text-state-marked-fg");

    // Counter-check: the selecting (source) side keeps its selection highlight.
    expect(
      page.getByText(BATCH, { exact: true }).element().closest("div")
        ?.className ?? "",
    ).toContain("text-state-selected-fg");

    // Toggle the filter off — the full tree is restored.
    await userEvent.click(
      page.getByRole("button", { name: "Filter counterparts" }),
    );
    await expect
      .element(page.getByText(LIB_AUDIT, { exact: true }))
      .toBeVisible();
  });
});
