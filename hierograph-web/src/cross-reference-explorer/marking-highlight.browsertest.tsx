import { graphql, HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithRouter } from "@/testing/render";

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
    // Highlights gamma (not beta, the currently-selected center node) — a
    // highlighted node distinct from the selection is needed to observe the
    // highlighted-row style, since a selected row always renders as selected.
    // Left reads referencedNodes post-flip: what the partner (alpha) uses.
    graphql.query("CrossReferenceExplorerCenterRelatedByLeft", () => {
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            nodes: { referencedNodes: { nodeIds: [GAMMA_ID] } },
          },
        },
      });
    }),
    // gamma is related and visible directly under root, so it has no hidden
    // ancestors — no badge, no hint bar.
    graphql.query("CrossReferenceExplorerCenterPredecessors", () => {
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            nodes: { nodes: [{ id: GAMMA_ID, predecessors: [] }] },
          },
        },
      });
    }),
  );
});

it("selecting a partner on the left highlights the matching center node without filtering the center", async () => {
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

  // Highlight, not navigate: beta stays selected in the center; the left click
  // only changes highlighting, not the center selection. The center anchor
  // remains in the primary (blue) tone, independent of the partner selection.
  await expect
    .poll(
      () =>
        centerTree
          .getByText(BETA_FQN)
          .element()
          .closest("[class*='bg-state-selected-bg']") !== null,
    )
    .toBe(true);

  // Partner-side selection renders in secondary tone: alpha in the left tree is
  // the currently-active selection, so it renders in the secondary (gray) style.
  await expect
    .poll(
      () =>
        leftTree
          .getByText(ALPHA_FQN)
          .element()
          .closest("[class*='bg-state-selected-secondary-bg']") !== null,
    )
    .toBe(true);

  // Not filtered (AC1 core): beta remains visible in the center alongside the
  // highlighted gamma — highlighting does not filter, it only visually marks related nodes.
  expect(
    centerTree
      .getByText(BETA_FQN)
      .element()
      .closest("[class*='bg-state-highlighted-bg']"),
  ).toBeNull();
});
