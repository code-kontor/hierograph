import { graphql, HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithRouter } from "@/testing/render";

import { CrossReferenceExplorerPage } from "./CrossReferenceExplorerPage";

// Cycle package FQNs — alpha→beta, beta→gamma, gamma→alpha (EXPECTED_VALUES §3),
// same fixture as center-multi-labels.browsertest.tsx.
const BETA_FQN = "org.hg.fixture.basic.cycle.beta";
const GAMMA_FQN = "org.hg.fixture.basic.cycle.gamma";

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
    // Center tree: beta and gamma both directly under root, so both can be
    // multi-selected without navigating the recorded hierarchy.
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
    // Dependencies Details body query — no recorded fixture is needed here,
    // every combination this test triggers returns an empty partner set.
    graphql.query("DependencyPartners", () =>
      HttpResponse.json({
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
      }),
    ),
  );
});

it("Inspect with a multi-node center anchor shows a hint and leaves the Details pane empty; a single anchor feeds it (Bug 4)", async () => {
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

  await userEvent.keyboard("{Control>}");
  await userEvent.click(centerTree.getByText(GAMMA_FQN));
  await userEvent.keyboard("{/Control}");

  await expect
    .poll(() => page.getByText("Anchor · 2 nodes").element())
    .toBeTruthy();

  // Both columns' Inspect buttons carry the same multi-center label; the
  // Used-by (left) one is clicked here, first in DOM order.
  await userEvent.click(
    page
      .getByRole("button", {
        name: "Inspect works on a single anchor — select exactly one center node (2 nodes)",
      })
      .first(),
  );

  await expect
    .element(
      page.getByText(
        "Inspect works on a single anchor — select exactly one center node.",
      ),
    )
    .toBeVisible();
  await expect.element(page.getByText("No selection")).toBeVisible();

  // Reducing to a single center node clears the hint automatically.
  await userEvent.click(centerTree.getByText(BETA_FQN));

  await expect
    .element(
      page.getByText(
        "Inspect works on a single anchor — select exactly one center node.",
      ),
    )
    .not.toBeInTheDocument();

  // Inspect now works: the aggregate feeds the Details pane.
  await userEvent.click(
    page.getByRole("button", { name: /^Inspect everything that uses / }),
  );

  await expect.element(page.getByText("No selection")).not.toBeInTheDocument();
});
