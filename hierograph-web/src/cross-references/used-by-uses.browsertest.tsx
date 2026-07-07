import { graphql, HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { CrossReferencesPage } from "@/cross-references/CrossReferencesPage";
import { SelectionProvider, useSelection } from "@/selection/SelectionContext";
import { worker } from "@/testing/msw/worker";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

// Cycle package FQNs — alpha→beta=1, beta→gamma=1, gamma→alpha=1 (EXPECTED_VALUES §3)
const ALPHA_FQN = "org.hg.fixture.basic.cycle.alpha";
const BETA_FQN = "org.hg.fixture.basic.cycle.beta";
const GAMMA_FQN = "org.hg.fixture.basic.cycle.gamma";

const ALPHA_ID = resolveNodeId(ALPHA_FQN);
const BETA_ID = resolveNodeId(BETA_FQN);
const GAMMA_ID = resolveNodeId(GAMMA_FQN);

// Sets beta as the focused subject, mirroring how the HierarchyTree sets focusedId.
function SetBetaButton() {
  const { setFocusedId, setFocusedName } = useSelection();
  return (
    <button
      onClick={() => {
        setFocusedId(BETA_ID);
        setFocusedName(BETA_FQN);
      }}
    >
      set-beta
    </button>
  );
}

// Simplified MSW responses: return alpha/gamma as direct children of the root
// request, and terminate with an empty second level for the partner itself.
// This tests the rendering logic (weight badge) without requiring a running
// backend to traverse the full hierarchy, and gives expandAllFolders'
// unbounded BFS (no visited-set) a terminating tree instead of looping
// forever on a self-referential parent.
// TECH-ISSUES: live traversal semantics of dependenciesTo/From for package
// nodes were not verified against a real store — see plan §E3, §TECH-ISSUES.
beforeEach(() => {
  worker.use(
    graphql.query("CrossReferencesUsedBy", ({ variables }) => {
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
                    hasChildren: true,
                    dependenciesTo: [{ weight: 1 }],
                  },
                ],
              },
            },
          },
        },
      });
    }),
    graphql.query("CrossReferencesUses", ({ variables }) => {
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
                    hasChildren: true,
                    dependenciesFrom: [{ weight: 1 }],
                  },
                ],
              },
            },
          },
        },
      });
    }),
    graphql.query("CrossReferencesNodePredecessors", ({ variables }) =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: { id: (variables as { id: string }).id, predecessors: [] },
          },
        },
      }),
    ),
  );
});

it("empty state — both panes show empty message when no subject is focused", async () => {
  await renderWithQueryClient(
    <SelectionProvider>
      <CrossReferencesPage />
    </SelectionProvider>,
  );

  await expect
    .poll(() =>
      page
        .getByText("Select a node in the hierarchy to see who depends on it.")
        .element(),
    )
    .toBeTruthy();
  await expect
    .poll(() =>
      page
        .getByText("Select a node in the hierarchy to see what it depends on.")
        .element(),
    )
    .toBeTruthy();
});

it("populated — Used by shows alpha with weight 1, Uses shows gamma with weight 1", async () => {
  await renderWithQueryClient(
    <SelectionProvider>
      <SetBetaButton />
      <CrossReferencesPage />
    </SelectionProvider>,
  );

  await userEvent.click(page.getByText("set-beta"));

  // Used by tree: alpha visible with weight badge "1"
  const usedByTree = page.getByLabelText("CrossReferencesUsedByTree");
  await expect
    .poll(() => usedByTree.getByText(ALPHA_FQN).element())
    .toBeTruthy();
  await expect.poll(() => usedByTree.getByText("1").element()).toBeTruthy();

  // Uses tree: gamma visible with weight badge "1"
  const usesTree = page.getByLabelText("CrossReferencesUsesTree");
  await expect.poll(() => usesTree.getByText(GAMMA_FQN).element()).toBeTruthy();
  await expect.poll(() => usesTree.getByText("1").element()).toBeTruthy();

  // Legend and subject-naming sub-header sit outside the tree containers.
  await expect
    .poll(() =>
      page.getByText("# = dependency weight · → = set as subject").elements(),
    )
    .toHaveLength(2);
  await expect
    .poll(() =>
      page.getByText(`Used by ${BETA_FQN}`, { exact: false }).element(),
    )
    .toBeTruthy();
  await expect
    .poll(() =>
      page.getByText("Cross references for:", { exact: false }).element(),
    )
    .toBeTruthy();
});

it("auto-expands nested partner children on load", async () => {
  worker.use(
    graphql.query("CrossReferencesUsedBy", ({ variables }) => {
      const { parentNode } = variables as { parentNode: string };
      if (parentNode === ALPHA_ID) {
        return HttpResponse.json({
          data: {
            hierarchicalGraph: {
              node: {
                childrenFilteredByReferencedNodes: {
                  nodes: [
                    {
                      id: `${ALPHA_ID}.sub`,
                      text: "alpha.sub",
                      type: "java.package",
                      hasChildren: false,
                      dependenciesTo: [{ weight: 1 }],
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
            node: {
              childrenFilteredByReferencedNodes: {
                nodes: [
                  {
                    id: ALPHA_ID,
                    text: ALPHA_FQN,
                    type: "java.package",
                    hasChildren: true,
                    dependenciesTo: [{ weight: 1 }],
                  },
                ],
              },
            },
          },
        },
      });
    }),
  );

  await renderWithQueryClient(
    <SelectionProvider>
      <SetBetaButton />
      <CrossReferencesPage />
    </SelectionProvider>,
  );

  await userEvent.click(page.getByText("set-beta"));

  const usedByTree = page.getByLabelText("CrossReferencesUsedByTree");
  await expect
    .poll(() => usedByTree.getByText("alpha.sub", { exact: true }).element())
    .toBeTruthy();
});
