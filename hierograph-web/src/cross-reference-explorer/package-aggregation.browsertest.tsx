import { graphql, HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

import { CrossReferenceExplorerPage } from "./CrossReferenceExplorerPage";

// Nested cycle package FQNs — cycle is class-less with alpha/beta/gamma
// sub-packages, each holding one leaf class two levels below cycle
// (EXPECTED_VALUES §1/§3). Same fixture as marking-highlight.browsertest.tsx.
const CYCLE_FQN = "org.hg.fixture.basic.cycle";
const CYCLE_A_FQN = "org.hg.fixture.basic.cycle.alpha.CycleA";
const CYCLE_B_FQN = "org.hg.fixture.basic.cycle.beta.CycleB";
const CYCLE_C_FQN = "org.hg.fixture.basic.cycle.gamma.CycleC";

const CYCLE_ID = resolveNodeId(CYCLE_FQN);
const CYCLE_A_ID = resolveNodeId(CYCLE_A_FQN);
const CYCLE_B_ID = resolveNodeId(CYCLE_B_FQN);
const CYCLE_C_ID = resolveNodeId(CYCLE_C_FQN);

// Root sentinel id, as recorded in src/testing/fixtures/RootNode.json.
const ROOT_ID = "-1";

// CrossReferenceExplorerPage uses OneOneSplitLayout, which requires an
// explicit height parent so react-resizable-panels can distribute space.
// Without it, all panels collapse to 0 height — elements are in the DOM but
// clicks fail.
const PAGE_WRAPPER_STYLE = { height: "600px" };

beforeEach(() => {
  worker.use(
    // Center tree: put the three leaf classes directly under root, so
    // centerLoadedIds (the marking candidate set) contains them without
    // navigating the full recorded hierarchy.
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
                      id: CYCLE_A_ID,
                      text: CYCLE_A_FQN,
                      type: "java.class",
                      hasChildren: false,
                    },
                    {
                      id: CYCLE_B_ID,
                      text: CYCLE_B_FQN,
                      type: "java.class",
                      hasChildren: false,
                    },
                    {
                      id: CYCLE_C_ID,
                      text: CYCLE_C_FQN,
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
    // Left partner tree: the non-leaf `cycle` package is a partner of the
    // selected center leaf.
    graphql.query("CrossReferenceExplorerLeftChildren", () => {
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: {
              childrenFilteredByReferencedNodes: {
                nodes: [
                  {
                    id: CYCLE_ID,
                    text: CYCLE_FQN,
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
    // Marking: mirrors the real backend's expandNodes(nodesToConsider) —
    // SELF_AND_CHILDREN on the non-leaf `cycle` package only reaches
    // {cycle, alpha, beta, gamma}, missing the leaf classes two levels down
    // (under-aggregation, the AC2 bug); SELF_AND_SUCCESSORS reaches all
    // descendants and aggregates correctly. This makes the test fail against
    // the pre-fix query doc and pass against the fixed one.
    graphql.query("CrossReferenceExplorerCenterMarkedByLeft", ({ query }) => {
      const nodeIds = query.includes("SELF_AND_SUCCESSORS")
        ? [CYCLE_A_ID, CYCLE_B_ID, CYCLE_C_ID]
        : [];
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            nodes: { filterReferencingNodes: { nodeIds } },
          },
        },
      });
    }),
  );
});

it("clicking a non-leaf package partner marks its nested leaf classes in the center", async () => {
  await renderWithQueryClient(
    <div style={PAGE_WRAPPER_STYLE}>
      <CrossReferenceExplorerPage />
    </div>,
  );

  const centerTree = page.getByLabelText("XrefCenter");
  await expect
    .poll(() => centerTree.getByText(CYCLE_A_FQN).element())
    .toBeTruthy();
  await userEvent.click(centerTree.getByText(CYCLE_A_FQN));

  const leftTree = page.getByLabelText("XrefLeft");
  await expect.poll(() => leftTree.getByText(CYCLE_FQN).element()).toBeTruthy();
  await userEvent.click(leftTree.getByText(CYCLE_FQN));

  // Both nested leaves (not the selected CycleA) are marked via aggregation.
  await expect
    .poll(
      () =>
        centerTree
          .getByText(CYCLE_B_FQN)
          .element()
          .closest("[class*='bg-state-related-bg']") !== null,
    )
    .toBe(true);
  await expect
    .poll(
      () =>
        centerTree
          .getByText(CYCLE_C_FQN)
          .element()
          .closest("[class*='bg-state-related-bg']") !== null,
    )
    .toBe(true);

  // The selected leaf stays selected, not marked.
  expect(
    centerTree
      .getByText(CYCLE_A_FQN)
      .element()
      .closest("[class*='bg-state-related-bg']"),
  ).toBeNull();
});
