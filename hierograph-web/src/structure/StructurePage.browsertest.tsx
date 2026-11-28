import { describe, expect, it, vi } from "vitest";
import { userEvent } from "vitest/browser";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
import structureGiven from "@/testing/fixtures/StructureGiven.json";
import { renderWithRouter } from "@/testing/render";

import { StructurePage } from "./StructurePage";

const SCREENS = [
  { path: "/structure", label: "Structure", needsGraph: true },
] as unknown as Screen[];

// The structure screen inside the frame, as the app renders it.
function renderMap(search = "") {
  return renderWithRouter(
    <AppFrame screens={SCREENS}>
      <StructurePage />
    </AppFrame>,
    "/structure",
    search,
  );
}

type MapRender = Awaited<ReturnType<typeof renderMap>>;

function box(screen: MapRender, label: string) {
  return screen.getByRole("treeitem", { name: label, exact: true });
}

function searchOf(screen: MapRender) {
  return screen.router.state.location.search as {
    expanded_ids?: string[];
    selected_id?: string;
    given_only?: boolean;
  };
}

// The recorded given dependencies (see record-fixtures.ts), in the scanned
// hierarchy: OrderController → OrderService and CustomerController →
// CustomerApi (app → lib), CycleB → CycleC (against the cycle's levels),
// Island → PlainClass (no dependency), and an id that names no node.
const GIVEN = structureGiven.entries[0].variables.pairs
  .map((pair) => `${pair.sourceId}-${pair.targetId}`)
  .join(",");

function arrowsOf(role: string) {
  return document.querySelectorAll(`path[data-role="${role}"]`).length;
}

// A menu's items as they read, without the gestures at their right ends.
function itemLabels(menu: ReturnType<MapRender["getByRole"]>) {
  return menu
    .getByRole("menuitem")
    .elements()
    .map((element) => {
      const item = element.cloneNode(true) as Element;
      item
        .querySelectorAll('[data-slot="context-menu-shortcut"]')
        .forEach((shortcut) => shortcut.remove());
      return item.textContent;
    });
}

function selectionStatus(screen: MapRender) {
  return screen.getByRole("status").filter({ hasText: "Selected:" });
}

// The box the keyboard is on, by its name.
function activeBox() {
  return document
    .getElementById(canvas().getAttribute("aria-activedescendant") ?? "")
    ?.getAttribute("aria-label");
}

function canvas() {
  return document.querySelector<HTMLElement>(".lsm-canvas")!;
}

// Opens a box's context menu, as a right click does.
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

async function expand(screen: MapRender, ...labels: string[]) {
  for (const label of labels) {
    await screen
      .getByRole("button", { name: `Expand ${label}`, exact: true })
      .click();
    await expect
      .element(box(screen, label))
      .toHaveAttribute("aria-expanded", "true");
  }
}

describe("StructurePage", () => {
  it("shows the top level in rows by level: the module above External Types, which it uses", async () => {
    const screen = await renderMap();

    await expect
      .element(
        screen
          .getByRole("group", { name: "Level 1" })
          .getByRole("treeitem", { name: "fixture-app" }),
      )
      .toBeVisible();
    await expect
      .element(
        screen
          .getByRole("group", { name: "Level 0" })
          .getByRole("treeitem", { name: "External Types" }),
      )
      .toBeVisible();
  });

  it("expands a container into its own rows, and keeps the expanded containers in the URL", async () => {
    const screen = await renderMap();

    await expand(screen, "fixture-app", "org.hg.fixture");

    await expect.element(box(screen, "basic")).toBeVisible();
    expect(searchOf(screen).expanded_ids).toHaveLength(2);

    await screen
      .getByRole("button", { name: "Collapse org.hg.fixture", exact: true })
      .click();

    await expect.element(box(screen, "basic")).not.toBeInTheDocument();
    expect(searchOf(screen).expanded_ids).toHaveLength(1);
  });

  it("folds a module's chain of lone packages into one box, named in full", async () => {
    const screen = await renderMap();

    // org holds only org.hg, which holds only org.hg.fixture.
    await expand(screen, "fixture-app");

    await expect.element(box(screen, "org.hg.fixture")).toBeVisible();
    await expect.element(box(screen, "org")).not.toBeInTheDocument();
    // The fold ends where a package holds more than one thing.
    await expand(screen, "org.hg.fixture");
    await expect.element(box(screen, "basic")).toBeVisible();
    await expect.element(box(screen, "locations")).toBeVisible();
    // External Types' java holds two packages: not folded.
    await expand(screen, "External Types");
    await expect.element(box(screen, "java")).toBeVisible();
    // The box names the packages it stands for.
    await expect
      .element(box(screen, "org.hg.fixture"))
      .toHaveAttribute("title", expect.stringMatching(/folded: org, org\.hg$/));
  });

  it("folds a chain inside a package too, named relative to it", async () => {
    const screen = await renderMap();

    await expand(screen, "fixture-app", "org.hg.fixture", "locations", "app");

    await expect
      .element(box(screen, "batch.nightly"))
      .toHaveAttribute(
        "title",
        expect.stringMatching(
          /folded: org\.hg\.fixture\.locations\.app\.batch$/,
        ),
      );
  });

  it("frames a cycle's members as a tangle, draws its feedback dependency", async () => {
    const screen = await renderMap();

    await expand(screen, "fixture-app", "org.hg.fixture", "basic", "cycle");

    const tangle = box(screen, "cycle").getByRole("group", { name: "Tangle" });
    await expect
      .element(tangle.getByRole("treeitem", { name: "alpha" }))
      .toBeVisible();
    await expect
      .element(tangle.getByRole("treeitem", { name: "beta" }))
      .toBeVisible();
    await expect
      .element(tangle.getByRole("treeitem", { name: "gamma" }))
      .toBeVisible();
    await expect
      .poll(
        () => document.querySelectorAll('path[data-role="feedback"]').length,
      )
      .toBe(1);
  });

  it("opens the expanded containers from the URL", async () => {
    const first = await renderMap();
    await expand(first, "fixture-app", "org.hg.fixture");
    const ids = searchOf(first).expanded_ids ?? [];
    await first.unmount();

    const screen = await renderMap(`?expanded_ids=${ids.join(",")}`);

    await expect.element(box(screen, "basic")).toBeVisible();
  });

  it("selects a clicked box: rings on what it uses, in the URL, until it is clicked again", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");

    await app.click();

    await expect.element(app).toHaveAttribute("aria-current", "true");
    await expect.element(app).toHaveAttribute("aria-selected", "true");
    await expect.element(box(screen, "External Types")).toHaveClass("lsm-uses");
    expect(searchOf(screen).selected_id).toBeDefined();
    await expect
      .element(selectionStatus(screen))
      .toHaveTextContent("Selected:fixture-app· Esc to clear");

    await app.click();

    await expect.element(app).not.toHaveAttribute("aria-current");
    await expect
      .element(box(screen, "External Types"))
      .not.toHaveClass("lsm-uses");
    expect(searchOf(screen).selected_id).toBeUndefined();
    await expect.element(selectionStatus(screen)).not.toBeInTheDocument();
  });

  it("traces nothing on hover: the selection stays whatever the pointer is on", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");

    await app.hover();
    await new Promise((resolve) => setTimeout(resolve, 300));

    await expect.element(app).not.toHaveAttribute("aria-current");

    await app.click();
    await box(screen, "External Types").hover();
    await new Promise((resolve) => setTimeout(resolve, 300));

    await expect.element(app).toHaveAttribute("aria-current", "true");
    await expect.element(box(screen, "External Types")).toHaveClass("lsm-uses");
  });

  it("clears the selection on a click on the canvas, with Esc, and with the status's button", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");

    await app.click();
    canvas().click();
    await expect.element(app).not.toHaveAttribute("aria-current");

    await app.click();
    await userEvent.keyboard("{Escape}");
    await expect.element(app).not.toHaveAttribute("aria-current");

    await app.click();
    // Unstyled in the test, the zoom controls may cover the button.
    (
      screen
        .getByRole("button", { name: "Clear selection" })
        .element() as HTMLElement
    ).click();
    await expect.element(app).not.toHaveAttribute("aria-current");
    expect(searchOf(screen).selected_id).toBeUndefined();
  });

  it("pins the selection: clicks keep it, a menu changes it, clearing it unpins", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    const external = box(screen, "External Types");
    const pin = screen.getByRole("button", { name: "Pin the selection" });

    // Nothing selected: nothing to pin.
    await expect.element(pin).toBeDisabled();

    await external.click();
    // Unstyled in the test, the zoom controls may cover the button.
    (pin.element() as HTMLElement).click();
    await expect.element(pin).toHaveAttribute("aria-pressed", "true");
    expect(
      (screen.router.state.location.search as { selection_pinned?: boolean })
        .selection_pinned,
    ).toBe(true);

    // A click, a click on the canvas and Enter leave the selection; a
    // double-click still expands.
    await app.click();
    await expect.element(external).toHaveAttribute("aria-selected", "true");
    canvas().click();
    await expect.element(external).toHaveAttribute("aria-selected", "true");
    await userEvent.keyboard("{Enter}");
    await expect.element(external).toHaveAttribute("aria-selected", "true");
    const name = app.getByText("fixture-app", { exact: true });
    await name.dblClick();
    await expect.element(app).toHaveAttribute("aria-expanded", "true");
    await expect.element(app).toHaveAttribute("aria-selected", "false");
    await expect.element(external).toHaveAttribute("aria-selected", "true");

    // The menu still selects, and the pin stays.
    rightClick(app.element());
    await screen.getByRole("menuitem", { name: "Select", exact: true }).click();
    await expect.element(app).toHaveAttribute("aria-selected", "true");
    await expect.element(pin).toHaveAttribute("aria-pressed", "true");

    // Escape clears the selection and unpins it.
    await userEvent.keyboard("{Escape}");
    await expect.element(app).toHaveAttribute("aria-selected", "false");
    await expect.element(pin).toBeDisabled();
    expect(searchOf(screen).selected_id).toBeUndefined();
  });

  it("expands and collapses a box on a double-click, and leaves it selected", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    // Its name: once open, its middle is its contents.
    const name = app.getByText("fixture-app", { exact: true });

    await name.dblClick();

    await expect.element(app).toHaveAttribute("aria-expanded", "true");
    await expect.element(app).toHaveAttribute("aria-selected", "true");

    await name.dblClick();

    await expect.element(app).toHaveAttribute("aria-expanded", "false");
    await expect.element(app).toHaveAttribute("aria-selected", "true");
    expect(searchOf(screen).expanded_ids).toBeUndefined();
  });

  it("walks the boxes by keyboard: up and down as drawn, + and − expand and collapse, Enter selects", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    await expect.element(app).toBeVisible();

    canvas().focus();
    await userEvent.keyboard("{ArrowDown}");
    expect(activeBox()).toBe("fixture-app");
    await expect.element(app).toHaveClass("lsm-cursor");

    await userEvent.keyboard("+");
    await expect.element(app).toHaveAttribute("aria-expanded", "true");
    await expect.element(box(screen, "org.hg.fixture")).toBeVisible();
    // Down from its name bar: into its top row.
    await userEvent.keyboard("{ArrowDown}");
    expect(activeBox()).toBe("org.hg.fixture");

    await userEvent.keyboard("{Enter}");
    await expect
      .element(box(screen, "org.hg.fixture"))
      .toHaveAttribute("aria-current", "true");

    // Up from its top row: out to the name bar.
    await userEvent.keyboard("{ArrowUp}");
    expect(activeBox()).toBe("fixture-app");

    // − in a box that isn't open collapses the box it lies in.
    await userEvent.keyboard("{ArrowDown}");
    await userEvent.keyboard("-");
    await expect.element(app).toHaveAttribute("aria-expanded", "false");
    expect(activeBox()).toBe("fixture-app");

    // External Types is drawn below the module, which uses it.
    await userEvent.keyboard("{ArrowDown}");
    expect(activeBox()).toBe("External Types");
    await userEvent.keyboard("{Home}");
    expect(activeBox()).toBe("fixture-app");
  });

  it("moves left and right along a row by keyboard", async () => {
    const screen = await renderMap();
    await expand(screen, "fixture-app", "org.hg.fixture", "basic");
    // A row with two boxes side by side.
    const row = [...document.querySelectorAll(".lsm-row")]
      .map((element) =>
        [...element.children]
          .map((cell) =>
            cell.querySelector<HTMLElement>(":scope > [data-box-id]"),
          )
          .filter((cell) => cell !== null),
      )
      .find((cells) => cells.length >= 2)!;
    const [first, second] = row.map((cell) => cell.getAttribute("aria-label"));

    await box(screen, first!).click();
    canvas().focus();
    await userEvent.keyboard("{ArrowRight}");
    expect(activeBox()).toBe(second);
    await userEvent.keyboard("{ArrowLeft}");
    expect(activeBox()).toBe(first);
  });

  it("zooms with ctrl + = and - and back to 100% with ctrl + 0, while the map has focus", async () => {
    const screen = await renderMap();
    await expect.element(box(screen, "fixture-app")).toBeVisible();
    const reset = screen.getByTitle("Reset the zoom to 100%");

    canvas().focus();
    await userEvent.keyboard("{Control>}0{/Control}");
    await expect.element(reset).toHaveTextContent("100%");
    await userEvent.keyboard("{Control>}={/Control}");
    await expect.element(reset).toHaveTextContent("125%");
    await userEvent.keyboard("{Control>}-{/Control}");
    await expect.element(reset).toHaveTextContent("100%");
  });

  it("zooms to the selection from the zoom controls, next to fitting the map", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    await expect.element(app).toBeVisible();
    const toSelection = screen.getByRole("button", {
      name: "Zoom to the selection",
    });
    const reset = screen.getByTitle("Reset the zoom to 100%");
    const percent = () => Number(reset.element().textContent?.slice(0, -1));

    // Nothing selected: nothing to zoom to.
    await expect.element(toSelection).toBeDisabled();
    const controls = screen
      .getByRole("group", { name: "Zoom" })
      .getByRole("button")
      .elements()
      .map((button) => button.getAttribute("aria-label"));
    expect(controls.indexOf("Zoom to the selection")).toBe(
      controls.indexOf("Fit the map") + 1,
    );

    await app.click();
    canvas().focus();
    await userEvent.keyboard("{Control>}0{/Control}");
    for (let step = 0; step < 4; step++) {
      await userEvent.keyboard("{Control>}={/Control}");
    }
    await expect.poll(percent).toBeGreaterThan(200);

    (toSelection.element() as HTMLElement).click();

    // The box and what it uses, never above 100%.
    await expect.poll(percent).toBeLessThanOrEqual(100);
  });

  it("greys out what a selected box isn't coupled to, until the selection is cleared", async () => {
    const screen = await renderMap();
    await expand(screen, "fixture-app", "org.hg.fixture", "basic", "cycle");
    const alpha = box(screen, "alpha");

    await alpha.click();

    await expect
      .poll(() => document.querySelectorAll(".lsm-dim").length)
      .toBeGreaterThan(0);
    await expect.element(alpha).not.toHaveClass("lsm-dim");
    // The containers it lies in stay as they are.
    await expect.element(box(screen, "cycle")).not.toHaveClass("lsm-dim");

    await userEvent.keyboard("{Escape}");

    await expect
      .poll(() => document.querySelectorAll(".lsm-dim").length)
      .toBe(0);
  });

  it("selects a box from its context menu, with each item's gesture", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    await expect.element(app).toBeVisible();

    rightClick(app.element());
    const menu = screen.getByRole("menu");
    await expect.element(menu).toBeVisible();
    expect(itemLabels(menu)).toEqual([
      "Select",
      "Expand",
      "Expand all",
      "Collapse",
      "Collapse all",
      "Copy",
    ]);
    await expect
      .element(menu.getByText("Double-click").first())
      .toBeInTheDocument();
    await expect
      .element(screen.getByRole("menuitem", { name: "Collapse", exact: true }))
      .toHaveAttribute("aria-disabled", "true");
    await screen.getByRole("menuitem", { name: "Select", exact: true }).click();

    expect(searchOf(screen).selected_id).toBeDefined();
    await expect.element(app).toHaveAttribute("aria-current", "true");

    rightClick(app.element());
    await screen.getByRole("menuitem", { name: "Clear selection" }).click();

    expect(searchOf(screen).selected_id).toBeUndefined();
    await expect.element(app).not.toHaveAttribute("aria-current");
  });

  it("opens an arrow's menu on a right click: select an end, expand an end", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    await app.click();
    await expect
      .poll(
        () =>
          document.querySelectorAll("[data-arrow-key] [data-arrow-hit]").length,
      )
      .toBeGreaterThan(0);

    rightClick(document.querySelector("[data-arrow-key] [data-arrow-hit]")!);
    const menu = screen.getByRole("menu");
    await expect.element(menu).toBeVisible();
    await expect
      .element(menu.getByText("fixture-app → External Types", { exact: false }))
      .toBeVisible();
    expect(itemLabels(menu)).toEqual([
      "Clear selection",
      "Select External Types",
      "Expand fixture-app",
      "Expand External Types",
      "Copy",
    ]);
    await screen
      .getByRole("menuitem", { name: "Select External Types" })
      .click();

    expect(searchOf(screen).selected_id).toBeDefined();
    await expect
      .element(box(screen, "External Types"))
      .toHaveAttribute("aria-current", "true");
  });

  it("copies an arrow's dependency from its Copy submenu", async () => {
    const writeText = vi
      .spyOn(navigator.clipboard, "writeText")
      .mockResolvedValue(undefined);
    const screen = await renderMap();
    await box(screen, "fixture-app").click();
    await expect
      .poll(
        () =>
          document.querySelectorAll("[data-arrow-key] [data-arrow-hit]").length,
      )
      .toBeGreaterThan(0);

    rightClick(document.querySelector("[data-arrow-key] [data-arrow-hit]")!);
    await screen.getByRole("menuitem", { name: "Copy" }).click();
    const dependency = screen
      .getByRole("menu", { name: "Copy" })
      .getByRole("menuitem", { name: "Dependency" });
    await expect.element(dependency).toBeVisible();
    // A click on the element: moving the pointer there from the trigger
    // would leave the submenu.
    (dependency.element() as HTMLElement).click();

    // The module by its artifact key, the external module by its name.
    expect(writeText).toHaveBeenCalledWith(
      expect.stringMatching(/^[^:\s]+:fixture-app:[^ ]+ → External Types$/),
    );
    writeText.mockRestore();
  });

  it("draws an expanded box's arrows from its contents", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    await app.click();
    const appId = app.element().getAttribute("data-box-id");
    const starts = () =>
      [...document.querySelectorAll<SVGElement>("[data-arrow-key]")]
        .filter((arrow) => arrow.dataset.arrowKey?.endsWith(">uses"))
        .map((arrow) => arrow.dataset.arrowKey?.split(">")[0]);
    await expect.poll(starts).toContain(appId);

    await expand(screen, "fixture-app");

    await expect.poll(() => starts().length).toBeGreaterThan(0);
    await expect.poll(starts).not.toContain(appId);
  });

  it("expands and collapses a box from its menu", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    await expect.element(app).toBeVisible();

    rightClick(app.element());
    await screen.getByRole("menuitem", { name: "Expand", exact: true }).click();
    await expect.element(app).toHaveAttribute("aria-expanded", "true");

    rightClick(app.element());
    await expect
      .element(screen.getByRole("menuitem", { name: "Expand", exact: true }))
      .toHaveAttribute("aria-disabled", "true");
    await screen
      .getByRole("menuitem", { name: "Collapse", exact: true })
      .click();
    await expect.element(app).toHaveAttribute("aria-expanded", "false");
    expect(searchOf(screen).expanded_ids).toBeUndefined();
  });

  it("collapses a box with everything open in it from its menu", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    await expect.element(app).toBeVisible();

    rightClick(app.element());
    await expect
      .element(screen.getByRole("menuitem", { name: "Collapse all" }))
      .toHaveAttribute("aria-disabled", "true");
    await screen.getByRole("menuitem", { name: "Select", exact: true }).click();
    await expand(screen, "fixture-app", "org.hg.fixture");

    rightClick(app.element());
    await screen.getByRole("menuitem", { name: "Collapse all" }).click();

    await expect.element(app).toHaveAttribute("aria-expanded", "false");
    expect(searchOf(screen).expanded_ids).toBeUndefined();
    // Opening it again opens one level only.
    await expand(screen, "fixture-app");
    await expect
      .element(box(screen, "org.hg.fixture"))
      .toHaveAttribute("aria-expanded", "false");
  });

  it("expands all of a box from its menu, down to the last container", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    await expect.element(app).toBeVisible();

    rightClick(app.element());
    await screen.getByRole("menuitem", { name: "Expand all" }).click();

    // Everything in it is open, down to the cycle's members.
    await expect
      .element(box(screen, "cycle"))
      .toHaveAttribute("aria-expanded", "true");
    await expect.element(box(screen, "alpha")).toBeVisible();
    expect(searchOf(screen).expanded_ids?.length).toBeGreaterThan(5);

    rightClick(app.element());
    await screen.getByRole("menuitem", { name: "Collapse all" }).click();

    await expect.element(app).toHaveAttribute("aria-expanded", "false");
    expect(searchOf(screen).expanded_ids).toBeUndefined();
  });

  it("collapses the whole map from the canvas's menu", async () => {
    const screen = await renderMap();
    await expand(screen, "fixture-app", "org.hg.fixture");
    rightClick(canvas());
    await screen.getByRole("menuitem", { name: "Collapse all" }).click();

    await expect
      .element(box(screen, "fixture-app"))
      .toHaveAttribute("aria-expanded", "false");
    expect(searchOf(screen).expanded_ids).toBeUndefined();

    // With nothing open, there is nothing to collapse.
    rightClick(canvas());
    await expect
      .element(screen.getByRole("menuitem", { name: "Collapse all" }))
      .toHaveAttribute("aria-disabled", "true");
  });

  it("answers in the hierarchy view from the URL: its groups are containers", async () => {
    const screen = await renderMap("?view=layers");

    await expand(screen, "fixture-app", "org.hg.fixture", "locations");

    await expect.element(box(screen, "application")).toBeVisible();
  });

  describe("given dependencies", () => {
    it("opens the map just far enough to draw them, and keeps that in the URL", async () => {
      const screen = await renderMap(`?dependency_ids=${GIVEN}`);

      // app → lib (two dependencies, one arrow) and beta → gamma.
      await expect.poll(() => arrowsOf("given")).toBe(2);
      await expect.element(box(screen, "app")).toBeVisible();
      await expect.element(box(screen, "lib")).toBeVisible();
      await expect.element(box(screen, "gamma")).toBeVisible();
      // The given beta → gamma is drawn instead of the feedback arrow.
      expect(arrowsOf("feedback")).toBe(0);
      expect(searchOf(screen).expanded_ids?.length).toBeGreaterThan(0);
      await expect
        .element(
          screen.getByRole("button", { name: "Zoom to the dependencies" }),
        )
        .toBeVisible();
    });

    it("keeps the expansion of the URL: both ends in one box, no arrow", async () => {
      const root =
        structureGiven.entries[0].data.hierarchicalGraph
          .aggregatedDependencies[0].sourceNode!.displayNode.predecessors;
      // The module, just below the root.
      const module = root[root.length - 2].id;
      const screen = await renderMap(
        `?dependency_ids=${GIVEN}&expanded_ids=${module}`,
      );

      await expect.element(box(screen, "org.hg.fixture")).toBeVisible();
      await expect
        .element(screen.getByRole("button", { name: "Only these" }))
        .toBeVisible();
      expect(searchOf(screen).expanded_ids).toEqual([module]);
      expect(arrowsOf("given")).toBe(0);
    });

    it("draws only the given dependencies when switched: their boxes and the boxes around them", async () => {
      const screen = await renderMap(`?dependency_ids=${GIVEN}`);
      await expect.poll(() => arrowsOf("given")).toBe(2);
      await expect.element(box(screen, "rel")).toBeVisible();

      await screen.getByRole("button", { name: "Only these" }).click();

      await expect.poll(() => searchOf(screen).given_only).toBe(true);
      await expect.element(box(screen, "rel")).not.toBeInTheDocument();
      await expect
        .element(box(screen, "External Types"))
        .not.toBeInTheDocument();
      await expect.element(box(screen, "app")).toBeVisible();
      await expect.element(box(screen, "lib")).toBeVisible();
      await expect.element(box(screen, "beta")).toBeVisible();
      // In the tangle, only the members the dependency joins.
      await expect.element(box(screen, "alpha")).not.toBeInTheDocument();
      expect(arrowsOf("given")).toBe(2);
      expect(document.querySelectorAll(".lsm-dim")).toHaveLength(0);

      await screen.getByRole("button", { name: "Only these" }).click();

      await expect.element(box(screen, "rel")).toBeVisible();
    });
  });
});
