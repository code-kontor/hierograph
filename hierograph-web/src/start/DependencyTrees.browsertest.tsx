import { describe, expect, it } from "vitest";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
import dependencyEnds from "@/testing/fixtures/DependencyEnds.json";
import { renderWithRouter } from "@/testing/render";

import { StartPage } from "./StartPage";

const SCREENS = [
  { path: "/", label: "Start", needsGraph: false },
] as unknown as Screen[];

// The id of a recorded selection, by its qualified name.
function idOf(text: string): string {
  const entry = dependencyEnds.entries.find(
    (candidate) => candidate.data.hierarchicalGraph.node.text === text,
  );
  if (!entry) throw new Error(`No recorded selection ${text}`);
  return entry.variables.id;
}

const LIB = "org.hg.fixture.locations.lib";
const APP = "org.hg.fixture.locations.app";
const ISOLATED = "org.hg.fixture.basic.isolated";
const MODULE = "io.hierograph.examples:fixture-app:1.0.0";

function renderStart(search: string) {
  return renderWithRouter(
    <AppFrame screens={SCREENS}>
      <StartPage />
    </AppFrame>,
    "/",
    search,
  );
}

type StartRender = Awaited<ReturnType<typeof renderStart>>;

// The tab's two trees: the selection's, and the one on the other end.
function trees(screen: StartRender, tab: string, other: string) {
  const panel = screen.getByRole("tabpanel", { name: tab });
  return {
    selection: panel.getByRole("region", { name: "Selection" }),
    other: panel.getByRole("region", { name: other }),
  };
}

describe("DependencyTrees", () => {
  it("shows the selection reduced to what is used, and what uses it in its hierarchy", async () => {
    const screen = await renderStart(`?selected_id=${idOf(LIB)}`);
    const { selection, other } = trees(
      screen,
      "Incoming dependencies",
      "Used by",
    );

    // The selection, open, with only the children something outside uses.
    await expect
      .element(selection.getByRole("treeitem", { name: LIB }))
      .toHaveAttribute("aria-expanded", "true");
    const children = selection
      .getByRole("group")
      .first()
      .getByRole("treeitem", { name: /^(audit|customer|order|report)$/ });
    await expect.element(children.first()).toBeVisible();
    expect(children.elements()).toHaveLength(4);

    // Opening a package loads its used children.
    await selection.getByRole("button", { name: "Expand order" }).click();
    await expect
      .element(selection.getByRole("treeitem", { name: "OrderService" }))
      .toBeVisible();
    await expect
      .element(selection.getByRole("treeitem", { name: "detail" }))
      .toBeVisible();

    // The users in their hierarchy, folded as the hierarchy tree folds it.
    await other.getByRole("button", { name: "Expand fixture-app" }).click();
    await other.getByRole("button", { name: "Expand org.hg.fixture" }).click();
    await other.getByRole("button", { name: "Expand locations" }).click();
    await other.getByRole("button", { name: "Expand app" }).click();
    await expect
      .element(other.getByRole("treeitem", { name: "batch.nightly" }))
      .toHaveAttribute(
        "aria-description",
        "folded from org.hg.fixture.locations.app.batch",
      );
    await other.getByRole("button", { name: "Expand web" }).click();
    await expect
      .element(other.getByRole("treeitem", { name: "OrderController" }))
      .toBeVisible();
  });

  it("shows what the selection uses on the outgoing tab", async () => {
    const screen = await renderStart(
      `?selected_id=${idOf(APP)}&dependencies=outgoing`,
    );
    const { selection, other } = trees(screen, "Outgoing dependencies", "Uses");

    await expect
      .element(selection.getByRole("treeitem", { name: APP }))
      .toHaveAttribute("aria-expanded", "true");
    await other.getByRole("button", { name: "Expand fixture-app" }).click();
    await other.getByRole("button", { name: "Expand org.hg.fixture" }).click();
    await other.getByRole("button", { name: "Expand locations" }).click();
    await other.getByRole("button", { name: "Expand lib" }).click();
    await expect
      .element(other.getByRole("treeitem", { name: "order" }))
      .toBeVisible();
  });

  it("folds a chain of lone packages into one row, as the hierarchy tree does", async () => {
    const screen = await renderStart(
      `?selected_id=${idOf(MODULE)}&dependencies=outgoing`,
    );
    const { selection } = trees(screen, "Outgoing dependencies", "Uses");

    // org › org.hg › org.hg.fixture: one row, which names what it stands for.
    const folded = selection.getByRole("treeitem", { name: "org.hg.fixture" });
    await expect
      .element(folded)
      .toHaveAttribute("aria-description", "folded from org, org.hg");
    await expect
      .element(selection.getByRole("treeitem", { name: "org", exact: true }))
      .not.toBeInTheDocument();
    await expect
      .element(folded.getByText("org.hg.fixture", { exact: true }))
      .toHaveAttribute("title", "org.hg.fixture\nfolded: org, org.hg");

    await selection
      .getByRole("button", { name: "Expand org.hg.fixture" })
      .click();
    await expect
      .element(selection.getByRole("treeitem", { name: "basic" }))
      .toBeVisible();
    await expect
      .element(selection.getByRole("treeitem", { name: "locations" }))
      .toBeVisible();
  });

  it("says when nothing outside the selection depends on it, or it on nothing", async () => {
    const screen = await renderStart(`?selected_id=${idOf(ISOLATED)}`);

    await expect
      .element(screen.getByText(`Nothing outside ${ISOLATED} depends on it.`))
      .toBeVisible();

    await screen.getByRole("tab", { name: "Outgoing dependencies" }).click();

    await expect
      .element(
        screen.getByText(`${ISOLATED} depends on nothing outside itself.`),
      )
      .toBeVisible();
  });

  it("asks for a single node when a cycle is selected", async () => {
    const screen = await renderStart(
      `?selected_cycle_ids=${idOf(LIB)},${idOf(APP)}`,
    );

    await expect
      .element(
        screen.getByText(
          "Select a single node in the hierarchy to see what depends on it.",
        ),
      )
      .toBeVisible();
  });
});
