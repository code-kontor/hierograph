import { graphql, HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

import { CrossReferenceExplorerPage } from "./CrossReferenceExplorerPage";

// Cycle package FQNs — alpha→beta, beta→gamma, gamma→alpha (EXPECTED_VALUES §3),
// same fixture as marking-highlight.browsertest.tsx.
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
    // Center tree: beta and gamma directly under root.
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
    // Left ("Used by") partner tree: alpha uses beta.
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
    // Right ("Uses") partner tree: beta uses gamma.
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
    graphql.query("CrossReferenceExplorerCenterMarkedByLeft", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            nodes: { filterReferencingNodes: { nodeIds: [] } },
          },
        },
      }),
    ),
    graphql.query("CrossReferenceExplorerCenterMarkedByRight", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            nodes: { filterReferencedNodes: { nodeIds: [] } },
          },
        },
      }),
    ),
    // Dependencies Details body queries — the auto-selection effect always
    // fires one of these, including with the graph root as an endpoint (no
    // recorded fixture covers that combination). Only the header (asserted
    // below) depends on NodeBasics/RootNode; these mocks just keep the body
    // from throwing "no fixture recorded" errors.
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

it("shows no Inspect button — the toolbar only carries tree settings", async () => {
  await renderWithQueryClient(
    <div style={PAGE_WRAPPER_STYLE}>
      <CrossReferenceExplorerPage />
    </div>,
  );

  expect(page.getByRole("button", { name: "Inspect" }).elements()).toHaveLength(
    0,
  );
});

it("center click auto-shows Everything that uses <center> in the Dependencies Details pane", async () => {
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

  await expect
    .element(page.getByText("No cell selected"))
    .not.toBeInTheDocument();
  await expect
    .element(page.getByText("Everything that uses", { exact: true }))
    .toBeVisible();
  await expect
    .poll(
      () =>
        page
          .getByText("Everything that uses", { exact: true })
          .element()
          .closest("div")?.textContent,
    )
    .toContain(BETA_FQN);
});

it("clicking a Used-by partner auto-shows the directed pair <partner> uses <center>", async () => {
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

  // Used-by partner alpha uses center beta → header reads "<alpha> uses <beta>".
  await expect.element(page.getByText("uses", { exact: true })).toBeVisible();
  await expect
    .poll(
      () =>
        page.getByText("uses", { exact: true }).element().closest("div")
          ?.textContent,
    )
    .toContain(ALPHA_FQN);
  await expect
    .poll(
      () =>
        page.getByText("uses", { exact: true }).element().closest("div")
          ?.textContent,
    )
    .toContain(BETA_FQN);
});

it("clicking a Uses partner auto-shows the directed pair <center> uses <partner>", async () => {
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

  const rightTree = page.getByLabelText("XrefRight");
  await expect
    .poll(() => rightTree.getByText(GAMMA_FQN).element())
    .toBeTruthy();
  await userEvent.click(rightTree.getByText(GAMMA_FQN));

  // Center beta uses partner gamma → header reads "<beta> uses <gamma>".
  await expect.element(page.getByText("uses", { exact: true })).toBeVisible();
  await expect
    .poll(
      () =>
        page.getByText("uses", { exact: true }).element().closest("div")
          ?.textContent,
    )
    .toContain(BETA_FQN);
  await expect
    .poll(
      () =>
        page.getByText("uses", { exact: true }).element().closest("div")
          ?.textContent,
    )
    .toContain(GAMMA_FQN);
});
