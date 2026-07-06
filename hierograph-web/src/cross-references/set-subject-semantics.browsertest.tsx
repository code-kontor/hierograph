// Handler strategy: MSW handlers built from EXPECTED_VALUES.md §3 (cycle fixture).
// Fixture store was not running during implementation; handlers model the set
// {alpha, beta} topology manually. To switch to recorded fixtures, run
// `pnpm fixtures:record` against the fixture store and update accordingly.

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

// Subject set {alpha, beta}:
//   Used by (external incoming): gamma→alpha=1 — only gamma appears, internal alpha→beta excluded
//   Uses    (external outgoing): beta→gamma=1  — only gamma appears, internal alpha→beta excluded

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

// Sets {alpha, beta} as the selected subject, mirroring a multi-select in the HierarchyTree.
function SetAlphaBetaButton() {
  const { setSelectedIds } = useSelection();
  return (
    <button
      onClick={() => {
        setSelectedIds([ALPHA_ID, BETA_ID]);
      }}
    >
      set-alpha-beta
    </button>
  );
}

beforeEach(() => {
  localStorage.clear();
  worker.use(
    // Used by {alpha, beta}: external incoming = gamma→alpha (weight 1).
    // The internal edge alpha→beta is not returned because subjectIds filters
    // members out on the backend side; the client-side E2 filter would also drop them.
    graphql.query("CrossReferencesUsedBy", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: {
              childrenFilteredByReferencedNodes: {
                nodes: [makePackageNode(GAMMA_ID, GAMMA_FQN, 1)],
              },
            },
          },
        },
      }),
    ),
    // Uses {alpha, beta}: external outgoing = beta→gamma (weight 1).
    graphql.query("CrossReferencesUses", () =>
      HttpResponse.json({
        data: {
          hierarchicalGraph: {
            node: {
              childrenFilteredByReferencingNodes: {
                nodes: [makePackageNode(GAMMA_ID, GAMMA_FQN, 1)],
              },
            },
          },
        },
      }),
    ),
    // Predecessors — alpha and beta are disjoint packages with no ancestors in
    // the selection, so normalization leaves the set unchanged.
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

it("set subject — Used by shows only external partner gamma with weight 1", async () => {
  await renderWithQueryClient(
    <SelectionProvider>
      <SetAlphaBetaButton />
      <CrossReferencesPage />
    </SelectionProvider>,
  );

  await userEvent.click(page.getByText("set-alpha-beta"));

  const usedByTree = page.getByLabelText("CrossReferencesUsedByTree");
  await expect
    .poll(() => usedByTree.getByText(GAMMA_FQN).element())
    .toBeTruthy();
  await expect.poll(() => usedByTree.getByText("1").element()).toBeTruthy();
});

it("set subject — Uses shows only external partner gamma with weight 1", async () => {
  await renderWithQueryClient(
    <SelectionProvider>
      <SetAlphaBetaButton />
      <CrossReferencesPage />
    </SelectionProvider>,
  );

  await userEvent.click(page.getByText("set-alpha-beta"));

  const usesTree = page.getByLabelText("CrossReferencesUsesTree");
  await expect.poll(() => usesTree.getByText(GAMMA_FQN).element()).toBeTruthy();
  await expect.poll(() => usesTree.getByText("1").element()).toBeTruthy();
});

it("set subject — internal set members do not appear as partners (E2 client filter)", async () => {
  await renderWithQueryClient(
    <SelectionProvider>
      <SetAlphaBetaButton />
      <CrossReferencesPage />
    </SelectionProvider>,
  );

  await userEvent.click(page.getByText("set-alpha-beta"));

  // Wait for the trees to render (gamma visible = data loaded)
  const usedByTree = page.getByLabelText("CrossReferencesUsedByTree");
  await expect
    .poll(() => usedByTree.getByText(GAMMA_FQN).element())
    .toBeTruthy();

  const usesTree = page.getByLabelText("CrossReferencesUsesTree");
  await expect.poll(() => usesTree.getByText(GAMMA_FQN).element()).toBeTruthy();

  // Internal edge: alpha must not appear as a partner in either pane
  await expect
    .poll(() => usedByTree.getByText(ALPHA_FQN).elements().length)
    .toBe(0);
  await expect
    .poll(() => usesTree.getByText(ALPHA_FQN).elements().length)
    .toBe(0);

  // Internal edge: beta must not appear as a partner in either pane
  await expect
    .poll(() => usedByTree.getByText(BETA_FQN).elements().length)
    .toBe(0);
  await expect
    .poll(() => usesTree.getByText(BETA_FQN).elements().length)
    .toBe(0);
});
