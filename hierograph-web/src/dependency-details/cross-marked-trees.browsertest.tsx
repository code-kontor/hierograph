import "@/index.css";

import { graphql, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/design-system/ui/resizable";
import { SelectionProvider, useSelection } from "@/selection/SelectionContext";
import { worker } from "@/testing/msw/worker";
import { renderWithQueryClient } from "@/testing/render";

// Regression for the "Cross-marked trees" tab showing only its header with the
// tree rows clipped away. Reproduces the real nesting faithfully — a vertical
// resizable panel group whose bottom panel (overflow:hidden) holds the
// DependencyDetailsPane (Tabs -> Pane). The tab content must fit and scroll
// *inside* the pane body; the tree rows must render within the panel's bounds,
// not overflow past it and get clipped.
//
// Needs Tailwind applied, so this file imports the stylesheet; the vitest config
// runs the @tailwindcss/vite plugin.

function SelectCellButton() {
  const { setCellSelection } = useSelection();
  return (
    <button
      onClick={() =>
        setCellSelection({ sourceNodeId: "127", targetNodeId: "101" })
      }
    >
      select-cell
    </button>
  );
}

describe("Cross-marked trees tab", () => {
  // The sibling force-mounted "Dependencies" tab also fires its DependencyEdges
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

  it("renders the tree rows within the panel bounds (not clipped)", async () => {
    const screen = await renderWithQueryClient(
      <SelectionProvider>
        <SelectCellButton />
        <div style={{ width: 640, height: 420, display: "flex" }}>
          <div style={{ flex: "1 1 0", minHeight: 0, padding: 12 }}>
            <ResizablePanelGroup direction="vertical" className="h-full">
              <ResizablePanel defaultSize={60} minSize={20}>
                <div style={{ height: "100%" }}>top</div>
              </ResizablePanel>
              <ResizableHandle />
              <ResizablePanel defaultSize={40} minSize={15}>
                <DependencyDetailsPane />
              </ResizablePanel>
            </ResizablePanelGroup>
          </div>
        </div>
      </SelectionProvider>,
    );

    await userEvent.click(page.getByText("select-cell"));
    await userEvent.click(page.getByText("Cross-marked trees"));

    const sourceRow = page.getByText(
      "org.hg.fixture.basic.rel.source.SubClass",
      { exact: true },
    );
    await expect.element(sourceRow).toBeVisible();

    // The panel that holds the DependencyDetailsPane (the Tabs root's parent).
    const tabsRoot = screen
      .getByText("Cross-marked trees")
      .element()
      .closest("[data-slot='tabs']") as HTMLElement;
    const panel = tabsRoot.parentElement as HTMLElement;

    // The trees are taller than this short panel, so they MUST be absorbed by an
    // inner scroll area inside the pane body. The bug let the content overflow
    // the panel (overflow:hidden), so its scrollHeight exceeded its clientHeight
    // and the lower rows were clipped away with no way to scroll to them.
    expect(panel.scrollHeight).toBeLessThanOrEqual(panel.clientHeight + 1);
  });
});
