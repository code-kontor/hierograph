import { graphql, HttpResponse } from "msw";
import { describe, expect, it, onTestFinished, vi } from "vitest";
import { userEvent } from "vitest/browser";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
import dependencySet from "@/testing/fixtures/DependencySet.json";
import dependencySetChildren from "@/testing/fixtures/DependencySetChildren.json";
import { worker } from "@/testing/msw/worker";
import { renderWithRouter } from "@/testing/render";

import { StartPage } from "./StartPage";

const SCREENS = [
  { path: "/", label: "Dependencies", needsGraph: false },
] as unknown as Screen[];

// The id of a recorded selection, by its qualified name.
function idOf(text: string): string {
  const entry = dependencySet.entries.find(
    (candidate) =>
      candidate.variables.ids.length === 1 &&
      candidate.data.hierarchicalGraph.nodes.nodes[0].text === text,
  );
  if (!entry) throw new Error(`No recorded selection ${text}`);
  return entry.variables.ids[0];
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

// In a run of rows with the same end (0: source, 1: target), only the
// first names it; every row's name keeps both ends. Some end has a run.
function expectRuns(rows: Element[], end: 0 | 1) {
  const cell = (row: Element) =>
    row.querySelectorAll("td")[end]?.textContent ?? "";
  let previous: string | undefined;
  for (const row of rows) {
    const name = (row.getAttribute("aria-label") ?? "").split(" → ")[end];
    if (name === previous) expect(cell(row)).toBe("");
    else expect(cell(row)).not.toBe("");
    previous = name;
  }
  expect(rows.some((row) => cell(row) === "")).toBe(true);
}

// Opens an element's context menu, as a right click does.
function rightClick(element: Element) {
  element.dispatchEvent(
    new MouseEvent("contextmenu", {
      bubbles: true,
      cancelable: true,
      clientX: 10,
      clientY: 10,
    }),
  );
}

// Opens the Copy submenu of the context menu open now, and clicks its entry
// `name`; returns the entry's preview. A click on the element: moving the
// pointer there from the trigger would leave the submenu.
async function copyEntry(screen: StartRender, name: string) {
  await screen.getByRole("menuitem", { name: "Copy" }).click();
  const entry = screen
    .getByRole("menu", { name: "Copy" })
    .getByRole("menuitem", { name, exact: true });
  await expect.element(entry).toBeVisible();
  const preview = entry.element().getAttribute("aria-description");
  (entry.element() as HTMLElement).click();
  return preview;
}

describe("DependencyTrees", () => {
  it("shows what uses the selection, the selection reduced to its dependencies, and what it uses", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);
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
    const screen = await renderStart(`?selected_ids=${idOf(APP)}`);
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
    const screen = await renderStart(`?selected_ids=${idOf(MODULE)}`);
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
    const screen = await renderStart(`?selected_ids=${idOf(ISOLATED)}`);

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
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);
    const { incoming, outgoing } = pane(screen);

    await expect
      .element(incoming.getByRole("heading"))
      .toHaveTextContent("13 dependencies · 25 references");
    // Source, target, each with its package, and the references.
    const row = incoming.getByRole("row", {
      name: `${WEB}.OrderController → ${LIB}.order.OrderService`,
    });
    const cells = row.getByRole("cell");
    // Each end as module:package.Class, then the kinds, the most frequent
    // first, and the references.
    await expect
      .element(cells.nth(0))
      .toHaveTextContent(`fixture-app:${WEB}.OrderController`);
    await expect
      .element(cells.nth(1))
      .toHaveTextContent(`fixture-app:${LIB}.order.OrderService`);
    await expect
      .element(cells.nth(2))
      .toHaveTextContent("calls 2, has type 1, instantiates 1");
    await expect.element(cells.nth(3)).toHaveTextContent("4");
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

  it("names the selection's type once for a run of its rows: incoming the target, in target order", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);
    const { incoming } = pane(screen);
    await expect
      .element(incoming.getByRole("heading"))
      .toHaveTextContent(/^13 dependencies/);

    const rows = incoming.getByRole("row").elements().slice(1);
    expectRuns(rows, 1);
    const targets = rows.map(
      (row) => (row.getAttribute("aria-label") ?? "").split(" → ")[1],
    );
    expect(targets).toEqual([...targets].sort((a, b) => a.localeCompare(b)));
  });

  it("names the selection's type once for a run of its rows: outgoing the source", async () => {
    const screen = await renderStart(
      `?selected_ids=${idOf(APP)}&dependency_list=outgoing`,
    );
    const { outgoing } = pane(screen);
    await expect
      .element(outgoing.getByRole("heading"))
      .toHaveTextContent(/dependencies/);
    await expect
      .poll(() => outgoing.getByRole("row").elements().length)
      .toBeGreaterThan(1);

    expectRuns(outgoing.getByRole("row").elements().slice(1), 0);
  });

  it("resizes the list's columns on the borders between their headings, remembering the widths", async () => {
    localStorage.clear();
    onTestFinished(() => localStorage.clear());
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);
    const { incoming } = pane(screen);
    const border = incoming.getByRole("separator", {
      name: "Resize Source and Target",
    });
    await expect.element(border).toHaveAttribute("aria-valuenow", "36");
    const widths = () =>
      Array.from(
        incoming.element().querySelectorAll<HTMLTableColElement>("col"),
      ).map((col) => col.style.width);

    border.element().focus();
    await userEvent.keyboard("{ArrowRight}{ArrowRight}");

    // The source wider, the target narrower by as much; the table as wide.
    await expect.poll(widths).toEqual(["40%", "32%", "18%", "10%"]);
    expect(
      JSON.parse(
        localStorage.getItem("hierograph:dependency-list-columns") ?? "[]",
      ),
    ).toEqual([40, 32, 18, 10]);

    // Without the stylesheet the border has no size to click on.
    border
      .element()
      .dispatchEvent(new MouseEvent("dblclick", { bubbles: true }));

    await expect.poll(widths).toEqual(["36%", "36%", "18%", "10%"]);
  });

  it("closes the selection in its tree, and keeps it closed in the URL until another selection", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);
    const { selection } = pane(screen);
    const top = selection.getByRole("treeitem", { name: LIB });
    await expect.element(top).toHaveAttribute("aria-expanded", "true");
    // The parts stay open below it, for when it opens again.
    await selection.getByRole("button", { name: "Expand order" }).click();

    await selection.getByRole("button", { name: `Collapse ${LIB}` }).click();

    await expect.element(top).toHaveAttribute("aria-expanded", "false");
    await expect
      .element(selection.getByRole("treeitem", { name: "order" }))
      .not.toBeInTheDocument();
    await expect
      .poll(() => screen.router.state.location.search)
      .toMatchObject({ selection_collapsed_ids: [idOf(LIB)] });

    await selection.getByRole("button", { name: `Expand ${LIB}` }).click();

    await expect
      .element(selection.getByRole("treeitem", { name: "OrderService" }))
      .toBeVisible();
    await expect
      .poll(() => screen.router.state.location.search)
      .not.toHaveProperty("selection_collapsed_ids");

    // Closed again, then another selection: that one opens.
    await selection.getByRole("button", { name: `Collapse ${LIB}` }).click();
    await expect
      .poll(() => screen.router.state.location.search)
      .toMatchObject({ selection_collapsed_ids: [idOf(LIB)] });
    await screen.getByRole("button", { name: "Clear selection" }).click();
    await expect
      .poll(() => screen.router.state.location.search)
      .not.toHaveProperty("selection_collapsed_ids");
  });

  it("opens with the selection closed when the link says so", async () => {
    const screen = await renderStart(
      `?selected_ids=${idOf(LIB)}&selection_collapsed_ids=${idOf(LIB)}`,
    );
    const { selection } = pane(screen);
    await expect
      .element(selection.getByRole("treeitem", { name: LIB }))
      .toHaveAttribute("aria-expanded", "false");
  });

  it("takes a group of selected nodes together: the dependencies crossing its border", async () => {
    const [app, lib] = [idOf(APP), idOf(LIB)];
    const screen = await renderStart(`?selected_ids=${app},${lib}`);
    const { usedBy, selection, uses } = pane(screen);

    await expect
      .element(screen.getByRole("heading", { name: "Dependencies of 2 nodes" }))
      .toBeVisible();
    // Both, open, in the order selected.
    const top = (name: string) =>
      selection.getByRole("treeitem", { name, exact: true });
    await expect.element(top(APP)).toHaveAttribute("aria-expanded", "true");
    await expect.element(top(LIB)).toHaveAttribute("aria-expanded", "true");
    expect(
      selection
        .getByRole("treeitem")
        .elements()
        .filter((row) => row.getAttribute("aria-level") === "1")
        .map((row) => row.getAttribute("aria-label")),
    ).toEqual([APP, LIB]);
    // app → lib is inside the group: nothing outside uses it, and only
    // app's one dependency outside goes out.
    await expect
      .element(
        usedBy.getByText("Nothing outside the selected nodes depends on them."),
      )
      .toBeVisible();
    await screen.getByRole("tab", { name: "Outgoing dependencies" }).click();
    await expect
      .element(
        screen
          .getByRole("region", { name: "Outgoing dependencies" })
          .getByText(/^1 dependency/),
      )
      .toBeVisible();

    // A used node marks the selected node it is reached from, not the other.
    const used = uses.getByRole("treeitem").first();
    await expect.element(used).toBeVisible();
    const usedName = used.element().getAttribute("aria-label") ?? "";
    await used.getByText(usedName, { exact: true }).click();
    await expect.element(top(APP)).toHaveAttribute("data-connected", "true");
    await expect.element(top(LIB)).not.toHaveAttribute("data-connected");
  });

  it("shows one direction at a time when asked, and both again once the selection is cleared", async () => {
    const user = treeIdOf(MODULE);
    const screen = await renderStart(
      `?selected_ids=${idOf(LIB)}&used_by_id=${user}`,
    );
    const directions = screen.getByRole("radiogroup", { name: "Directions" });
    await expect
      .element(directions.getByRole("radio", { name: "Both" }))
      .toHaveAttribute("aria-checked", "true");
    await expect.element(pane(screen).usedBy).toBeVisible();

    await directions.getByRole("radio", { name: "Outgoing" }).click();

    // The other side's tree and tab go; the list's one tab is a heading.
    await expect
      .element(screen.getByRole("region", { name: "Used by" }))
      .not.toBeInTheDocument();
    await expect.element(pane(screen).uses).toBeVisible();
    await expect
      .element(screen.getByRole("tab", { name: "Incoming dependencies" }))
      .not.toBeInTheDocument();
    await expect
      .element(screen.getByRole("heading", { name: "Outgoing dependencies" }))
      .toBeVisible();
    await expect
      .poll(() => screen.router.state.location.search)
      .toMatchObject({ dependency_directions: "outgoing" });
    // The hidden side's pick stays, for its return.
    expect(String(screen.router.state.location.search.used_by_id)).toBe(user);

    await directions.getByRole("radio", { name: "Incoming" }).click();

    await expect
      .element(screen.getByRole("region", { name: "Uses" }))
      .not.toBeInTheDocument();
    await expect.element(pane(screen).usedBy).toBeVisible();
    await expect
      .element(pane(screen).usedBy.getByRole("treeitem", { selected: true }))
      .toBeVisible();
    await expect
      .element(screen.getByRole("tab", { name: "Outgoing dependencies" }))
      .not.toBeInTheDocument();

    await screen.getByRole("button", { name: "Clear selection" }).click();

    await expect
      .poll(() => screen.router.state.location.search)
      .not.toHaveProperty("dependency_directions");
    await expect
      .element(directions.getByRole("radio", { name: "Both" }))
      .toHaveAttribute("aria-checked", "true");
  });

  it("moves between the directions with the arrow keys, one tab stop for the three", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);
    const directions = screen.getByRole("radiogroup", { name: "Directions" });
    const radio = (name: string) => directions.getByRole("radio", { name });
    await expect.element(radio("Both")).toHaveAttribute("tabindex", "0");
    await expect.element(radio("Incoming")).toHaveAttribute("tabindex", "-1");
    await expect.element(radio("Outgoing")).toHaveAttribute("tabindex", "-1");

    (radio("Both").element() as HTMLElement).focus();
    await userEvent.keyboard("{ArrowRight}");

    await expect
      .element(radio("Outgoing"))
      .toHaveAttribute("aria-checked", "true");
    await expect.element(radio("Outgoing")).toHaveFocus();
    await expect.element(radio("Outgoing")).toHaveAttribute("tabindex", "0");

    // Around the end, to the start.
    await userEvent.keyboard("{ArrowRight}");
    await expect
      .element(radio("Incoming"))
      .toHaveAttribute("aria-checked", "true");
    await userEvent.keyboard("{End}");
    await expect
      .element(radio("Outgoing"))
      .toHaveAttribute("aria-checked", "true");
  });

  it("offers the other direction when the one shown has no dependencies", async () => {
    // lib uses nothing outside itself, but is used.
    const screen = await renderStart(
      `?selected_ids=${idOf(LIB)}&dependency_directions=outgoing`,
    );
    const uses = screen.getByRole("region", { name: "Uses" });
    await expect
      .element(uses.getByText(`${LIB} depends on nothing outside itself.`))
      .toBeVisible();
    await expect
      .element(uses.getByText(/^\d+ incoming dependenc/))
      .toBeVisible();
    // The list, too.
    await expect
      .element(
        screen
          .getByRole("region", { name: "Outgoing dependencies" })
          .getByRole("button", { name: "Show the incoming dependencies" }),
      )
      .toBeVisible();

    await uses
      .getByRole("button", { name: "Show the incoming dependencies" })
      .click();

    await expect
      .poll(() => screen.router.state.location.search)
      .toMatchObject({ dependency_directions: "incoming" });
    await expect.element(pane(screen).usedBy).toBeVisible();
    // Incoming has some: nothing to offer.
    await expect
      .element(
        screen.getByRole("button", { name: /^Show the .* dependencies$/ }),
      )
      .not.toBeInTheDocument();
  });

  it("shows what a picked node connects to in the other trees and the lists", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);
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
      .element(incoming.getByRole("button", { name: "Drop selection Order" }))
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
      `?selected_ids=${idOf(LIB)}&selection_part_id=${order}`,
    );
    const { incoming } = pane(screen);

    await incoming
      .getByRole("button", { name: "Drop selection Order" })
      .click();

    await expect
      .poll(() => screen.router.state.location.search)
      .not.toHaveProperty("selection_part_id");
    await expect
      .element(incoming.getByRole("heading"))
      .toHaveTextContent("13 dependencies");
  });

  it("keeps each tree's open rows in the URL, so a link restores them", async () => {
    const module = treeIdOf(MODULE);
    const screen = await renderStart(`?selected_ids=${idOf(APP)}`);
    const { uses } = pane(screen);

    await uses.getByRole("button", { name: "Expand fixture-app" }).click();

    await expect
      .poll(() => screen.router.state.location.search)
      .toHaveProperty("uses_expanded_ids", [module]);
    await expect
      .element(uses.getByRole("treeitem", { name: "fixture-app" }))
      .toHaveAttribute("aria-expanded", "true");

    await uses.getByRole("button", { name: "Collapse fixture-app" }).click();

    await expect
      .poll(() => screen.router.state.location.search)
      .not.toHaveProperty("uses_expanded_ids");
  });

  it("opens the rows the URL names", async () => {
    const screen = await renderStart(
      `?selected_ids=${idOf(LIB)}&used_by_expanded_ids=${treeIdOf(MODULE)}`,
    );
    const { usedBy } = pane(screen);

    await expect
      .element(usedBy.getByRole("treeitem", { name: "fixture-app" }))
      .toHaveAttribute("aria-expanded", "true");
  });

  it("expands and collapses a row with everything in it from its menu", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(APP)}`);
    const { uses } = pane(screen);

    await uses
      .getByRole("treeitem", { name: "fixture-app" })
      .getByText("fixture-app", { exact: true })
      .click({ button: "right" });
    await screen.getByRole("menuitem", { name: "Expand all" }).click();

    // Opened down to the types.
    await expect
      .element(uses.getByRole("treeitem", { name: "order", exact: true }))
      .toHaveAttribute("aria-expanded", "true");
    await expect
      .poll(() => screen.router.state.location.search.uses_expanded_ids)
      .toContain(treeIdOf(MODULE));

    await uses
      .getByRole("treeitem", { name: "fixture-app" })
      .getByText("fixture-app", { exact: true })
      .click({ button: "right" });
    await screen.getByRole("menuitem", { name: "Collapse all" }).click();

    await expect
      .element(uses.getByRole("treeitem", { name: "fixture-app" }))
      .toHaveAttribute("aria-expanded", "false");
    await expect
      .poll(() => screen.router.state.location.search)
      .not.toHaveProperty("uses_expanded_ids");
  });

  it("makes a part the selection from the Selection tree's menu, and shows it in the hierarchy", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(MODULE)}`);
    const { selection } = pane(screen);
    await selection
      .getByRole("button", { name: "Expand org.hg.fixture" })
      .click();
    await selection.getByRole("button", { name: "Expand locations" }).click();

    await selection
      .getByRole("treeitem", { name: "lib", exact: true })
      .getByText("lib", { exact: true })
      .click({ button: "right" });
    await screen.getByRole("menuitem", { name: "Select" }).click();

    await expect
      .poll(() => String(screen.router.state.location.search.selected_ids))
      .toBe(idOf(LIB));
    // The hierarchy tree opens down to it and selects it.
    await expect
      .element(
        screen
          .getByRole("tree", { name: "Hierarchy" })
          .getByRole("treeitem", { name: "lib", exact: true }),
      )
      .toHaveAttribute("aria-selected", "true");
    await expect
      .element(pane(screen).selection.getByRole("treeitem", { name: LIB }))
      .toBeVisible();
    // The sides offer no Select.
    await pane(screen)
      .usedBy.getByRole("treeitem", { name: "fixture-app" })
      .getByText("fixture-app", { exact: true })
      .click({ button: "right" });
    await expect
      .element(screen.getByRole("menuitem", { name: "Expand all" }))
      .toBeVisible();
    await expect
      .element(screen.getByRole("menuitem", { name: "Select" }))
      .not.toBeInTheDocument();
  });

  it("offers no Select for the selection itself", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);
    const { selection } = pane(screen);

    await selection
      .getByRole("treeitem", { name: LIB })
      .getByText(LIB, { exact: true })
      .first()
      .click({ button: "right" });

    await expect
      .element(screen.getByRole("menuitem", { name: "Select" }))
      .toHaveAttribute("data-disabled");
  });

  it("greys what isn't connected to a pick in another tree, and explains it in the pane's ?", async () => {
    const order = treeIdOf(`${LIB}.order.Order`);
    const screen = await renderStart(
      `?selected_ids=${idOf(LIB)}&selection_part_id=${order}`,
    );
    const { usedBy } = pane(screen);
    await usedBy.getByRole("button", { name: "Expand fixture-app" }).click();

    // Connected rows stay as they are, the others fade; none bold.
    const rows = () =>
      Array.from(
        usedBy.element().querySelectorAll<HTMLElement>("[role=treeitem] > div"),
      );
    await expect.poll(() => rows().length).toBeGreaterThan(0);
    for (const row of rows()) {
      const connected =
        row.parentElement?.getAttribute("data-connected") === "true";
      expect(row.classList.contains("opacity-35")).toBe(!connected);
      expect(row.querySelector(".font-semibold")).toBeNull();
    }
    // No legend over the trees: it is in the pane's ?.
    await expect
      .element(screen.getByText(/aren't connected to it are faded/))
      .not.toBeInTheDocument();
    await screen.getByRole("button", { name: "Gestures" }).last().click();
    await expect
      .element(screen.getByText(/aren't connected to it are faded/))
      .toBeVisible();
  });

  it("opens a linked pick's tree down to it", async () => {
    const order = treeIdOf(`${LIB}.order.Order`);
    const screen = await renderStart(
      `?selected_ids=${idOf(LIB)}&selection_part_id=${order}`,
    );
    const { selection } = pane(screen);

    await expect
      .element(selection.getByRole("treeitem", { name: "Order", exact: true }))
      .toHaveAttribute("aria-selected", "true");
    await expect
      .element(selection.getByRole("treeitem", { name: "order", exact: true }))
      .toHaveAttribute("aria-expanded", "true");
  });

  it("marks a closed row that hides the pick, and opens it down to the pick from its menu", async () => {
    const order = treeIdOf(`${LIB}.order.Order`);
    const screen = await renderStart(
      `?selected_ids=${idOf(LIB)}&selection_part_id=${order}`,
    );
    const { selection } = pane(screen);
    const orderPackage = () =>
      selection.getByRole("treeitem", { name: "order", exact: true });
    await expect
      .element(orderPackage())
      .toHaveAttribute("aria-expanded", "true");
    await expect.element(orderPackage()).not.toHaveAttribute("data-holds-pick");

    await selection.getByRole("button", { name: "Collapse order" }).click();

    // Still the filter, out of sight: the row it is in says so.
    expect(String(screen.router.state.location.search.selection_part_id)).toBe(
      order,
    );
    await expect.element(orderPackage()).toHaveAttribute("data-holds-pick");
    await expect
      .element(orderPackage())
      .toHaveAccessibleDescription(/contains the picked node/);

    // Closing the row above moves the mark up to it.
    const top = selection.getByRole("treeitem", { name: LIB });
    await selection.getByRole("button", { name: `Collapse ${LIB}` }).click();
    await expect.element(top).toHaveAttribute("data-holds-pick");

    await top.getByText(LIB, { exact: true }).click({ button: "right" });
    await screen.getByRole("menuitem", { name: "Reveal the pick" }).click();

    await expect
      .element(selection.getByRole("treeitem", { name: "Order", exact: true }))
      .toHaveAttribute("aria-selected", "true");
    await expect.element(top).not.toHaveAttribute("data-holds-pick");
    await expect.element(orderPackage()).not.toHaveAttribute("data-holds-pick");
  });

  it("opens the list's tab of the side a node is picked on", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(APP)}`);
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
      `?selected_ids=${idOf(LIB)}&uses_id=${order}&selection_part_id=${order}`,
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

  it("orders each level as the hierarchy tree does, External Types last", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(APP)}`);
    const { uses } = pane(screen);

    // The module first, External Types after it, with no separator.
    const top = uses.getByRole("treeitem", {
      name: /^(fixture-app|External Types)$/,
    });
    await expect.element(top.nth(1)).toBeVisible();
    expect(top.elements().map((row) => row.getAttribute("aria-label"))).toEqual(
      ["fixture-app", "External Types"],
    );
    await expect.element(top.nth(1)).not.toHaveClass("border-t");
  });

  it("orders the selection's parts in dependency order, not by name", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);
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
    const screen = await renderStart(`?selected_ids=${idOf(MODULE)}`);
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

  it("says what each tree holds in its title's tooltip, not under it", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);
    const { usedBy } = pane(screen);
    const holds = `The nodes outside ${LIB} whose types depend on types in ${LIB}.`;

    await expect.element(usedBy).toHaveAttribute("aria-description", holds);
    await expect.element(usedBy.getByTitle(holds)).toBeVisible();
    await expect
      .element(usedBy.getByText(`depends on ${LIB}`))
      .not.toBeInTheDocument();
  });

  it("names the selection in the pane's title", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);

    await expect
      .element(screen.getByRole("heading", { name: `Dependencies of ${LIB}` }))
      .toBeVisible();
  });

  it("counts next to each row of the sides how many of the selection's dependencies it holds", async () => {
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);
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

  it("copies a row's names and its type's paths from the row's Copy submenu", async () => {
    const writeText = vi
      .spyOn(navigator.clipboard, "writeText")
      .mockResolvedValue(undefined);
    onTestFinished(() => writeText.mockRestore());
    // Located by a server built from the workspace; not in the recording.
    worker.use(
      graphql.query("NodeSourceLocation", ({ variables }) =>
        HttpResponse.json({
          data: {
            server: { workspace: "/home/dev/fixture-app" },
            hierarchicalGraph: {
              node: {
                id: variables.id,
                properties: [
                  {
                    key: "workspacePath",
                    value: `src/main/java/${LIB.replaceAll(".", "/")}/order/OrderService.java`,
                  },
                  {
                    key: "sourceRootPath",
                    value: `${LIB.replaceAll(".", "/")}/order/OrderService.java`,
                  },
                ],
              },
            },
          },
        }),
      ),
    );
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);
    const { selection } = pane(screen);
    await selection.getByRole("button", { name: "Expand order" }).click();
    const service = selection.getByRole("treeitem", { name: "OrderService" });
    await expect.element(service).toBeVisible();

    rightClick(service.getByText("OrderService", { exact: true }).element());
    expect(await copyEntry(screen, "Fully qualified name")).toBe(
      `${LIB}.order.OrderService`,
    );
    expect(writeText).toHaveBeenLastCalledWith(`${LIB}.order.OrderService`);

    rightClick(service.getByText("OrderService", { exact: true }).element());
    const absolute = `/home/dev/fixture-app/src/main/java/${LIB.replaceAll(".", "/")}/order/OrderService.java`;
    expect(await copyEntry(screen, "Absolute path")).toBe(absolute);
    expect(writeText).toHaveBeenLastCalledWith(absolute);
  });

  it("copies a listed dependency from its row's Copy submenu", async () => {
    const writeText = vi
      .spyOn(navigator.clipboard, "writeText")
      .mockResolvedValue(undefined);
    onTestFinished(() => writeText.mockRestore());
    const screen = await renderStart(`?selected_ids=${idOf(LIB)}`);
    const dependency = `${WEB}.OrderController → ${LIB}.order.OrderService`;
    const row = pane(screen).incoming.getByRole("row", { name: dependency });
    await expect.element(row).toBeVisible();

    rightClick(row.getByRole("cell").first().element());
    expect(await copyEntry(screen, "Dependency")).toBe(dependency);
    expect(writeText).toHaveBeenLastCalledWith(dependency);
  });
});
