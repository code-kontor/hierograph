import { graphql, HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

import { CrossReferenceExplorerPage } from "./CrossReferenceExplorerPage";

// Cycle package FQNs — alpha→beta, beta→gamma, gamma→alpha (EXPECTED_VALUES §3),
// same fixture as src/cross-references/used-by-uses.browsertest.tsx.
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
    // Center tree: put beta and gamma directly under root, so that after
    // selecting beta, gamma remains visible as a second, unmarked center node
    // (proof that marking highlights rather than filters).
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
    graphql.query("CrossReferenceExplorerRightChildren", () => {
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: { childrenFilteredByReferencingNodes: { nodes: [] } },
          },
        },
      });
    }),
    // Marks gamma (not beta, the currently-selected center node) — a marked
    // node distinct from the selection is needed to observe the marked-row
    // style, since a selected row always renders as selected, never marked.
    graphql.query("CrossReferenceExplorerCenterMarkedByLeft", () => {
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            nodes: { filterReferencingNodes: { nodeIds: [GAMMA_ID] } },
          },
        },
      });
    }),
  );
});

it("clicking a partner on the left highlights the matching center node without filtering the center", async () => {
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
  await expect.poll(() => leftTree.getByText(ALPHA_FQN).element()).toBeTruthy();
  await userEvent.click(leftTree.getByText(ALPHA_FQN));

  // Marking greift: gamma's row is highlighted via the shared marked-row token.
  await expect
    .poll(
      () =>
        centerTree
          .getByText(GAMMA_FQN)
          .element()
          .closest("[class*='bg-state-highlighted-bg']") !== null,
    )
    .toBe(true);

  // Highlight statt Navigieren: beta stays selected in the center; the left
  // click only changes marking, not the center selection. While a partner is
  // the active selection, the center selection renders in the secondary tone so
  // the two selections stay visually distinct.
  await expect
    .poll(
      () =>
        centerTree
          .getByText(BETA_FQN)
          .element()
          .closest("[class*='bg-state-selected-secondary-bg']") !== null,
    )
    .toBe(true);

  // Not filtered (AC1 core): beta remains visible in the center alongside the
  // marked gamma — marking highlights, it does not remove other nodes.
  expect(
    centerTree
      .getByText(BETA_FQN)
      .element()
      .closest("[class*='bg-state-highlighted-bg']"),
  ).toBeNull();
});
