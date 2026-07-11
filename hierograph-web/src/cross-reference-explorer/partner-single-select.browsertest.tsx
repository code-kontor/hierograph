import { graphql, HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithRouter } from "@/testing/render";

import { CrossReferenceExplorerPage } from "./CrossReferenceExplorerPage";

// Cycle package FQNs — alpha→beta, beta→gamma, gamma→alpha (EXPECTED_VALUES §3).
const BETA_FQN = "org.hg.fixture.basic.cycle.beta";
const GAMMA_FQN = "org.hg.fixture.basic.cycle.gamma";
// Unrelated package, used only as a second selectable node in the Uses column.
const ISOLATED_FQN = "org.hg.fixture.basic.isolated";

const BETA_ID = resolveNodeId(BETA_FQN);
const GAMMA_ID = resolveNodeId(GAMMA_FQN);
const ISOLATED_ID = resolveNodeId(ISOLATED_FQN);

// Root sentinel id, as recorded in src/testing/fixtures/RootNode.json.
const ROOT_ID = "-1";

// CrossReferenceExplorerPage uses OneOneSplitLayout, which requires an
// explicit height parent so react-resizable-panels can distribute space.
// Without it, all panels collapse to 0 height — elements are in the DOM but
// clicks fail.
const PAGE_WRAPPER_STYLE = { height: "600px" };

beforeEach(() => {
  worker.use(
    graphql.query("NodeChildren", ({ variables }) => {
      const { id } = variables as { id: string };
      if (id === ROOT_ID) {
        return HttpResponse.json({
          data: {
            hierarchicalGraph: {
              node: {
                id: ROOT_ID,
                children: {
                  nodes: [
                    {
                      id: BETA_ID,
                      text: BETA_FQN,
                      type: "java.package",
                      hasChildren: false,
                    },
                  ],
                },
              },
            },
          },
        });
      }
      return HttpResponse.json({
        data: {
          hierarchicalGraph: { node: { id, children: { nodes: [] } } },
        },
      });
    }),
    graphql.query("CrossReferenceExplorerLeftChildren", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: { childrenFilteredByReferencedNodes: { nodes: [] } },
          },
        },
      }),
    ),
    // Right ("Uses") partner tree: two top-level nodes, so a Ctrl/Cmd-click on
    // the second exercises single-select enforcement against the first.
    graphql.query("CrossReferenceExplorerRightChildren", ({ variables }) => {
      const { parentNode } = variables as { parentNode: string };
      if (parentNode === GAMMA_ID || parentNode === ISOLATED_ID) {
        return HttpResponse.json({
          data: {
            hierarchicalGraph: {
              node: { childrenFilteredByReferencingNodes: { nodes: [] } },
            },
          },
        });
      }
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: {
              childrenFilteredByReferencingNodes: {
                nodes: [
                  {
                    id: GAMMA_ID,
                    text: GAMMA_FQN,
                    type: "java.package",
                    hasChildren: false,
                  },
                  {
                    id: ISOLATED_ID,
                    text: ISOLATED_FQN,
                    type: "java.package",
                    hasChildren: false,
                  },
                ],
              },
            },
          },
        },
      });
    }),
    graphql.query("CrossReferenceExplorerCenterRelatedByRight", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: { nodes: { referencingNodes: { nodeIds: [] } } },
        },
      }),
    ),
  );
});

it("Ctrl/Cmd-click on a second partner node keeps only the last-clicked node selected", async () => {
  await renderWithRouter(
    <div style={PAGE_WRAPPER_STYLE}>
      <CrossReferenceExplorerPage />
    </div>,
    "/cross-reference-explorer",
  );

  const centerTree = page.getByLabelText("XrefCenter");
  await expect
    .poll(() => centerTree.getByText(BETA_FQN).element())
    .toBeTruthy();
  await userEvent.click(centerTree.getByText(BETA_FQN));

  const rightTree = page.getByLabelText("XrefRight");
  await expect
    .poll(() => rightTree.getByText(GAMMA_FQN).element())
    .toBeTruthy();
  await expect
    .poll(() => rightTree.getByText(ISOLATED_FQN).element())
    .toBeTruthy();

  await userEvent.click(rightTree.getByText(GAMMA_FQN));
  await userEvent.keyboard("{Control>}");
  await userEvent.click(rightTree.getByText(ISOLATED_FQN));
  await userEvent.keyboard("{/Control}");

  // Single-select mode: the Ctrl modifier is ignored, only isolated ends up
  // selected.
  await expect
    .poll(
      () =>
        rightTree
          .getByText(ISOLATED_FQN)
          .element()
          .closest("[class*='bg-state-selected-secondary-bg']") !== null,
    )
    .toBe(true);
  expect(
    rightTree
      .getByText(GAMMA_FQN)
      .element()
      .closest("[class*='bg-state-selected-secondary-bg']"),
  ).toBeNull();
});
