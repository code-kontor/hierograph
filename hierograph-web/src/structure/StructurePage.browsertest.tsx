import { afterEach, describe, expect, it } from "vitest";

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
    pinned_id?: string;
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
  afterEach(() => {
    localStorage.removeItem("structure-trace-on-hover");
  });

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

  it("traces a hovered box: rings on what it uses, until the pointer leaves", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");

    await app.hover();

    await expect.element(app).toHaveAttribute("aria-current", "true");
    await expect.element(box(screen, "External Types")).toHaveClass("lsm-uses");

    await screen.getByText("Structure", { exact: true }).hover();

    await expect
      .element(box(screen, "External Types"))
      .not.toHaveClass("lsm-uses");
    await expect.element(app).not.toHaveAttribute("aria-current");
  });

  it("traces nothing on hover when switched off in the corner, pins on Shift + click, and remembers it", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    const toggle = screen.getByRole("button", { name: "Trace on hover" });
    await expect.element(toggle).toHaveAttribute("aria-pressed", "true");

    await toggle.click();
    await app.hover();

    await expect.element(toggle).toHaveAttribute("aria-pressed", "false");
    await expect.element(app).not.toHaveAttribute("aria-current");
    expect(localStorage.getItem("structure-trace-on-hover")).toBe("false");

    // Shift + click pins the box instead; again, it unpins.
    await app.click({ modifiers: ["Shift"] });
    await expect.element(app).toHaveAttribute("aria-current", "true");
    expect(searchOf(screen).pinned_id).toBeDefined();
    await app.click({ modifiers: ["Shift"] });
    await expect.element(app).not.toHaveAttribute("aria-current");
    expect(searchOf(screen).pinned_id).toBeUndefined();

    await toggle.click();
    await screen.getByText("Structure", { exact: true }).hover();
    await app.hover();

    await expect.element(app).toHaveAttribute("aria-current", "true");
  });

  it("greys out what a hovered box isn't coupled to, until the pointer leaves", async () => {
    const screen = await renderMap();
    await expand(screen, "fixture-app", "org.hg.fixture", "basic", "cycle");
    const alpha = box(screen, "alpha");

    await alpha.hover();

    await expect
      .poll(() => document.querySelectorAll(".lsm-dim").length)
      .toBeGreaterThan(0);
    await expect.element(alpha).not.toHaveClass("lsm-dim");
    // The containers it lies in stay as they are.
    await expect.element(box(screen, "cycle")).not.toHaveClass("lsm-dim");

    await screen.getByText("Structure", { exact: true }).hover();

    await expect
      .poll(() => document.querySelectorAll(".lsm-dim").length)
      .toBe(0);
  });

  it("pins a box's arrows from its context menu: in the URL, until unpinned", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    await expect.element(app).toBeVisible();

    rightClick(app.element());
    const menu = screen.getByRole("menu");
    await expect.element(menu).toBeVisible();
    expect(
      menu
        .getByRole("menuitem")
        .elements()
        .map((element) => element.textContent),
    ).toEqual([
      "Pin these arrows",
      "Expand",
      "Expand all",
      "Collapse",
      "Collapse all",
    ]);
    await expect
      .element(screen.getByRole("menuitem", { name: "Collapse", exact: true }))
      .toHaveAttribute("aria-disabled", "true");
    await screen.getByRole("menuitem", { name: "Pin these arrows" }).click();

    expect(searchOf(screen).pinned_id).toBeDefined();
    // Hovering another box keeps the pinned box's arrows.
    await box(screen, "External Types").hover();
    await new Promise((resolve) => setTimeout(resolve, 300));
    await expect.element(app).toHaveAttribute("aria-current", "true");
    await expect.element(box(screen, "External Types")).toHaveClass("lsm-uses");

    await screen.getByRole("button", { name: "Unpin the arrows" }).click();

    expect(searchOf(screen).pinned_id).toBeUndefined();
    await expect.element(app).not.toHaveAttribute("aria-current");
  });

  it("opens an arrow's menu on a right click: pin an end, expand an end", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    await expect.element(app).toBeVisible();
    // Pinned, not hovered: the pointer could hover the other end meanwhile,
    // which the menu would then name instead.
    rightClick(app.element());
    await screen.getByRole("menuitem", { name: "Pin these arrows" }).click();
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
    expect(
      menu
        .getByRole("menuitem")
        .elements()
        .map((element) => element.textContent),
    ).toEqual([
      "Unpin the arrows",
      "Pin the arrows of External Types",
      "Expand fixture-app",
      "Expand External Types",
    ]);
    await screen
      .getByRole("menuitem", { name: "Pin the arrows of External Types" })
      .click();

    expect(searchOf(screen).pinned_id).toBeDefined();
    await expect
      .element(box(screen, "External Types"))
      .toHaveAttribute("aria-current", "true");
  });

  it("draws an expanded box's arrows from its contents", async () => {
    const screen = await renderMap();
    const app = box(screen, "fixture-app");
    await expect.element(app).toBeVisible();
    rightClick(app.element());
    await screen.getByRole("menuitem", { name: "Pin these arrows" }).click();
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
    await screen.getByRole("menuitem", { name: "Pin these arrows" }).click();
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
    const canvas = document.querySelector(".lsm-canvas")!;

    rightClick(canvas);
    await screen.getByRole("menuitem", { name: "Collapse all" }).click();

    await expect
      .element(box(screen, "fixture-app"))
      .toHaveAttribute("aria-expanded", "false");
    expect(searchOf(screen).expanded_ids).toBeUndefined();

    // With nothing open, there is nothing to collapse.
    rightClick(canvas);
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
          .aggregatedDependencies[0].sourceNode!.predecessors;
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
