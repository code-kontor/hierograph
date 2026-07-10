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
    // Related-node (highlight-flip) queries — Left now reads referencedNodes
    // (what the Used-by partner uses), Right reads referencingNodes (who uses
    // the Uses partner). See dependencies-details-anbindung.md, Regel 3.
    graphql.query("CrossReferenceExplorerCenterRelatedByLeft", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            nodes: { referencedNodes: { nodeIds: [] } },
          },
        },
      }),
    ),
    graphql.query("CrossReferenceExplorerCenterRelatedByRight", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            nodes: { referencingNodes: { nodeIds: [] } },
          },
        },
      }),
    ),
    // Dependencies Details body query — the derived cell selection always
    // fires this, including with the graph root as an endpoint (no recorded
    // fixture covers that combination). (root, beta) — the Used-by-beta
    // aggregate — returns one edge (alpha uses beta), so the partner list
    // assertion below has real data; every other combination returns an
    // empty set (Empty state b), which is all the other tests need from the
    // body.
    graphql.query("DependencyPartners", ({ variables }) => {
      const { sourceNodeId, targetNodeId } = variables as {
        sourceNodeId: string;
        targetNodeId: string;
      };
      if (sourceNodeId === ROOT_ID && targetNodeId === BETA_ID) {
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
                      id: "alpha-uses-beta",
                      sourceNode: {
                        id: ALPHA_ID,
                        text: ALPHA_FQN,
                        type: "java.package",
                      },
                      targetNode: {
                        id: BETA_ID,
                        text: BETA_FQN,
                        type: "java.package",
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

it("center click alone keeps the Dependencies Details pane empty", async () => {
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

  await expect.element(page.getByText("No selection")).toBeVisible();
  await expect
    .element(page.getByText("Everything that uses", { exact: true }))
    .not.toBeInTheDocument();
});

it("the Used by column's inspect button shows Everything that uses <center>", async () => {
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
    page.getByRole("button", { name: /^Inspect everything that uses / }),
  );

  // (root, beta) → "Everything that uses <beta>".
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

  // Partner list: alpha uses beta → one partner row "alpha" with count 1.
  const partnersList = page.getByTestId("dependency-partners-list");
  await expect.element(partnersList.getByText(ALPHA_FQN)).toBeVisible();
  await expect.poll(() => partnersList.element().textContent).toContain("1");
});

it("the Uses column's inspect button shows Everything <center> uses — the #0092 (C, root) case", async () => {
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

  // (beta, root) → "Everything <beta> uses".
  await expect.element(page.getByText("uses", { exact: true })).toBeVisible();
  await expect
    .poll(
      () =>
        page.getByText("uses", { exact: true }).element().closest("div")
          ?.textContent,
    )
    .toContain(BETA_FQN);
});

it("clicking a Used-by partner pivots to Everything <partner> uses", async () => {
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

  // Used-by partner alpha pivots to (alpha, root) → "Everything <alpha> uses".
  await expect.element(page.getByText("uses", { exact: true })).toBeVisible();
  await expect
    .poll(
      () =>
        page.getByText("uses", { exact: true }).element().closest("div")
          ?.textContent,
    )
    .toContain(ALPHA_FQN);
});

it("clicking a Uses partner pivots to Everything that uses <partner>", async () => {
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

  // Uses partner gamma pivots to (root, gamma) → "Everything that uses <gamma>".
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
    .toContain(GAMMA_FQN);
});

it("a partner click resets an active aggregate button (partner pivot takes precedence)", async () => {
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
    page.getByRole("button", { name: /^Inspect everything that uses / }),
  );
  await expect
    .element(page.getByText("Everything that uses", { exact: true }))
    .toBeVisible();

  const leftTree = page.getByLabelText("XrefLeft");
  await expect.poll(() => leftTree.getByText(ALPHA_FQN).element()).toBeTruthy();
  await userEvent.click(leftTree.getByText(ALPHA_FQN));

  // Aggregate (root, beta) is replaced by the partner pivot (alpha, root).
  await expect
    .element(page.getByText("Everything that uses", { exact: true }))
    .not.toBeInTheDocument();
  await expect.element(page.getByText("uses", { exact: true })).toBeVisible();
  await expect
    .poll(
      () =>
        page.getByText("uses", { exact: true }).element().closest("div")
          ?.textContent,
    )
    .toContain(ALPHA_FQN);
});

it("Inspect wins over an active partner pivot (aggregate takes over)", async () => {
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

  // Used-by partner alpha pivots to (alpha, root) → "Everything <alpha> uses".
  await expect.element(page.getByText("uses", { exact: true })).toBeVisible();
  await expect
    .poll(
      () =>
        page.getByText("uses", { exact: true }).element().closest("div")
          ?.textContent,
    )
    .toContain(ALPHA_FQN);

  await userEvent.click(
    page.getByRole("button", { name: /^Inspect everything that uses / }),
  );

  // Pivot (alpha, root) is replaced by the aggregate (root, beta).
  await expect
    .element(page.getByText("uses", { exact: true }))
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

it("the active Inspect button reflects the pinned aggregate (aria-pressed)", async () => {
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
    page.getByRole("button", { name: /^Inspect everything that uses / }),
  );

  // The clicked Left-Inspect button is pressed; the Right-Inspect is not.
  await expect
    .element(
      page.getByRole("button", {
        name: /^Inspect everything that uses /,
        pressed: true,
      }),
    )
    .toBeVisible();
  await expect
    .element(
      page.getByRole("button", {
        name: /^Inspect everything .+ uses$/,
        pressed: true,
      }),
    )
    .not.toBeInTheDocument();
});
