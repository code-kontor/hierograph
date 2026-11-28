import { describe, expect, it, vi } from "vitest";
import { userEvent } from "vitest/browser";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
import { handlersFor } from "@/testing/msw/handlers";
import { worker } from "@/testing/msw/worker";
import { renderWithRouter } from "@/testing/render";

import { HierarchyTree } from "./HierarchyTree";

const SCREENS = [
  { path: "/", label: "Dependencies", needsGraph: false },
] as unknown as Screen[];

// The tree inside the frame, which provides the graph scope.
function renderTree(search = "") {
  return renderWithRouter(
    <AppFrame screens={SCREENS}>
      <HierarchyTree />
    </AppFrame>,
    "/",
    search,
  );
}

type TreeRender = Awaited<ReturnType<typeof renderTree>>;

function item(screen: TreeRender, name: string) {
  return screen.getByRole("treeitem", { name, exact: true });
}

// The drawn arcs, read from the SVG.
function arcs() {
  return Array.from(
    document.querySelectorAll<SVGGElement>('svg[aria-label="Dependencies"] g'),
  ).map((g) => ({
    from: g.dataset.from ?? "",
    to: g.dataset.to ?? "",
    direction: g.dataset.direction,
    relation: g.dataset.relation,
    strokeWidth: Number(
      g.querySelector("[data-arrow-line]")?.getAttribute("stroke-width"),
    ),
    dotted:
      g.querySelector("[data-arrow-line]")?.hasAttribute("stroke-dasharray") ??
      false,
    colour: g.querySelector("[data-arrow-line]")?.getAttribute("stroke") ?? "",
    arrowhead:
      g.querySelector("[data-arrow-line]")?.hasAttribute("marker-end") ?? false,
    title: g.querySelector("title")?.textContent ?? "",
    weight: g.dataset.weight ?? "",
    dimmed: g.dataset.dimmed === "true",
  }));
}

// Right-clicks an arc. The tests run without the stylesheet, so the arc area
// isn't laid out over the tree: the click arrives at the arc as an event.
function rightClickArc(from: string, to: string) {
  const arc = document.querySelector(
    `svg[aria-label="Dependencies"] g[data-from="${from}"][data-to="${to}"]`,
  );
  if (arc) rightClick(arc);
}

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

// A row's label: clicking it clicks the row.
function row(screen: TreeRender, name: string) {
  return item(screen, name).getByText(name, { exact: true }).first();
}

// A menu's items as they read, without the gestures at their right ends.
function itemLabels(menu: ReturnType<TreeRender["getByRole"]>) {
  return menu
    .getByRole("menuitem")
    .elements()
    .map((element) => {
      const copy = element.cloneNode(true) as Element;
      copy
        .querySelectorAll('[data-slot="context-menu-shortcut"]')
        .forEach((shortcut) => shortcut.remove());
      return copy.textContent;
    });
}

// The selection as announced (not drawn: the start screen names it in the
// dependencies' heading).
function selectionStatus(screen: TreeRender) {
  return screen.getByRole("status").filter({ hasText: "Selected:" });
}

// Whether the arcs drawn are all the row's.
function onlyArcsOf(id: string) {
  return arcs().every((arc) => arc.from === id || arc.to === id);
}

const CYCLE_PATH = ["fixture-app", "org.hg.fixture", "basic", "cycle"];

// A visible row's node id, by its label.
function nodeId(label: string) {
  return (
    document.querySelector<HTMLElement>(`[data-label="${label}"]`)?.dataset
      .nodeId ?? ""
  );
}

// The labels of an expanded item's children, in display order, once loaded.
async function childLabels(screen: TreeRender, name: string) {
  const children = item(screen, name)
    .getByRole("group")
    .first()
    .getByRole("treeitem");
  await expect.element(children.first()).toBeVisible();
  return children
    .elements()
    .map((element) => element.getAttribute("aria-label"));
}

// Expands the items along `path`, each found by its label.
async function expand(screen: TreeRender, ...path: string[]) {
  for (const name of path) {
    await item(screen, name)
      .getByRole("button", { name: `Expand ${name}`, exact: true })
      .click();
    await expect
      .element(item(screen, name))
      .toHaveAttribute("aria-expanded", "true");
  }
}

describe("HierarchyTree", () => {
  it("reserves no room for arcs without a selection, and only what its arcs need with one", async () => {
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);
    await expect.element(item(screen, "gamma")).toBeVisible();
    const tree = screen.getByRole("tree", { name: "Hierarchy" }).element();
    const gutter = () =>
      getComputedStyle(
        tree.querySelector<HTMLElement>("[style*='--arc-gutter']") ??
          tree.closest<HTMLElement>("[style*='--arc-gutter']") ??
          tree,
      ).getPropertyValue("--arc-gutter");

    expect(gutter()).toBe("0px");

    await item(screen, "gamma").getByText("gamma", { exact: true }).click();

    await expect.poll(() => parseFloat(gutter())).toBeGreaterThan(0);
    await expect.poll(() => parseFloat(gutter())).toBeLessThan(180);
  });

  it("shows the top level of the scanned hierarchy, collapsed", async () => {
    const screen = await renderTree();

    await expect.element(screen.getByRole("tree")).toBeVisible();
    await expect
      .element(item(screen, "fixture-app"))
      .toHaveAttribute("aria-expanded", "false");
    // External Types last, below a separator, so the modules are counted
    // apart from it.
    await expect.element(item(screen, "External Types")).toBeVisible();
    const top = screen
      .getByRole("tree")
      .getByRole("treeitem")
      .elements()
      .map((element) => element.getAttribute("aria-label"));
    expect(top).toEqual(["fixture-app", "External Types"]);
    await expect
      .element(item(screen, "External Types"))
      .toHaveClass(/border-t/);
    // No tooltip, which would cover the arcs.
    expect(
      item(screen, "fixture-app")
        .element()
        .querySelector("[data-node-id]")
        ?.hasAttribute("title"),
    ).toBe(false);
  });

  it("loads a node's children when it is expanded, named relative to it", async () => {
    const screen = await renderTree();

    await expand(screen, "fixture-app", "org.hg.fixture", "basic", "core");

    await expect.element(item(screen, "PlainClass")).toBeVisible();
    await expect.element(item(screen, "Pair")).toBeVisible();
  });

  it("orders siblings by their dependencies: dependents on top, the used at the bottom", async () => {
    const screen = await renderTree();
    await expand(screen, "fixture-app", "org.hg.fixture", "basic");

    expect(await childLabels(screen, "basic")).toEqual([
      "rel",
      "jdk",
      "isolated",
      "cycle",
      "core",
    ]);
  });

  it("marks the siblings in a dependency cycle", async () => {
    const screen = await renderTree();
    await expand(screen, "fixture-app", "org.hg.fixture", "basic", "cycle");

    expect(await childLabels(screen, "cycle")).toEqual([
      "gamma",
      "alpha",
      "beta",
    ]);
    for (const name of ["gamma", "alpha", "beta"]) {
      await expect
        .element(item(screen, name))
        .toHaveAttribute("data-cycle", "true");
    }
    await expect
      .element(item(screen, "alpha"))
      .toHaveAttribute(
        "aria-description",
        "in a dependency cycle with gamma, beta",
      );
    await expect
      .element(item(screen, "core"))
      .not.toHaveAttribute("data-cycle");
  });

  it("ends at the types, without their members", async () => {
    const screen = await renderTree();
    await expand(screen, "fixture-app", "org.hg.fixture", "basic", "core");

    const type = item(screen, "PlainClass");
    await expect.element(type).toBeVisible();
    await expect.element(type).not.toHaveAttribute("aria-expanded");

    await expect
      .element(type.getByRole("button", { name: /^Expand/ }))
      .not.toBeInTheDocument();
    await row(screen, "PlainClass").dblClick();

    await expect.element(type).not.toHaveAttribute("aria-expanded");
    await expect.element(type.getByRole("group")).not.toBeInTheDocument();
  });

  it("collapses an expanded node", async () => {
    const screen = await renderTree();
    await expand(screen, "fixture-app");
    await expect.element(item(screen, "org.hg.fixture")).toBeVisible();

    await screen
      .getByRole("button", { name: "Collapse fixture-app", exact: true })
      .click();

    await expect
      .element(item(screen, "fixture-app"))
      .toHaveAttribute("aria-expanded", "false");
    await expect
      .element(item(screen, "org.hg.fixture"))
      .not.toBeInTheDocument();
  });

  it("shows a hierarchy view's groups, with their members named relative to the level above", async () => {
    const screen = await renderTree("?view=layers");

    await expect
      .element(screen.getByRole("banner").getByText("layers"))
      .toBeVisible();
    await expand(screen, "fixture-app", "org.hg.fixture", "locations");

    await expect.element(item(screen, "application")).toBeVisible();
    await expect.element(item(screen, "library")).toBeVisible();

    await expand(screen, "application");

    await expect.element(item(screen, "app")).toBeVisible();
  });

  it("leaves out what the view excludes, with everything inside it", async () => {
    const screen = await renderTree("?view=locations");

    await expand(screen, "fixture-app");

    // Without `basic`, `org.hg.fixture` holds nothing but `locations`: the
    // chain folds one package further.
    await expect
      .element(item(screen, "org.hg.fixture.locations"))
      .toHaveAttribute(
        "aria-description",
        "folded from org, org.hg, org.hg.fixture",
      );
    await expect.element(item(screen, "basic")).not.toBeInTheDocument();
  });

  it("folds a chain of lone packages into one row, named in full below a module", async () => {
    const screen = await renderTree();

    await expand(screen, "fixture-app");

    const folded = item(screen, "org.hg.fixture");
    await expect
      .element(folded)
      .toHaveAttribute("aria-description", "folded from org, org.hg");
    // The skipped packages are named on the label, left of the arcs.
    expect(
      folded
        .element()
        .querySelector("[data-tree-label]")
        ?.getAttribute("title"),
    ).toBe("org.hg.fixture\nfolded: org, org.hg");
  });

  it("folds below a package too, named relative to it", async () => {
    const screen = await renderTree();

    await expand(screen, "fixture-app", "org.hg.fixture", "locations", "app");

    await expect
      .element(item(screen, "batch.nightly"))
      .toHaveAttribute(
        "aria-description",
        "folded from org.hg.fixture.locations.app.batch",
      );
    // A row that isn't folded has just its full name as the label's
    // tooltip.
    await expect.element(item(screen, "web")).toBeVisible();
    expect(
      item(screen, "web")
        .element()
        .querySelector("[data-tree-label]")
        ?.getAttribute("title"),
    ).toBe("org.hg.fixture.locations.app.web");
  });

  it("draws no arcs until a row is selected", async () => {
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);
    await expect.element(item(screen, "gamma")).toBeVisible();

    expect(arcs()).toEqual([]);
  });

  it("draws the arcs thin and light, at full strength while one of their ends is hovered", async () => {
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);
    await expect.element(item(screen, "gamma")).toBeVisible();
    await row(screen, "beta").click();
    const lines = () =>
      Array.from(
        document.querySelectorAll<SVGPathElement>("[data-arrow-line]"),
      );
    await expect.poll(() => lines().length).toBeGreaterThan(0);
    // The pointer off the arcs' ends: on the cycle's own row.
    await row(screen, "cycle").hover();
    await expect
      .poll(() => lines().every((line) => line.dataset.quiet === "true"))
      .toBe(true);
    expect(
      lines().every((line) => line.getAttribute("stroke-width") === "1.00"),
    ).toBe(true);

    await row(screen, "alpha").hover();

    const alpha = nodeId("alpha");
    await expect
      .poll(() =>
        lines()
          .filter((line) => line.dataset.quiet !== "true")
          .every((line) => {
            const group = line.closest<SVGGElement>("[data-from]");
            return group?.dataset.from === alpha || group?.dataset.to === alpha;
          }),
      )
      .toBe(true);
    expect(lines().some((line) => line.dataset.quiet !== "true")).toBe(true);
  });

  it("dims the arcs of the direction the dependencies don't show", async () => {
    const screen = await renderTree("?dependency_directions=incoming");
    await expand(screen, ...CYCLE_PATH);
    await row(screen, "beta").click();
    const beta = nodeId("beta");
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    const all = arcs();
    // Both directions are drawn, the outgoing ones faded.
    expect(all.some((arc) => arc.from === beta)).toBe(true);
    expect(all.some((arc) => arc.to === beta)).toBe(true);
    for (const arc of all) {
      expect(arc.dimmed).toBe(arc.relation === "outgoing");
    }
  });

  it("draws the selected row's arcs: with the order and against it", async () => {
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);
    await expect.element(item(screen, "gamma")).toBeVisible();
    const cycle = new Set(["gamma", "alpha", "beta"].map(nodeId));

    // The last of a cycle depends on one above it: an arc against the order.
    await row(screen, "beta").click();
    const beta = nodeId("beta");
    await expect
      .element(item(screen, "beta"))
      .toHaveAttribute("aria-selected", "true");
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    expect(onlyArcsOf(beta)).toBe(true);
    const up = arcs().filter((arc) => arc.direction === "up");
    expect(up.length).toBeGreaterThan(0);
    for (const arc of up) {
      expect(cycle.has(arc.from) && cycle.has(arc.to)).toBe(true);
      expect(arc.dotted).toBe(true);
    }
    // Seen from beta: its own dependencies outgoing (blue), the others'
    // incoming (grey), whichever way they run; each with an arrowhead; the
    // heavier never thinner.
    for (const arc of arcs()) {
      expect(arc.relation).toBe(arc.from === beta ? "outgoing" : "incoming");
      expect(arc.colour).toContain(
        arc.relation === "outgoing" ? "--hg-accent" : "--hg-fg-muted",
      );
      expect(arc.arrowhead).toBe(true);
    }
    const byWeight = arcs().sort((a, b) => Number(a.weight) - Number(b.weight));
    for (let index = 1; index < byWeight.length; index++) {
      expect(byWeight[index].strokeWidth).toBeGreaterThanOrEqual(
        byWeight[index - 1].strokeWidth,
      );
    }
    // The tooltip names both ends and the weight.
    expect(arcs()[0].title).toMatch(/^.+ → .+: weight \d+$/);

    // The first of a cycle depends on one below it: an arc with the order.
    await row(screen, "gamma").click();
    const gamma = nodeId("gamma");
    await expect.poll(() => onlyArcsOf(gamma)).toBe(true);
    expect(arcs().some((arc) => arc.direction === "down")).toBe(true);
    expect(
      arcs()
        .filter((arc) => arc.direction === "down")
        .every((arc) => !arc.dotted),
    ).toBe(true);
  });

  it("traces nothing on hover: the selection stays whatever the pointer is on", async () => {
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);
    await row(screen, "alpha").hover();
    await new Promise((resolve) => setTimeout(resolve, 300));
    expect(arcs()).toEqual([]);

    await row(screen, "beta").click();
    const beta = nodeId("beta");
    await row(screen, "gamma").hover();
    await new Promise((resolve) => setTimeout(resolve, 300));

    expect(arcs().length).toBeGreaterThan(0);
    expect(onlyArcsOf(beta)).toBe(true);
  });

  it("clears the selection on a second click, on the empty space and with Escape", async () => {
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);
    const tree = screen.getByRole("tree").element() as HTMLElement;

    await row(screen, "beta").click();
    await expect
      .element(selectionStatus(screen))
      .toHaveTextContent("Selected: beta");
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    // Again, after the double-click time.
    await new Promise((resolve) => setTimeout(resolve, 600));
    await row(screen, "beta").click();
    await expect.poll(() => arcs().length).toBe(0);
    await expect.element(selectionStatus(screen)).not.toBeInTheDocument();

    await row(screen, "beta").click();
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    tree.click();
    await expect.poll(() => arcs().length).toBe(0);

    await new Promise((resolve) => setTimeout(resolve, 600));
    await row(screen, "beta").click();
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    await userEvent.keyboard("{Escape}");
    await expect.poll(() => arcs().length).toBe(0);
    expect(screen.router.state.location.search.selected_ids).toBeUndefined();
  });

  it("expands and collapses a row on a double-click, and leaves it selected", async () => {
    const screen = await renderTree();
    const app = item(screen, "fixture-app");

    await row(screen, "fixture-app").dblClick();

    await expect.element(app).toHaveAttribute("aria-expanded", "true");
    await expect.element(app).toHaveAttribute("aria-selected", "true");

    await row(screen, "fixture-app").dblClick();

    await expect.element(app).toHaveAttribute("aria-expanded", "false");
    await expect.element(app).toHaveAttribute("aria-selected", "true");
  });

  it("expands and collapses a row with a click left of its name, without selecting it", async () => {
    const screen = await renderTree();
    const app = item(screen, "fixture-app");
    await expect.element(app).toBeVisible();
    // On the row's indent, left of its chevron.
    const clickIndent = () => {
      const element = app
        .element()
        .querySelector<HTMLElement>("[data-node-id]")!;
      const bounds = element.getBoundingClientRect();
      element.dispatchEvent(
        new MouseEvent("click", {
          bubbles: true,
          cancelable: true,
          detail: 1,
          clientX: bounds.left + 2,
          clientY: bounds.top + bounds.height / 2,
        }),
      );
    };

    clickIndent();

    await expect.element(app).toHaveAttribute("aria-expanded", "true");
    await expect.element(app).toHaveAttribute("aria-selected", "false");
    await expect.element(selectionStatus(screen)).not.toBeInTheDocument();

    clickIndent();

    await expect.element(app).toHaveAttribute("aria-expanded", "false");
    await expect.element(app).toHaveAttribute("aria-selected", "false");
  });

  it("has no pin: a click always selects", async () => {
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);

    await expect
      .element(screen.getByRole("button", { name: "Pin the selection" }))
      .not.toBeInTheDocument();
    await row(screen, "beta").click();
    await row(screen, "gamma").click();

    await expect
      .element(item(screen, "gamma"))
      .toHaveAttribute("aria-selected", "true");
    await expect
      .element(item(screen, "beta"))
      .toHaveAttribute("aria-selected", "false");
  });

  it("lists its gestures in the pane's help, without the pin, zoom or pan", async () => {
    const screen = await renderTree();

    await screen.getByRole("button", { name: "Gestures" }).click();

    const help = screen.getByRole("dialog", { name: "Gestures" });
    await expect.element(help.getByText("Pin button")).not.toBeInTheDocument();
    await expect.element(help.getByText("Right-click")).toBeVisible();
    await expect.element(help.getByText(/Zoom/)).not.toBeInTheDocument();
  });

  it("walks the rows by keyboard: moves, expands, goes in and out, selects", async () => {
    const screen = await renderTree();
    await expect.element(item(screen, "fixture-app")).toBeVisible();
    const tree = screen.getByRole("tree").element() as HTMLElement;
    const active = () =>
      document
        .getElementById(tree.getAttribute("aria-activedescendant") ?? "")
        ?.getAttribute("aria-label");

    tree.focus();
    await userEvent.keyboard("{ArrowDown}");
    expect(active()).toBe("fixture-app");

    await userEvent.keyboard("{ArrowRight}");
    await expect
      .element(item(screen, "fixture-app"))
      .toHaveAttribute("aria-expanded", "true");
    await expect.element(item(screen, "org.hg.fixture")).toBeVisible();
    await userEvent.keyboard("{ArrowRight}");
    expect(active()).toBe("org.hg.fixture");

    await userEvent.keyboard("{Enter}");
    await expect
      .element(item(screen, "org.hg.fixture"))
      .toHaveAttribute("aria-selected", "true");

    await userEvent.keyboard("{ArrowLeft}");
    expect(active()).toBe("fixture-app");
    await userEvent.keyboard("{ArrowLeft}");
    await expect
      .element(item(screen, "fixture-app"))
      .toHaveAttribute("aria-expanded", "false");
    await userEvent.keyboard("{End}");
    expect(active()).toBe("External Types");
    await userEvent.keyboard("{Home}");
    expect(active()).toBe("fixture-app");

    // + and − expand and collapse, as on every graph; − in a row that isn't
    // open collapses the row it lies in.
    await userEvent.keyboard("+");
    await expect
      .element(item(screen, "fixture-app"))
      .toHaveAttribute("aria-expanded", "true");
    await expect.element(item(screen, "org.hg.fixture")).toBeVisible();
    await userEvent.keyboard("{ArrowDown}");
    expect(active()).toBe("org.hg.fixture");
    await userEvent.keyboard("-");
    await expect
      .element(item(screen, "fixture-app"))
      .toHaveAttribute("aria-expanded", "false");
    expect(active()).toBe("fixture-app");
  });

  it("opens an arc's menu on a right click: clear the selection, select its other end", async () => {
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);
    await row(screen, "beta").click();
    const beta = nodeId("beta");
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    const arc = arcs()[0];
    const other = arc.from === beta ? arc.to : arc.from;
    const otherLabel =
      document.querySelector<HTMLElement>(`[data-node-id="${other}"]`)?.dataset
        .label ?? "";

    rightClickArc(arc.from, arc.to);

    const menu = screen.getByRole("menu");
    await expect.element(menu).toBeVisible();
    expect(itemLabels(menu)).toEqual([
      "Clear selection",
      `Select ${otherLabel}`,
      "Copy",
    ]);
    // The arcs stay while the menu is open.
    await new Promise((resolve) => setTimeout(resolve, 300));
    expect(arcs().length).toBeGreaterThan(0);
    // Closing the menu with Escape leaves the selection.
    await userEvent.keyboard("{Escape}");
    await expect.element(screen.getByRole("menu")).not.toBeInTheDocument();
    expect(onlyArcsOf(beta)).toBe(true);

    rightClickArc(arc.from, arc.to);
    await screen
      .getByRole("menuitem", { name: `Select ${otherLabel}` })
      .click();

    await expect.poll(() => onlyArcsOf(other)).toBe(true);
    expect(String(screen.router.state.location.search.selected_ids)).toBe(
      other,
    );
  });

  it("copies a package's names from its row's Copy submenu, each with a preview", async () => {
    const writeText = vi
      .spyOn(navigator.clipboard, "writeText")
      .mockResolvedValue(undefined);
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);
    await expect.element(item(screen, "beta")).toBeVisible();

    rightClick(row(screen, "beta").element());
    await screen.getByRole("menuitem", { name: "Copy" }).click();
    const submenu = screen.getByRole("menu", { name: "Copy" });
    await expect.element(submenu).toBeVisible();
    expect(
      submenu
        .getByRole("menuitem")
        .elements()
        .map((element) => element.firstElementChild?.textContent),
    ).toEqual(["Simple name", "Fully qualified name"]);
    const qualified = submenu.getByRole("menuitem", {
      name: "Fully qualified name",
    });
    await expect
      .element(qualified)
      .toHaveAccessibleDescription("org.hg.fixture.basic.cycle.beta");
    // A click on the element: moving the pointer there from the trigger
    // would leave the submenu.
    (qualified.element() as HTMLElement).click();

    expect(writeText).toHaveBeenCalledWith("org.hg.fixture.basic.cycle.beta");
    writeText.mockRestore();
  });

  it("drills an expanded row's arcs down to the rows inside it", async () => {
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);
    await expect.element(item(screen, "gamma")).toBeVisible();
    // The visible rows inside the expanded module.
    const inside = new Set(
      Array.from(
        item(screen, "fixture-app")
          .element()
          .querySelectorAll<HTMLElement>("[data-frontier]"),
      ).map((element) => element.dataset.nodeId ?? ""),
    );

    // Its code's dependencies across its border, on External Types.
    await row(screen, "fixture-app").click();
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    for (const arc of arcs()) {
      expect(inside.has(arc.from)).not.toBe(inside.has(arc.to));
      expect(arc.relation).toBe(inside.has(arc.from) ? "outgoing" : "incoming");
    }
  });

  it("selects a row from its context menu, and expands it there", async () => {
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);
    await expect.element(item(screen, "beta")).toBeVisible();
    const beta = nodeId("beta");

    rightClick(row(screen, "fixture-app").element());
    await expect.element(screen.getByRole("menu")).toBeVisible();
    expect(itemLabels(screen.getByRole("menu"))).toEqual([
      "Select",
      "Add to the selection",
      "Collapse",
      "Copy",
    ]);
    await userEvent.keyboard("{Escape}");
    await expect.element(screen.getByRole("menu")).not.toBeInTheDocument();

    rightClick(row(screen, "beta").element());
    const menu = screen.getByRole("menu");
    await expect.element(menu).toBeVisible();
    // A package: it can be expanded too.
    expect(itemLabels(menu)).toEqual([
      "Select",
      "Add to the selection",
      "Expand",
      "Copy",
    ]);
    // Each item with its gesture.
    await expect
      .element(menu.getByText("Click", { exact: true }))
      .toBeInTheDocument();
    await expect
      .element(menu.getByText("+ / Double-click", { exact: true }))
      .toBeInTheDocument();
    await screen.getByRole("menuitem", { name: "Select", exact: true }).click();

    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    expect(onlyArcsOf(beta)).toBe(true);

    // The selected row's menu clears the selection.
    rightClick(row(screen, "beta").element());
    await screen.getByRole("menuitem", { name: "Clear selection" }).click();
    await expect.element(selectionStatus(screen)).not.toBeInTheDocument();
  });

  it("takes rows together with ⌘-click: their arcs cross the group's border, a row above or below one replaces it", async () => {
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);
    await expect.element(item(screen, "gamma")).toBeVisible();
    const [alpha, beta, cycle] = ["alpha", "beta", "cycle"].map(nodeId);
    const selectedIds = () =>
      (
        screen.router.state.location.search.selected_ids as
          unknown[] | undefined
      )
        ?.map(String)
        .sort();
    const metaClick = (name: string) =>
      row(screen, name).click({ modifiers: ["ControlOrMeta"] } as never);

    await row(screen, "alpha").click();
    await metaClick("beta");

    await expect.poll(selectedIds).toEqual([alpha, beta].sort());
    await expect
      .element(item(screen, "alpha"))
      .toHaveAttribute("aria-selected", "true");
    await expect
      .element(item(screen, "beta"))
      .toHaveAttribute("aria-selected", "true");
    await expect
      .element(selectionStatus(screen))
      .toHaveTextContent("Selected: 2 nodes: alpha, beta");
    // Only what crosses the border around both: nothing between them.
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    const group = new Set([alpha, beta]);
    for (const arc of arcs()) {
      expect(group.has(arc.from)).not.toBe(group.has(arc.to));
    }

    // Again: out of the group.
    await metaClick("beta");
    await expect.poll(selectedIds).toEqual([alpha]);

    // The cycle's package holds alpha: it takes alpha's place.
    await metaClick("beta");
    await expect.poll(selectedIds).toEqual([alpha, beta].sort());
    await metaClick("cycle");
    await expect.poll(selectedIds).toEqual([cycle]);
    await expect
      .element(item(screen, "alpha"))
      .toHaveAttribute("aria-selected", "false");

    // And a row inside it takes the package's place.
    await metaClick("gamma");
    await expect.poll(selectedIds).toEqual([nodeId("gamma")]);

    // A plain click selects one row alone; Esc clears.
    await metaClick("alpha");
    await row(screen, "beta").click();
    await expect.poll(selectedIds).toEqual([beta]);
    await userEvent.keyboard("{Escape}");
    await expect.poll(selectedIds).toBeUndefined();
  });

  it("marks a collapsed row that hides the selection, and expands it down to it from its menu", async () => {
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);
    await row(screen, "alpha").click();
    await expect
      .element(item(screen, "alpha"))
      .toHaveAttribute("aria-selected", "true");
    await expect
      .element(item(screen, "cycle"))
      .not.toHaveAttribute("data-holds-selection");

    await item(screen, "cycle")
      .getByRole("button", { name: "Collapse cycle", exact: true })
      .click();

    await expect
      .element(item(screen, "cycle"))
      .toHaveAttribute("data-holds-selection");
    await expect
      .element(item(screen, "cycle"))
      .toHaveAccessibleDescription(/contains the selection/);

    rightClick(row(screen, "cycle").element());
    await screen
      .getByRole("menuitem", { name: "Reveal the selection" })
      .click();

    await expect
      .element(item(screen, "alpha"))
      .toHaveAttribute("aria-selected", "true");
    await expect
      .element(item(screen, "cycle"))
      .not.toHaveAttribute("data-holds-selection");
  });

  it("selects a cycle with a click on its bar, or from the bar's menu", async () => {
    const screen = await renderTree();
    await expand(screen, ...CYCLE_PATH);
    await expect.element(item(screen, "gamma")).toBeVisible();
    const cycle = new Set(["gamma", "alpha", "beta"].map(nodeId));
    // The tests run without the stylesheet, so the bar has no size to click:
    // the click arrives at it as an event.
    const bar = () =>
      item(screen, "alpha").element().querySelector("[data-cycle-bar]")!;

    bar().dispatchEvent(new MouseEvent("click", { bubbles: true }));

    // The dependencies between the members, each in the cycle's own colour,
    // at least one against the order (else there were no cycle).
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    for (const arc of arcs()) {
      expect(cycle.has(arc.from) && cycle.has(arc.to)).toBe(true);
      expect(arc.relation).toBe("cycle");
    }
    expect(new Set(arcs().flatMap((arc) => [arc.from, arc.to]))).toEqual(cycle);
    expect(arcs().some((arc) => arc.direction === "up" && arc.dotted)).toBe(
      true,
    );
    await expect
      .element(selectionStatus(screen))
      .toHaveTextContent(/Selected: cycle of /);
    // The row it is on stays unselected.
    await expect
      .element(item(screen, "alpha"))
      .toHaveAttribute("aria-selected", "false");

    // The bar's menu only, not also the row's.
    rightClick(bar());
    await expect.element(screen.getByRole("menu")).toBeVisible();
    expect(screen.getByRole("menu").elements()).toHaveLength(1);
    await screen.getByRole("menuitem", { name: "Clear selection" }).click();
    await expect.poll(() => arcs().length).toBe(0);

    rightClick(bar());
    await screen.getByRole("menuitem", { name: "Select the cycle" }).click();
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    // Choosing it left the bar's row as it was.
    await expect
      .element(item(screen, "alpha"))
      .not.toHaveAttribute("aria-expanded", "true");

    // A click on the row: its own arcs.
    await row(screen, "alpha").click();
    const alpha = nodeId("alpha");
    await expect.poll(() => onlyArcsOf(alpha)).toBe(true);
    expect(arcs().every((arc) => arc.relation !== "cycle")).toBe(true);
  });

  it("keeps its open rows and the selection in the URL, and opens as they say", async () => {
    const first = await renderTree();
    await expand(first, ...CYCLE_PATH);
    await expect.element(item(first, "beta")).toBeVisible();
    const beta = nodeId("beta");
    await row(first, "beta").click();
    await expect.element(selectionStatus(first)).toBeVisible();
    const { search, searchStr } = first.router.state.location;
    // A lone numeric id comes back from the URL as a number.
    expect(String(search.selected_ids)).toBe(beta);
    // The rows along the folded path: fixture-app, org.hg.fixture, basic, cycle.
    expect(search.expanded_ids).toHaveLength(4);
    await first.unmount();

    const screen = await renderTree(searchStr);

    await expect.element(item(screen, "beta")).toBeVisible();
    await expect
      .element(item(screen, "cycle"))
      .toHaveAttribute("aria-expanded", "true");
    await expect
      .element(selectionStatus(screen))
      .toHaveTextContent("Selected: beta");
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    expect(onlyArcsOf(beta)).toBe(true);
  });

  it("draws the arcs to External Types, with the order", async () => {
    const screen = await renderTree();
    await expand(screen, "fixture-app");
    await expect.element(item(screen, "org.hg.fixture")).toBeVisible();

    await row(screen, "org.hg.fixture").click();

    const org = nodeId("org.hg.fixture");
    const external = nodeId("External Types");
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    expect(arcs()).toContainEqual(
      expect.objectContaining({
        from: org,
        to: external,
        direction: "down",
        relation: "outgoing",
        dotted: false,
      }),
    );
  });

  it("draws the arcs of a row inside External Types: the code that uses it", async () => {
    const screen = await renderTree();
    await expand(screen, "External Types", "java");
    await expect.element(item(screen, "util")).toBeVisible();

    await row(screen, "util").click();

    const util = nodeId("util");
    const app = nodeId("fixture-app");
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    expect(arcs()).toContainEqual(
      expect.objectContaining({
        from: app,
        to: util,
        direction: "down",
        relation: "incoming",
      }),
    );
  });

  it("says when there is no graph", async () => {
    worker.use(...handlersFor("no-graph"));
    const screen = await renderTree();

    await expect.element(screen.getByText(/No graph is loaded/)).toBeVisible();
    await expect.element(screen.getByRole("tree")).not.toBeInTheDocument();
  });
});
