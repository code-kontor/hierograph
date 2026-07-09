import { expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";

import { CrossReferencesPage } from "@/cross-references/CrossReferencesPage";
import { DevPanel } from "@/dev-panel/DevPanel";
import { SelectionProvider, useSelection } from "@/selection/SelectionContext";
import { resolveNodeId } from "@/testing/nodeLookup";
import { renderWithQueryClient } from "@/testing/render";

// org.hg.fixture.basic.rel.source has a NodeDetail fixture entry — the
// DevPanel will fetch and display it when focusedId is set.
const NODE_FQN = "org.hg.fixture.basic.rel.source";
const NODE_ID = resolveNodeId(NODE_FQN);

// Simulates setting focusedId/focusedName from the "other route" (e.g.
// /dependencies) without actually mounting the full HierarchyTree interaction.
// Approach: fallback (plan §Schritt 6) — trigger button sets state directly on
// the shared provider; CrossReferencesPage and the DevPanel mounted alongside
// it read the same context instance and must reflect the focused node.
function SetFocusedNodeButton() {
  const { setFocusedId, setFocusedName } = useSelection();
  return (
    <button
      onClick={() => {
        setFocusedId(NODE_ID);
        setFocusedName(NODE_FQN);
      }}
    >
      focus-node
    </button>
  );
}

it("selection survives route switch — focusedId visible in CrossReferencesPage inspector", async () => {
  // Both the trigger and CrossReferencesPage share one SelectionProvider,
  // mirroring the app's root-level SelectionProvider after the hoist in
  // __root.tsx. This proves that the DevPanel mounted alongside
  // CrossReferencesPage reads from the same context instance.
  await renderWithQueryClient(
    <SelectionProvider>
      <SetFocusedNodeButton />
      <CrossReferencesPage />
      <DevPanel />
    </SelectionProvider>,
  );

  await userEvent.click(page.getByText("focus-node"));

  // DevPanel (DEV-only, visible in Vitest browser mode) fetches
  // NodeDetail for focusedId and displays node.text. Polling because the query
  // is async.
  const widget = page.getByLabelText("DevPanel");
  await expect
    .poll(() => widget.getByText(NODE_FQN).first().element())
    .toBeTruthy();
});
