import { graphql, HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { page, userEvent } from "vitest/browser";

import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

import { CrossReferenceExplorerPage } from "./CrossReferenceExplorerPage";

// Container package at root level (P) with a class child (C) — beta has
// CycleB as a real child in the fixture-app (EXPECTED_VALUES §3), so P is a
// genuine container and C is only visible in the center tree after P expands.
const BETA_FQN = "org.hg.fixture.basic.cycle.beta";
const CYCLE_B_FQN = "org.hg.fixture.basic.cycle.beta.CycleB";

const BETA_ID = resolveNodeId(BETA_FQN);
const CYCLE_B_ID = resolveNodeId(CYCLE_B_FQN);

// Root sentinel id, as recorded in src/testing/fixtures/RootNode.json.
const ROOT_ID = "-1";

// CrossReferenceExplorerPage uses OneOneSplitLayout, which requires an
// explicit height parent so react-resizable-panels can distribute space.
// Without it, all panels collapse to 0 height — elements are in the DOM but
// clicks fail.
const PAGE_WRAPPER_STYLE = { height: "600px" };

let scrollSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  scrollSpy = vi
    .spyOn(Element.prototype, "scrollIntoView")
    .mockImplementation(() => {});

  worker.use(
    // Center tree: root -> beta (container) -> CycleB (leaf, initially hidden).
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
                      hasChildren: true,
                    },
                  ],
                },
              },
            },
          },
        });
      }
      if (id === BETA_ID) {
        return HttpResponse.json({
          data: {
            hierarchicalGraph: {
              node: {
                id: BETA_ID,
                children: {
                  nodes: [
                    {
                      id: CYCLE_B_ID,
                      text: CYCLE_B_FQN,
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
    graphql.query("CrossReferenceExplorerLeftChildren", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: { childrenFilteredByReferencedNodes: { nodes: [] } },
          },
        },
      }),
    ),
    graphql.query("CrossReferenceExplorerRightChildren", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: { childrenFilteredByReferencingNodes: { nodes: [] } },
          },
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
    // (beta, root) → "Everything beta uses" → one partner: CycleB.
    graphql.query("DependencyPartners", ({ variables }) => {
      const { sourceNodeId, targetNodeId } = variables as {
        sourceNodeId: string;
        targetNodeId: string;
      };
      if (sourceNodeId === BETA_ID && targetNodeId === ROOT_ID) {
        return HttpResponse.json({
          data: {
            hierarchicalGraph: {
              dependencySetForAggregatedDependency: {
                size: 1,
                dependencyPage: {
                  pageInfo: {
                    pageNumber: 1,
                    maxPages: 1,
                    pageSize: 1000,
                    totalCount: 1,
                  },
                  dependencies: [
                    {
                      id: "beta-uses-cycleb",
                      sourceNode: {
                        id: BETA_ID,
                        text: BETA_FQN,
                        type: "java.package",
                      },
                      targetNode: {
                        id: CYCLE_B_ID,
                        text: CYCLE_B_FQN,
                        type: "java.class",
                      },
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
          hierarchicalGraph: {
            dependencySetForAggregatedDependency: {
              size: 0,
              dependencyPage: {
                pageInfo: {
                  pageNumber: 1,
                  maxPages: 1,
                  pageSize: 1000,
                  totalCount: 0,
                },
                dependencies: [],
              },
            },
          },
        },
      });
    }),
    // CycleB's nearest ancestor is beta.
    graphql.query(
      "CrossReferenceExplorerCenterPredecessors",
      ({ variables }) => {
        const { relatedIds } = variables as { relatedIds: string[] };
        if (relatedIds.length === 1 && relatedIds[0] === CYCLE_B_ID) {
          return HttpResponse.json({
            data: {
              hierarchicalGraph: {
                nodes: {
                  nodes: [{ id: CYCLE_B_ID, predecessors: [{ id: BETA_ID }] }],
                },
              },
            },
          });
        }
        return HttpResponse.json({
          data: { hierarchicalGraph: { nodes: { nodes: [] } } },
        });
      },
    ),
  );
});

afterEach(() => {
  scrollSpy.mockRestore();
});

it("clicking a partner row reveals it in the center tree without changing the center selection", async () => {
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

  await userEvent.click(
    page.getByRole("button", { name: /^Inspect everything .+ uses$/ }),
  );

  const partnersList = page.getByTestId("dependency-partners-list");
  await expect.element(partnersList.getByTestId("partner-row")).toBeVisible();

  await userEvent.click(partnersList.getByTestId("partner-row"));

  // CycleB is now rendered in the center tree — proves beta was expanded.
  await expect
    .poll(() => centerTree.getByText(CYCLE_B_FQN).element())
    .toBeTruthy();

  expect(scrollSpy).toHaveBeenCalled();

  // Center selection is unchanged: beta still carries the primary selection,
  // CycleB does not.
  await expect
    .poll(
      () =>
        centerTree
          .getByText(BETA_FQN, { exact: true })
          .element()
          .closest("[class*='bg-state-selected-bg']") !== null,
    )
    .toBe(true);
  expect(
    centerTree
      .getByText(CYCLE_B_FQN)
      .element()
      .closest("[class*='bg-state-selected-bg']"),
  ).toBeNull();
});
