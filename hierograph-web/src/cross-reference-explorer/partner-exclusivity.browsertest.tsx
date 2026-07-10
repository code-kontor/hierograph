import { graphql, HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

import { CrossReferenceExplorerPage } from "./CrossReferenceExplorerPage";

// Cycle package FQNs — alpha→beta, beta→gamma, gamma→alpha (EXPECTED_VALUES §3),
// same fixture as used-by-uses.browsertest.tsx.
const ALPHA_FQN = "org.hg.fixture.basic.cycle.alpha";
const BETA_FQN = "org.hg.fixture.basic.cycle.beta";
const GAMMA_FQN = "org.hg.fixture.basic.cycle.gamma";

const ALPHA_ID = resolveNodeId(ALPHA_FQN);
const BETA_ID = resolveNodeId(BETA_FQN);
const GAMMA_ID = resolveNodeId(GAMMA_FQN);

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
    graphql.query("CrossReferenceExplorerLeftChildren", ({ variables }) => {
      const { parentNode } = variables as { parentNode: string };
      if (parentNode === ALPHA_ID) {
        return HttpResponse.json({
          data: {
            hierarchicalGraph: {
              node: { childrenFilteredByReferencedNodes: { nodes: [] } },
            },
          },
        });
      }
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: {
              childrenFilteredByReferencedNodes: {
                nodes: [
                  {
                    id: ALPHA_ID,
                    text: ALPHA_FQN,
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
    graphql.query("CrossReferenceExplorerRightChildren", ({ variables }) => {
      const { parentNode } = variables as { parentNode: string };
      if (parentNode === GAMMA_ID) {
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
                ],
              },
            },
          },
        },
      });
    }),
    graphql.query("CrossReferenceExplorerCenterRelatedByLeft", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: { nodes: { referencedNodes: { nodeIds: [] } } },
        },
      }),
    ),
    graphql.query("CrossReferenceExplorerCenterRelatedByRight", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: { nodes: { referencingNodes: { nodeIds: [] } } },
        },
      }),
    ),
  );
});

it("selecting a partner on one side clears the selection on the other side (Bug 3)", async () => {
  await renderWithQueryClient(
    <div style={PAGE_WRAPPER_STYLE}>
      <CrossReferenceExplorerPage />
    </div>,
  );

  const centerTree = page.getByLabelText("XrefCenter");
  await expect
    .poll(() => centerTree.getByText(BETA_FQN).element())
    .toBeTruthy();
  await userEvent.click(centerTree.getByText(BETA_FQN));

  const leftTree = page.getByLabelText("XrefLeft");
  const rightTree = page.getByLabelText("XrefRight");

  await expect.poll(() => leftTree.getByText(ALPHA_FQN).element()).toBeTruthy();
  await userEvent.click(leftTree.getByText(ALPHA_FQN));

  await expect
    .poll(
      () =>
        leftTree
          .getByText(ALPHA_FQN)
          .element()
          .closest("[class*='bg-state-selected-secondary-bg']") !== null,
    )
    .toBe(true);
  await expect
    .poll(() => page.getByText(`Highlighting what ${ALPHA_FQN} uses`).element())
    .toBeTruthy();

  await expect
    .poll(() => rightTree.getByText(GAMMA_FQN).element())
    .toBeTruthy();
  await userEvent.click(rightTree.getByText(GAMMA_FQN));

  // Right takes over: left's selection is cleared and the center sub-label
  // flips to the "Uses" direction.
  await expect
    .poll(
      () =>
        rightTree
          .getByText(GAMMA_FQN)
          .element()
          .closest("[class*='bg-state-selected-secondary-bg']") !== null,
    )
    .toBe(true);
  await expect
    .poll(
      () =>
        leftTree
          .getByText(ALPHA_FQN)
          .element()
          .closest("[class*='bg-state-selected-secondary-bg']") !== null,
    )
    .toBe(false);
  await expect
    .poll(() => page.getByText(`Highlighting what uses ${GAMMA_FQN}`).element())
    .toBeTruthy();
});
