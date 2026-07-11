import { graphql, HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithRouter } from "@/testing/render";

import { CrossReferenceExplorerPage } from "./CrossReferenceExplorerPage";

// Topology built inline via MSW over real fqns (ids resolved at runtime):
// center root → { P (collapsed container), NEIGHBOR (leaf) }, and P → { L (leaf) }.
// A left partner click makes L related; L sits inside the collapsed P, so it is
// a hidden hit that must surface as a badge on P and in the hint bar.
const P_FQN = "org.hg.fixture.basic.cycle.alpha";
const L_FQN = "org.hg.fixture.basic.cycle.alpha.CycleA";
const NEIGHBOR_FQN = "org.hg.fixture.basic.cycle.beta";
const PARTNER_FQN = "org.hg.fixture.basic.cycle.gamma";

const P_ID = resolveNodeId(P_FQN);
const L_ID = resolveNodeId(L_FQN);
const NEIGHBOR_ID = resolveNodeId(NEIGHBOR_FQN);
const PARTNER_ID = resolveNodeId(PARTNER_FQN);

// Root sentinel id, as recorded in src/testing/fixtures/RootNode.json.
const ROOT_ID = "-1";

// CrossReferenceExplorerPage uses OneOneSplitLayout, which requires an explicit
// height parent so react-resizable-panels can distribute space.
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
                      id: P_ID,
                      text: P_FQN,
                      type: "java.package",
                      hasChildren: true,
                    },
                    {
                      id: NEIGHBOR_ID,
                      text: NEIGHBOR_FQN,
                      type: "java.class",
                      hasChildren: false,
                    },
                  ],
                },
              },
            },
          },
        });
      }
      if (id === P_ID) {
        return HttpResponse.json({
          data: {
            hierarchicalGraph: {
              node: {
                id: P_ID,
                children: {
                  nodes: [
                    {
                      id: L_ID,
                      text: L_FQN,
                      type: "java.class",
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
    // Left partner tree: one partner node the user can click.
    graphql.query("CrossReferenceExplorerLeftChildren", () => {
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: {
              childrenFilteredByReferencedNodes: {
                nodes: [
                  {
                    id: PARTNER_ID,
                    text: PARTNER_FQN,
                    type: "java.class",
                    hasChildren: false,
                  },
                ],
              },
            },
          },
        },
      });
    }),
    graphql.query("CrossReferenceExplorerRightChildren", () => {
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: { childrenFilteredByReferencingNodes: { nodes: [] } },
          },
        },
      });
    }),
    // The partner is related to the leaf L (which is hidden under collapsed P).
    graphql.query("CrossReferenceExplorerCenterRelatedByLeft", () => {
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            nodes: { referencedNodes: { nodeIds: [L_ID] } },
          },
        },
      });
    }),
    // L's ancestor chain (nearest first): its nearest ancestor is the collapsed P.
    graphql.query("CrossReferenceExplorerCenterPredecessors", () => {
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            nodes: { nodes: [{ id: L_ID, predecessors: [{ id: P_ID }] }] },
          },
        },
      });
    }),
    // Dependencies Details body queries — the auto-selection effect fires one of
    // these for the selected cell; these mocks just keep the body from throwing
    // "no fixture recorded" errors (irrelevant to this test).
    graphql.query("FilteredDependencies", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            dependencySetForAggregatedDependency: {
              size: 0,
              filteredDependencies: {
                markedSourceIds: [],
                markedTargetIds: [],
                markedSourceLeafIds: [],
                markedTargetLeafIds: [],
              },
            },
          },
        },
      }),
    ),
    graphql.query("FilteredChildren", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            dependencySetForAggregatedDependency: { filteredChildren: [] },
          },
        },
      }),
    ),
    graphql.query("DependencyEdges", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            dependencySetForAggregatedDependency: {
              dependencyPage: {
                pageInfo: {
                  pageNumber: 1,
                  maxPages: 1,
                  pageSize: 50,
                  totalCount: 0,
                },
                dependencies: [],
              },
            },
          },
        },
      }),
    ),
  );
});

it("badges hidden hits on their collapsed ancestor, surfaces a hint bar, and reveals on demand", async () => {
  await renderWithRouter(
    <div style={PAGE_WRAPPER_STYLE}>
      <CrossReferenceExplorerPage />
    </div>,
    "/cross-reference-explorer",
  );

  const centerTree = page.getByLabelText("XrefCenter");
  await expect
    .poll(() => centerTree.getByText(NEIGHBOR_FQN).element())
    .toBeTruthy();

  // Select a center node so the partner columns populate.
  await userEvent.click(centerTree.getByText(NEIGHBOR_FQN));

  const leftTree = page.getByLabelText("XrefLeft");
  await expect
    .poll(() => leftTree.getByText(PARTNER_FQN).element())
    .toBeTruthy();
  await userEvent.click(leftTree.getByText(PARTNER_FQN));

  // L is related but hidden inside the collapsed P → count badge "1" on P.
  await expect
    .poll(() => page.getByLabelText("1 hidden highlighted nodes").element())
    .toBeTruthy();

  // Hint bar visible with the total and its text.
  await expect
    .poll(() =>
      page.getByText("highlighted nodes in collapsed branches").element(),
    )
    .toBeTruthy();

  // L itself is not rendered yet (its branch is collapsed).
  expect(centerTree.getByText(L_FQN).elements().length).toBe(0);

  // Expand button reveals exactly P's branch → L becomes visible and highlighted.
  await userEvent.click(page.getByTitle("Expand all hits"));
  await expect
    .poll(
      () =>
        centerTree
          .getByText(L_FQN)
          .element()
          .closest("[class*='bg-state-highlighted-bg']") !== null,
    )
    .toBe(true);

  // Badge and hint bar disappear once nothing is hidden anymore.
  await expect
    .poll(
      () => page.getByLabelText("1 hidden highlighted nodes").elements().length,
    )
    .toBe(0);
  expect(
    page.getByText("highlighted nodes in collapsed branches").elements().length,
  ).toBe(0);

  // Reset: selecting another center node clears the related set and the bar.
  await userEvent.click(centerTree.getByText(P_FQN, { exact: true }));
  await expect
    .poll(
      () =>
        page.getByText("highlighted nodes in collapsed branches").elements()
          .length,
    )
    .toBe(0);
});
