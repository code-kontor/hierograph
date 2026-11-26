import { describe, expect, it } from "vitest";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
import dependencySet from "@/testing/fixtures/DependencySet.json";
import dependencySetChildren from "@/testing/fixtures/DependencySetChildren.json";
import { renderWithRouter } from "@/testing/render";

import { StartPage } from "./StartPage";

const SCREENS = [
  { path: "/", label: "Dependencies", needsGraph: false },
] as unknown as Screen[];

// The id of a recorded selection, by its qualified name.
function idOf(text: string): string {
  const entry = dependencySet.entries.find(
    (candidate) => candidate.data.hierarchicalGraph.node.text === text,
  );
  if (!entry) throw new Error(`No recorded selection ${text}`);
  return entry.variables.id;
}

// The id of a node in the recorded dependency trees, by its qualified name.
function treeIdOf(text: string): string {
  const pattern = new RegExp(
    `"id":\\s*"(\\d+)",\\s*"text":\\s*"${text.replaceAll(".", "\\.")}"`,
  );
  const found = JSON.stringify(dependencySetChildren).match(pattern);
  if (!found) throw new Error(`No recorded tree node ${text}`);
  return found[1];
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

// The three trees and the list's two tabs in the pane below.
function pane(screen: StartRender) {
  return {
    usedBy: screen.getByRole("region", { name: "Used by" }),
    selection: screen.getByRole("region", { name: "Selection" }),
    uses: screen.getByRole("region", { name: "Uses" }),
    incoming: screen.getByRole("tabpanel", { name: "Incoming dependencies" }),
    outgoing: screen.getByRole("tabpanel", { name: "Outgoing dependencies" }),
  };
}

const WEB = "org.hg.fixture.locations.app.web";

// Opens the users of `lib` down to its web controllers.
async function openWeb(usedBy: ReturnType<typeof pane>["usedBy"]) {
  await usedBy.getByRole("button", { name: "Expand fixture-app" }).click();
  await usedBy.getByRole("button", { name: "Expand org.hg.fixture" }).click();
  await usedBy.getByRole("button", { name: "Expand locations" }).click();
  await usedBy.getByRole("button", { name: "Expand app" }).click();
  await usedBy.getByRole("button", { name: "Expand web" }).click();
}

describe("DependencyTrees", () => {
  it("shows what uses the selection, the selection reduced to its dependencies, and what it uses", async () => {
    const screen = await renderStart(`?selected_id=${idOf(LIB)}`);
    const { usedBy, selection, uses } = pane(screen);

    // The selection, open, with all its children.
    await expect
      .element(selection.getByRole("treeitem", { name: LIB }))
      .toHaveAttribute("aria-expanded", "true");
    const children = selection
      .getByRole("group")
      .first()
      .getByRole("treeitem", { name: /^(audit|customer|order|report)$/ });
    await expect.element(children.first()).toBeVisible();
    expect(children.elements()).toHaveLength(4);

    // Opening a package loads its children.
    await selection.getByRole("button", { name: "Expand order" }).click();
    await expect
      .element(selection.getByRole("treeitem", { name: "OrderService" }))
      .toBeVisible();
    await expect
      .element(selection.getByRole("treeitem", { name: "detail" }))
      .toBeVisible();

    // The users in their hierarchy, folded as the hierarchy tree folds it.
    await usedBy.getByRole("button", { name: "Expand fixture-app" }).click();
    await usedBy.getByRole("button", { name: "Expand org.hg.fixture" }).click();
    await usedBy.getByRole("button", { name: "Expand locations" }).click();
    await usedBy.getByRole("button", { name: "Expand app" }).click();
    await expect
      .element(usedBy.getByRole("treeitem", { name: "batch.nightly" }))
      .toHaveAttribute(
        "aria-description",
        expect.stringContaining(
          "folded from org.hg.fixture.locations.app.batch",
        ),
      );
    await usedBy.getByRole("button", { name: "Expand web" }).click();
    await expect
      .element(usedBy.getByRole("treeitem", { name: "OrderController" }))
      .toBeVisible();

    // lib uses nothing outside itself.
    await expect
      .element(uses.getByText(`${LIB} depends on nothing outside itself.`))
      .toBeVisible();
  });

  it("shows what the selection uses on the right", async () => {
    const screen = await renderStart(`?selected_id=${idOf(APP)}`);
    const { selection, uses } = pane(screen);

    await expect
      .element(selection.getByRole("treeitem", { name: APP }))
      .toHaveAttribute("aria-expanded", "true");
    await uses.getByRole("button", { name: "Expand fixture-app" }).click();
    await uses.getByRole("button", { name: "Expand org.hg.fixture" }).click();
    await uses.getByRole("button", { name: "Expand locations" }).click();
    await uses.getByRole("button", { name: "Expand lib" }).click();
    await expect
      .element(uses.getByRole("treeitem", { name: "order" }))
      .toBeVisible();
  });

  it("folds a chain of lone packages into one row, as the hierarchy tree does", async () => {
    const screen = await renderStart(`?selected_id=${idOf(MODULE)}`);
    const { selection } = pane(screen);

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

  it("says when the selection has no dependencies outside itself", async () => {
    const screen = await renderStart(`?selected_id=${idOf(ISOLATED)}`);

    await expect
      .element(
        screen.getByText(
          `${ISOLATED} has no dependencies with anything outside itself.`,
        ),
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
          "Select a single node in the hierarchy to see its dependencies.",
        ),
      )
      .toBeVisible();
  });

  it("lists the incoming and the outgoing dependencies below, a tab each, from source to target", async () => {
    const screen = await renderStart(`?selected_id=${idOf(LIB)}`);
    const { incoming, outgoing } = pane(screen);

    await expect
      .element(incoming.getByRole("heading"))
      .toHaveTextContent("13 dependencies");
    // Source, target, each with its package, and the references.
    const row = incoming.getByRole("row", {
      name: `${WEB}.OrderController → ${LIB}.order.OrderService`,
    });
    const cells = row.getByRole("cell");
    await expect
      .element(cells.nth(0))
      .toHaveTextContent(`OrderController${WEB}`);
    await expect
      .element(cells.nth(1))
      .toHaveTextContent(`OrderService${LIB}.order`);
    await expect.element(cells.nth(2)).toHaveTextContent("4");
    const length = screen.router.history.length;

    await screen.getByRole("tab", { name: "Outgoing dependencies" }).click();

    await expect
      .element(outgoing.getByRole("heading"))
      .toHaveTextContent("0 dependencies");
    await expect
      .poll(() => screen.router.state.location.search)
      .toHaveProperty("dependency_list", "outgoing");
    expect(screen.router.history.length).toBe(length);

    // The first tab is the default and leaves no parameter behind.
    await screen.getByRole("tab", { name: "Incoming dependencies" }).click();
    await expect
      .poll(() => screen.router.state.location.search)
      .not.toHaveProperty("dependency_list");
  });

  it("shows what a picked node connects to in the other trees and the lists", async () => {
    const screen = await renderStart(`?selected_id=${idOf(LIB)}`);
    const { usedBy, selection, incoming } = pane(screen);

    // A user: the list keeps only its dependencies.
    await openWeb(usedBy);
    const controller = usedBy.getByRole("treeitem", {
      name: "OrderController",
    });
    await controller.click();
    await expect.element(controller).toHaveAttribute("aria-selected", "true");
    await expect
      .element(incoming.getByRole("heading"))
      .toHaveTextContent("3 dependencies");
    await expect
      .element(
        incoming.getByRole("button", { name: "Drop used by OrderController" }),
      )
      .toBeVisible();
    await expect
      .poll(() => screen.router.state.location.search)
      .toHaveProperty("used_by_id");
    // The selection stays complete: the parts it uses are in bold.
    await expect
      .element(selection.getByRole("treeitem", { name: "order" }))
      .toHaveAttribute("data-connected", "true");
    await expect
      .element(selection.getByRole("treeitem", { name: "audit" }))
      .not.toHaveAttribute("data-connected");

    // And a part of the selection: the dependencies between the two.
    await selection.getByRole("button", { name: "Expand order" }).click();
    await selection
      .getByRole("treeitem", { name: "Order", exact: true })
      .click();
    await expect
      .element(incoming.getByRole("heading"))
      .toHaveTextContent("1 dependency");
    await expect
      .element(incoming.getByRole("button", { name: "Drop part Order" }))
      .toBeVisible();
    await expect
      .poll(() => screen.router.state.location.search)
      .toHaveProperty("selection_part_id");
    // "Used by" stays complete: what uses the part is in bold.
    await expect
      .element(usedBy.getByRole("treeitem", { name: "OrderController" }))
      .toHaveAttribute("data-connected", "true");
    await expect
      .element(usedBy.getByRole("treeitem", { name: "CustomerController" }))
      .not.toHaveAttribute("data-connected");

    // Picking the user again drops it: everything that uses Order.
    await controller.click();
    await expect
      .element(incoming.getByRole("heading"))
      .toHaveTextContent("3 dependencies");
    await expect
      .element(
        incoming.getByRole("button", { name: "Drop used by OrderController" }),
      )
      .not.toBeInTheDocument();
    await expect
      .element(usedBy.getByRole("treeitem", { name: "OrderController" }))
      .toHaveAttribute("aria-selected", "false");
  });

  it("drops a pick from its chip over the list", async () => {
    const order = treeIdOf(`${LIB}.order.Order`);
    const screen = await renderStart(
      `?selected_id=${idOf(LIB)}&selection_part_id=${order}`,
    );
    const { incoming } = pane(screen);

    await incoming.getByRole("button", { name: "Drop part Order" }).click();

    await expect
      .poll(() => screen.router.state.location.search)
      .not.toHaveProperty("selection_part_id");
    await expect
      .element(incoming.getByRole("heading"))
      .toHaveTextContent("13 dependencies");
  });

  it("opens a linked pick's tree down to it", async () => {
    const order = treeIdOf(`${LIB}.order.Order`);
    const screen = await renderStart(
      `?selected_id=${idOf(LIB)}&selection_part_id=${order}`,
    );
    const { selection } = pane(screen);

    await expect
      .element(selection.getByRole("treeitem", { name: "Order", exact: true }))
      .toHaveAttribute("aria-selected", "true");
    await expect
      .element(selection.getByRole("treeitem", { name: "order", exact: true }))
      .toHaveAttribute("aria-expanded", "true");
  });

  it("opens the list's tab of the side a node is picked on", async () => {
    const screen = await renderStart(`?selected_id=${idOf(APP)}`);
    const { uses } = pane(screen);
    await uses.getByRole("button", { name: "Expand fixture-app" }).click();
    await uses.getByRole("button", { name: "Expand org.hg.fixture" }).click();
    await uses.getByRole("button", { name: "Expand locations" }).click();
    await uses.getByRole("treeitem", { name: "lib" }).click();

    await expect
      .poll(() => screen.router.state.location.search)
      .toHaveProperty("dependency_list", "outgoing");
    await expect
      .element(screen.getByRole("tab", { name: "Outgoing dependencies" }))
      .toHaveAttribute("aria-selected", "true");
  });

  it("keeps one direction at a time: a pick on one side drops the other side's", async () => {
    const order = treeIdOf(`${LIB}.order.Order`);
    const screen = await renderStart(
      `?selected_id=${idOf(LIB)}&uses_id=${order}&selection_part_id=${order}`,
    );
    const { usedBy } = pane(screen);
    await openWeb(usedBy);

    await usedBy.getByRole("treeitem", { name: "OrderController" }).click();

    await expect
      .poll(() => screen.router.state.location.search)
      .toHaveProperty("used_by_id");
    const search = screen.router.state.location.search;
    expect(search).not.toHaveProperty("uses_id");
    // The part belongs to both directions and stays.
    expect(search).toHaveProperty("selection_part_id");
  });

  it("orders each level as the hierarchy tree does, External Types last below a separator", async () => {
    const screen = await renderStart(`?selected_id=${idOf(APP)}`);
    const { uses } = pane(screen);

    // The module first, External Types after it, separated.
    const top = uses.getByRole("treeitem", {
      name: /^(fixture-app|External Types)$/,
    });
    await expect.element(top.nth(1)).toBeVisible();
    expect(top.elements().map((row) => row.getAttribute("aria-label"))).toEqual(
      ["fixture-app", "External Types"],
    );
    await expect.element(top.nth(1)).toHaveClass("border-t");
    await expect.element(top.nth(0)).not.toHaveClass("border-t");
  });

  it("orders the selection's parts in dependency order, not by name", async () => {
    const screen = await renderStart(`?selected_id=${idOf(LIB)}`);
    const { selection } = pane(screen);

    const parts = selection
      .getByRole("group")
      .first()
      .getByRole("treeitem", { name: /^(audit|customer|order|report)$/ });
    await expect.element(parts.nth(3)).toBeVisible();
    expect(
      parts.elements().map((row) => row.getAttribute("aria-label")),
    ).toEqual(["report", "order", "customer", "audit"]);
  });

  it("shows all of the selection, parts without dependencies too", async () => {
    const screen = await renderStart(`?selected_id=${idOf(MODULE)}`);
    const { selection } = pane(screen);

    await selection
      .getByRole("button", { name: "Expand org.hg.fixture" })
      .click();
    await selection.getByRole("button", { name: "Expand basic" }).click();

    // `isolated` has no dependency outside itself, and is there anyway.
    await expect
      .element(selection.getByRole("treeitem", { name: "isolated" }))
      .toBeVisible();
  });

  it("says under each title what its tree holds, and in which direction", async () => {
    const screen = await renderStart(`?selected_id=${idOf(LIB)}`);
    const { usedBy, selection, uses } = pane(screen);

    await expect.element(usedBy.getByText(`depends on ${LIB}`)).toBeVisible();
    await expect
      .element(selection.getByText(`${LIB}, all of it`))
      .toBeVisible();
    await expect.element(uses.getByText(`${LIB} depends on it`)).toBeVisible();
    await expect
      .element(usedBy)
      .toHaveAttribute(
        "aria-description",
        `The nodes outside ${LIB} whose types depend on types in ${LIB}.`,
      );
  });

  it("names the selection in the pane's title and tints the sides, so they aren't taken for the hierarchy", async () => {
    const screen = await renderStart(`?selected_id=${idOf(LIB)}`);
    const { usedBy, selection, uses } = pane(screen);

    await expect
      .element(screen.getByRole("heading", { name: `Dependencies of ${LIB}` }))
      .toBeVisible();
    await expect.element(usedBy).toHaveAttribute("data-tinted", "true");
    await expect.element(uses).toHaveAttribute("data-tinted", "true");
    await expect.element(selection).not.toHaveAttribute("data-tinted");
  });

  it("counts next to each row of the sides how many of the selection's dependencies it holds", async () => {
    const screen = await renderStart(`?selected_id=${idOf(LIB)}`);
    const { usedBy, selection } = pane(screen);

    // All 13 of lib's incoming dependencies come from the module.
    const module = usedBy.getByRole("treeitem", { name: "fixture-app" });
    await expect.element(module).toHaveTextContent(/13$/);
    await expect
      .element(module)
      .toHaveAttribute(
        "aria-description",
        expect.stringMatching(/^13 dependencies, \d+ references/),
      );
    // The selection is the hierarchy's: no counts.
    await expect
      .element(selection.getByRole("treeitem", { name: "report" }))
      .not.toHaveAttribute("aria-description");
  });
});
