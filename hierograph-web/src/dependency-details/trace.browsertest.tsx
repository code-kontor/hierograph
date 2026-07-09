import { graphql, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { SelectionProvider, useSelection } from "@/selection/SelectionContext";
import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

// SubClass extends BaseClass and references nothing else on the target side —
// selecting it marks exactly BaseClass as its counterpart (see #46's
// marking-with-selection path, reused here through the same queries).
//
// Node ids shift whenever the fixture-app grows/shrinks; resolve them by fqn
// from the recorded fixtures instead of hard-coding (see resolveNodeId).
const SOURCE_ID = resolveNodeId("org.hg.fixture.basic.rel.source");
const TARGET_ID = resolveNodeId("org.hg.fixture.basic.rel.target");
const SUB_CLASS = "org.hg.fixture.basic.rel.source.SubClass";
const BASE_CLASS = "org.hg.fixture.basic.rel.target.BaseClass";
const TARGET_A = "org.hg.fixture.basic.rel.target.TargetA";

// A second, distinct cell (used to trigger a PathsPanel remount via
// key={cellKey} without re-selecting the same cell). This locations.app→lib
// cell has real intermediate folders on both sides, so it exercises deep
// expand/collapse and the hits-only counterpart reveal.
const SOURCE_ID_2 = resolveNodeId("org.hg.fixture.locations.app");
const TARGET_ID_2 = resolveNodeId("org.hg.fixture.locations.lib");
// A first-level source package whose counterparts sit two folders deep on the
// target side (only package drivers are recorded for this cell — never a type).
const LOC_DRIVER = "org.hg.fixture.locations.app.batch";
const DEEP_COUNTERPART = "org.hg.fixture.locations.lib.order.detail.OrderLine";

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

function SelectOtherCellButton() {
  const { setCellSelection } = useSelection();
  return (
    <button
      onClick={() =>
        setCellSelection({
          sourceNodeId: SOURCE_ID_2,
          targetNodeId: TARGET_ID_2,
        })
      }
    >
      select-cell-2
    </button>
  );
}

async function renderPathsTab() {
  await renderWithQueryClient(
    <SelectionProvider>
      <SelectCellButton />
      <SelectOtherCellButton />
      <DependencyDetailsPane />
    </SelectionProvider>,
  );

  await userEvent.click(page.getByText("select-cell", { exact: true }));
  await userEvent.click(page.getByRole("tab", { name: "Paths" }));
}

// The deep locations.app→lib cell, used for expand/collapse and hits-only
// reveal assertions on genuinely nested folders.
async function renderPathsTabDeepCell() {
  await renderWithQueryClient(
    <SelectionProvider>
      <SelectCellButton />
      <SelectOtherCellButton />
      <DependencyDetailsPane />
    </SelectionProvider>,
  );

  await userEvent.click(page.getByText("select-cell-2"));
  await userEvent.click(page.getByRole("tab", { name: "Paths" }));
}

function sourceRow(text: string) {
  return page
    .getByLabelText("TraceSourceTree")
    .getByText(text, { exact: true });
}
function targetRow(text: string) {
  return page
    .getByLabelText("TraceTargetTree")
    .getByText(text, { exact: true });
}

function rowClassName(row: ReturnType<typeof sourceRow>): string {
  return row.element().closest("div")?.className ?? "";
}

describe("Paths tab", () => {
  // The sibling force-mounted "Usages" tab also fires its DependencyEdges
  // query (no fixture); stub it so it isn't an unhandled request.
  beforeEach(() => {
    localStorage.clear();
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

  it("marks the counterpart on click and shows the source→target direction", async () => {
    await renderPathsTab();

    await expect.element(sourceRow(SUB_CLASS)).toBeVisible();
    await userEvent.click(sourceRow(SUB_CLASS));

    await expect.element(targetRow(BASE_CLASS)).toBeVisible();
    // The marked-counterpart query is async; poll rather than assert once.
    await expect
      .poll(() => rowClassName(targetRow(BASE_CLASS)))
      .toContain("bg-state-highlighted-bg");
    expect(rowClassName(sourceRow(SUB_CLASS))).toContain(
      "text-state-selected-fg",
    );

    await expect.element(page.getByText(/Dependencies of/)).toBeVisible();
    await expect
      .element(page.getByTestId("trace-status"))
      .toHaveTextContent("references");
    const statusSummary = page
      .getByTestId("trace-status")
      .element()
      .querySelector("span > span");
    expect(statusSummary?.className).toContain("font-medium");
    expect(
      page.getByTestId("trace-status").element().querySelector("span")?.title,
    ).toMatch(/references/);
  });

  it("is exclusive: driving from the target side clears the source selection", async () => {
    await renderPathsTab();

    await userEvent.click(sourceRow(SUB_CLASS));
    await expect.element(targetRow(BASE_CLASS)).toBeVisible();

    await userEvent.click(targetRow(BASE_CLASS));

    await expect
      .element(page.getByTestId("trace-status"))
      .toHaveTextContent("referenced by");
    // The source side is now the counterpart: marked, but no longer the
    // (blue) selected row — the previous driver selection was cleared. The
    // marked-counterpart query is async; poll rather than assert once.
    await expect
      .poll(() => rowClassName(sourceRow(SUB_CLASS)))
      .toContain("bg-state-highlighted-bg");
    expect(rowClassName(sourceRow(SUB_CLASS))).not.toContain(
      "text-state-selected-fg",
    );
    expect(rowClassName(targetRow(BASE_CLASS))).toContain(
      "text-state-selected-fg",
    );
  });

  it("the single view toggle switches between in-context and hits-only", async () => {
    await renderPathsTab();

    await userEvent.click(sourceRow(SUB_CLASS));
    await expect.element(targetRow(TARGET_A)).toBeVisible();

    const toggle = page.getByRole("button", { name: "Show hits only" });

    await userEvent.click(toggle);
    await expect.element(targetRow(TARGET_A)).not.toBeInTheDocument();
    await expect.element(targetRow(BASE_CLASS)).toBeVisible();
    await expect.element(toggle).toHaveAttribute("aria-pressed", "true");

    await userEvent.click(toggle);
    await expect.element(targetRow(TARGET_A)).toBeVisible();
    await expect.element(toggle).toHaveAttribute("aria-pressed", "false");
  });

  it("keeps hits-only view mode across tab switches", async () => {
    await renderPathsTab();

    await userEvent.click(sourceRow(SUB_CLASS));
    await userEvent.click(page.getByRole("button", { name: "Show hits only" }));

    await userEvent.click(page.getByRole("tab", { name: "Usages" }));
    await userEvent.click(page.getByRole("tab", { name: "Paths" }));

    await expect
      .element(page.getByRole("button", { name: "Show hits only" }))
      .toHaveAttribute("aria-pressed", "true");
  });

  it("keeps hits-only view mode across cell selection changes", async () => {
    await renderPathsTab();

    await userEvent.click(sourceRow(SUB_CLASS));
    await userEvent.click(page.getByRole("button", { name: "Show hits only" }));

    await userEvent.click(page.getByText("select-cell-2"));

    await expect
      .element(page.getByRole("button", { name: "Show hits only" }))
      .toHaveAttribute("aria-pressed", "true");
  });

  it("clear selection drops the driver and both selections", async () => {
    await renderPathsTab();

    const clear = page.getByRole("button", { name: "Clear selection" });
    await expect.element(clear).toBeDisabled(); // nothing selected yet

    await userEvent.click(sourceRow(SUB_CLASS));
    await expect.element(clear).toBeEnabled();

    await userEvent.click(clear);
    await expect
      .element(page.getByTestId("trace-status"))
      .toHaveTextContent("Select a type"); // back to idle status
    expect(rowClassName(sourceRow(SUB_CLASS))).not.toContain(
      "text-state-selected-fg",
    );
    await expect.element(clear).toBeDisabled();
  });

  it("expand all / collapse all act on both trees", async () => {
    await renderPathsTabDeepCell();

    await userEvent.click(page.getByRole("button", { name: "Expand all" }));
    await expect.element(targetRow(DEEP_COUNTERPART)).toBeVisible();

    await userEvent.click(page.getByRole("button", { name: "Collapse all" }));
    await expect.element(targetRow(DEEP_COUNTERPART)).not.toBeInTheDocument();
  });

  it("hits-only reveals a deeply nested counterpart", async () => {
    await renderPathsTabDeepCell();

    // Enable hits-only BEFORE selecting a driver, so selecting drives the async
    // marks → key-remount path that must still leave the counterpart expanded.
    await userEvent.click(page.getByRole("button", { name: "Show hits only" }));
    await userEvent.click(sourceRow(LOC_DRIVER)); // package driver app.batch

    // OrderLine sits two folders deep (lib → order → detail); it must be
    // expanded into view, not left collapsed behind its ancestors.
    await expect.element(targetRow(DEEP_COUNTERPART)).toBeVisible();
  });
});
