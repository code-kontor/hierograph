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

// Used-by X = nodes depending on X:  alpha←gamma, beta←alpha, gamma←beta
// Uses X    = nodes X depends on:    alpha→beta,  beta→gamma,  gamma→alpha
function makePackageNode(
  id: string,
  text: string,
  weight: number,
): {
  id: string;
  text: string;
  type: string;
  hasChildren: boolean;
  dependenciesTo: { weight: number }[];
  dependenciesFrom: { weight: number }[];
} {
  return {
    id,
    text,
    type: "java.package",
    hasChildren: false,
    dependenciesTo: [{ weight }],
    dependenciesFrom: [{ weight }],
  };
}

// Sets beta as the focused subject, mirroring how HierarchyTree sets focusedId.
function SetBetaButton() {
  const { setFocusedId, setFocusedName } = useSelection();
  return (
    <button
      onClick={() => {
        setFocusedId(BETA_ID);
        setFocusedName("beta");
      }}
    >
      set-beta
    </button>
  );
}

// Variable-aware MSW handlers that model the cycle topology.
beforeEach(() => {
  // Reset panel position so DevPanel doesn't overlap tree rows.
  localStorage.clear();
  worker.use(
    graphql.query("CrossReferencesUsedBy", ({ variables }) => {
      const subjectId = (variables as { subjectIds: string[] }).subjectIds[0];
      let node = null;
      if (subjectId === ALPHA_ID)
        node = makePackageNode(GAMMA_ID, GAMMA_FQN, 1);
      else if (subjectId === BETA_ID)
        node = makePackageNode(ALPHA_ID, ALPHA_FQN, 1);
      else if (subjectId === GAMMA_ID)
        node = makePackageNode(BETA_ID, BETA_FQN, 1);
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: {
              childrenFilteredByReferencedNodes: { nodes: node ? [node] : [] },
            },
          },
        },
      });
    }),
    graphql.query("CrossReferencesUses", ({ variables }) => {
      const subjectId = (variables as { subjectIds: string[] }).subjectIds[0];
      let node = null;
      if (subjectId === ALPHA_ID) node = makePackageNode(BETA_ID, BETA_FQN, 1);
      else if (subjectId === BETA_ID)
        node = makePackageNode(GAMMA_ID, GAMMA_FQN, 1);
      else if (subjectId === GAMMA_ID)
        node = makePackageNode(ALPHA_ID, ALPHA_FQN, 1);
      return HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: {
              childrenFilteredByReferencingNodes: { nodes: node ? [node] : [] },
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
    // Minimal response so the Inspector's Usages tab doesn't hit an unhandled request.
    graphql.query("DependencyEdges", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            dependencySetForAggregatedDependency: {
              dependencyPage: {
                pageInfo: {
                  pageNumber: 0,
                  maxPages: 1,
                  pageSize: 20,
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

const INSPECTOR_EMPTY_TEXT =
  "Pick a dependency cell in the matrix to inspect its usages and paths.";

// CrossReferencesPage uses TwoOneSplitLayout which requires an explicit height
// parent so react-resizable-panels can distribute space. Without it, all panels
// collapse to 0 height — elements are in the DOM but clicks fail.
const PAGE_WRAPPER_STYLE = { height: "600px" };

it("(a) inspect handoff — row click sets cell and clears Inspector empty state", async () => {
  await renderWithQueryClient(
    <div style={PAGE_WRAPPER_STYLE}>
      <SelectionProvider>
        <SetBetaButton />
        <CrossReferencesPage />
      </SelectionProvider>
    </div>,
  );

  await userEvent.click(page.getByText("set-beta"));

  const usedByTree = page.getByLabelText("CrossReferencesUsedByTree");
  await expect
    .poll(() => usedByTree.getByText(ALPHA_FQN).element())
    .toBeTruthy();

  // Inspector starts in empty state
  await expect
    .poll(() => page.getByText(INSPECTOR_EMPTY_TEXT).element())
    .toBeTruthy();

  // Click the row text (not the "→" button) — triggers onFocusedIdChange handler
  await userEvent.click(usedByTree.getByText(ALPHA_FQN));

  // Inspector leaves empty state: cell (alpha → beta) was handed off
  await expect
    .poll(() => page.getByText(INSPECTOR_EMPTY_TEXT).elements().length)
    .toBe(0);

  // Subject is still beta: "Used by" still shows alpha (no subject change)
  await expect
    .poll(() => usedByTree.getByText(ALPHA_FQN).element())
    .toBeTruthy();
});

it("(b) promote — button click changes subject and resets Inspector cell (E3, E4)", async () => {
  await renderWithQueryClient(
    <div style={PAGE_WRAPPER_STYLE}>
      <SelectionProvider>
        <SetBetaButton />
        <CrossReferencesPage />
      </SelectionProvider>
    </div>,
  );

  await userEvent.click(page.getByText("set-beta"));

  const usedByTree = page.getByLabelText("CrossReferencesUsedByTree");
  await expect
    .poll(() => usedByTree.getByText(ALPHA_FQN).element())
    .toBeTruthy();

  // Set a cell via row click so Inspector is not in empty state
  await userEvent.click(usedByTree.getByText(ALPHA_FQN));
  await expect
    .poll(() => page.getByText(INSPECTOR_EMPTY_TEXT).elements().length)
    .toBe(0);

  // Click the "→ as subject" button — stopPropagation prevents an extra row-click
  const promoteBtn = usedByTree.getByRole("button", { name: "Set as subject" });
  await userEvent.click(promoteBtn);

  // E4: subjectId changed → cellSelection reset → Inspector back to empty state
  await expect
    .poll(() => page.getByText(INSPECTOR_EMPTY_TEXT).element())
    .toBeTruthy();

  // New subject is alpha — "Used by" now shows gamma (gamma→alpha=1)
  await expect
    .poll(() => usedByTree.getByText(GAMMA_FQN).element())
    .toBeTruthy();
});

it("(c) 2-step chain — beta → promote gamma from Uses → gamma's Used-by shows beta", async () => {
  await renderWithQueryClient(
    <div style={PAGE_WRAPPER_STYLE}>
      <SelectionProvider>
        <SetBetaButton />
        <CrossReferencesPage />
      </SelectionProvider>
    </div>,
  );

  await userEvent.click(page.getByText("set-beta"));

  // Step 1: beta subject — Uses shows gamma
  const usesTree = page.getByLabelText("CrossReferencesUsesTree");
  await expect.poll(() => usesTree.getByText(GAMMA_FQN).element()).toBeTruthy();

  // Promote gamma to subject via the "→" button in the Uses pane
  const promoteBtn = usesTree.getByRole("button", { name: "Set as subject" });
  await userEvent.click(promoteBtn);

  // Step 2: gamma is now subject — "Used by" shows beta (beta→gamma=1)
  const usedByTree = page.getByLabelText("CrossReferencesUsedByTree");
  await expect
    .poll(() => usedByTree.getByText(BETA_FQN).element())
    .toBeTruthy();
});
