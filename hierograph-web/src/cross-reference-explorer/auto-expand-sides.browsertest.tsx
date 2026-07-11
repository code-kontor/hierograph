import { graphql, HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithRouter } from "@/testing/render";

import { CrossReferenceExplorerPage } from "./CrossReferenceExplorerPage";

// Center node FQN; used by resolveNodeId to look up stable id.
const CENTER_FQN = "org.hg.fixture.basic.cycle.beta";
const CENTER_ID = resolveNodeId(CENTER_FQN);

// Root sentinel id as recorded in fixture.
const ROOT_ID = "-1";

// Synthetic left/right tree nodes (not from real fixture, just stable mock data).
const LEFT_L1_ID = "left-level1";
const LEFT_L1_TEXT = "LeftLevel1";
const LEFT_L2_ID = "left-level2";
const LEFT_L2_TEXT = "LeftLevel2DeepNode";

const RIGHT_L1_ID = "right-level1";
const RIGHT_L1_TEXT = "RightLevel1";
const RIGHT_L2_ID = "right-level2";
const RIGHT_L2_TEXT = "RightLevel2DeepNode";

const PAGE_WRAPPER_STYLE = { height: "600px" };

beforeEach(() => {
  worker.use(
    graphql.query("NodeChildren", ({ variables }) => {
      const { id } = variables as { id: string };
      // Center tree: put beta directly under root.
      if (id === ROOT_ID) {
        return HttpResponse.json({
          data: {
            hierarchicalGraph: {
              node: {
                id: ROOT_ID,
                children: {
                  nodes: [
                    {
                      id: CENTER_ID,
                      text: CENTER_FQN,
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
      // Root → level 1 (has children for auto-expand chaining).
      if (parentNode === ROOT_ID) {
        return HttpResponse.json({
          data: {
            hierarchicalGraph: {
              node: {
                childrenFilteredByReferencedNodes: {
                  nodes: [
                    {
                      id: LEFT_L1_ID,
                      text: LEFT_L1_TEXT,
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
      // Level 1 → level 2 (leaf, only reachable via auto-expand).
      if (parentNode === LEFT_L1_ID) {
        return HttpResponse.json({
          data: {
            hierarchicalGraph: {
              node: {
                childrenFilteredByReferencedNodes: {
                  nodes: [
                    {
                      id: LEFT_L2_ID,
                      text: LEFT_L2_TEXT,
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
          hierarchicalGraph: {
            node: { childrenFilteredByReferencedNodes: { nodes: [] } },
          },
        },
      });
    }),
    graphql.query("CrossReferenceExplorerRightChildren", ({ variables }) => {
      const { parentNode } = variables as { parentNode: string };
      // Root → level 1 (has children).
      if (parentNode === ROOT_ID) {
        return HttpResponse.json({
          data: {
            hierarchicalGraph: {
              node: {
                childrenFilteredByReferencingNodes: {
                  nodes: [
                    {
                      id: RIGHT_L1_ID,
                      text: RIGHT_L1_TEXT,
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
      // Level 1 → level 2 (leaf, only via auto-expand).
      if (parentNode === RIGHT_L1_ID) {
        return HttpResponse.json({
          data: {
            hierarchicalGraph: {
              node: {
                childrenFilteredByReferencingNodes: {
                  nodes: [
                    {
                      id: RIGHT_L2_ID,
                      text: RIGHT_L2_TEXT,
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
          hierarchicalGraph: {
            node: { childrenFilteredByReferencingNodes: { nodes: [] } },
          },
        },
      });
    }),
  );
});

it("auto-expand left and right trees when center node is selected", async () => {
  await renderWithRouter(
    <div style={PAGE_WRAPPER_STYLE}>
      <CrossReferenceExplorerPage />
    </div>,
    "/cross-reference-explorer",
  );

  // Select center node.
  const centerTree = page.getByLabelText("XrefCenter");
  await expect
    .poll(() => centerTree.getByText(CENTER_FQN).element())
    .toBeTruthy();
  await userEvent.click(centerTree.getByText(CENTER_FQN));

  // Get left and right trees.
  const leftTree = page.getByLabelText("XrefLeft");
  const rightTree = page.getByLabelText("XrefRight");

  // Verify level 1 nodes are visible (from root response).
  await expect
    .poll(() => leftTree.getByText(LEFT_L1_TEXT).element())
    .toBeTruthy();
  await expect
    .poll(() => rightTree.getByText(RIGHT_L1_TEXT).element())
    .toBeTruthy();

  // Verify level 2 nodes are visible without manual expansion (auto-expand="all" did this).
  await expect
    .poll(() => leftTree.getByText(LEFT_L2_TEXT).element())
    .toBeTruthy();
  await expect
    .poll(() => rightTree.getByText(RIGHT_L2_TEXT).element())
    .toBeTruthy();
});
