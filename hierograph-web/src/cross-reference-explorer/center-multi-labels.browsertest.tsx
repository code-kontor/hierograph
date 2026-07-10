import { graphql, HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

import { CrossReferenceExplorerPage } from "./CrossReferenceExplorerPage";

// Cycle package FQNs — alpha→beta, beta→gamma, gamma→alpha (EXPECTED_VALUES §3),
// same fixture as marking-highlight.browsertest.tsx.
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
  );
});

it("center Ctrl-click multi-selection shows an aggregated count label and plural verb (Bug 4)", async () => {
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

  // Single center node: formatted name + singular verb.
  await expect
    .poll(() => page.getByText(`Anchor · ${BETA_FQN}`).element())
    .toBeTruthy();
  await expect
    .poll(() => page.getByText(`what ${BETA_FQN} uses`).element())
    .toBeTruthy();

  await userEvent.keyboard("{Control>}");
  await userEvent.click(centerTree.getByText(GAMMA_FQN));
  await userEvent.keyboard("{/Control}");

  // Multi center selection: aggregated count label + plural verb.
  await expect
    .poll(() => page.getByText("Anchor · 2 nodes").element())
    .toBeTruthy();
  await expect
    .poll(() => page.getByText("what 2 nodes use").element())
    .toBeTruthy();
  // Both Inspect buttons (Used by + Uses) reflect the multi-center condition.
  const multiInspectButtons = page.getByRole("button", {
    name: "Inspect works on a single anchor — select exactly one center node (2 nodes)",
  });
  expect(multiInspectButtons.elements()).toHaveLength(2);

  // Reducing back to a single center node restores the formatted name and
  // singular verb.
  await userEvent.click(centerTree.getByText(BETA_FQN));

  await expect
    .poll(() => page.getByText(`Anchor · ${BETA_FQN}`).element())
    .toBeTruthy();
  await expect
    .poll(() => page.getByText(`what ${BETA_FQN} uses`).element())
    .toBeTruthy();
});
