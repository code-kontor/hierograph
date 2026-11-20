import { describe, expect, it } from "vitest";

import { AppFrame } from "@/frame/AppFrame";
import type { Screen } from "@/frame/screens";
import { handlersFor } from "@/testing/msw/handlers";
import { worker } from "@/testing/msw/worker";
import { renderWithRouter } from "@/testing/render";

import { HierarchyTree } from "./HierarchyTree";

const SCREENS = [
  { path: "/", label: "Start", needsGraph: false },
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
    strokeWidth: g.querySelector("path")?.getAttribute("stroke-width"),
    dotted: g.querySelector("path")?.hasAttribute("stroke-dasharray") ?? false,
    colour: g.querySelector("path")?.getAttribute("class") ?? "",
    arrowhead: g.querySelector("[data-arrowhead]") !== null,
    title: g.querySelector("title")?.textContent ?? "",
  }));
}

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
    await item(screen, name).getByRole("button").first().click();
    await expect
      .element(item(screen, name))
      .toHaveAttribute("aria-expanded", "true");
  }
}

describe("HierarchyTree", () => {
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
    await expect
      .element(item(screen, "fixture-app").getByRole("button").first())
      .not.toHaveAttribute("title");
  });

  it("loads a node's children when it is expanded, named relative to it", async () => {
    const screen = await renderTree();

    await expand(
      screen,
      "fixture-app",
      "org",
      "hg",
      "fixture",
      "basic",
      "core",
    );

    await expect.element(item(screen, "PlainClass")).toBeVisible();
    await expect.element(item(screen, "Pair")).toBeVisible();
  });

  it("orders siblings by their dependencies: dependents on top, the used at the bottom", async () => {
    const screen = await renderTree();
    await expand(screen, "fixture-app", "org", "hg", "fixture", "basic");

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
    await expand(
      screen,
      "fixture-app",
      "org",
      "hg",
      "fixture",
      "basic",
      "cycle",
    );

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

  it("keeps a type's members in source order", async () => {
    const screen = await renderTree();
    await expand(
      screen,
      "fixture-app",
      "org",
      "hg",
      "fixture",
      "basic",
      "core",
      "PlainClass",
    );

    expect(await childLabels(screen, "PlainClass")).toEqual([
      "int publicField",
      "int protectedField",
      "int packagePrivateField",
      "int privateField",
      "void <init>()",
      "void publicMethod()",
      "void protectedMethod()",
      "void packagePrivateMethod()",
      "void privateMethod()",
    ]);
  });

  it("collapses an expanded node", async () => {
    const screen = await renderTree();
    await expand(screen, "fixture-app");
    await expect.element(item(screen, "org")).toBeVisible();

    await item(screen, "fixture-app").getByRole("button").first().click();

    await expect
      .element(item(screen, "fixture-app"))
      .toHaveAttribute("aria-expanded", "false");
    await expect.element(item(screen, "org")).not.toBeInTheDocument();
  });

  it("shows a hierarchy view's groups, with their members named relative to the level above", async () => {
    const screen = await renderTree("?view=layers");

    await expect
      .element(screen.getByRole("banner").getByText("layers"))
      .toBeVisible();
    await expand(screen, "fixture-app", "org", "hg", "fixture", "locations");

    await expect.element(item(screen, "application")).toBeVisible();
    await expect.element(item(screen, "library")).toBeVisible();

    await expand(screen, "application");

    await expect.element(item(screen, "app")).toBeVisible();
  });

  it("draws no arcs until a row is hovered", async () => {
    const screen = await renderTree();
    await expand(
      screen,
      "fixture-app",
      "org",
      "hg",
      "fixture",
      "basic",
      "cycle",
    );
    await expect.element(item(screen, "gamma")).toBeVisible();

    expect(arcs()).toEqual([]);
  });

  it("draws the hovered row's arcs: with the order and against it", async () => {
    const screen = await renderTree();
    await expand(
      screen,
      "fixture-app",
      "org",
      "hg",
      "fixture",
      "basic",
      "cycle",
    );
    await expect.element(item(screen, "gamma")).toBeVisible();
    const cycle = new Set(["gamma", "alpha", "beta"].map(nodeId));

    // The last of a cycle depends on one above it: an arc against the order.
    await item(screen, "beta").getByRole("button").first().hover();
    const beta = nodeId("beta");
    await expect.poll(() => arcs().length).toBeGreaterThan(0);
    expect(arcs().every((arc) => arc.from === beta || arc.to === beta)).toBe(
      true,
    );
    const up = arcs().filter((arc) => arc.direction === "up");
    expect(up.length).toBeGreaterThan(0);
    for (const arc of up) {
      expect(cycle.has(arc.from) && cycle.has(arc.to)).toBe(true);
      expect(arc.dotted).toBe(true);
    }
    // Seen from beta: its own dependencies outgoing (blue), the others'
    // incoming (grey), whichever way they run; each with an arrowhead; all of
    // one thickness.
    for (const arc of arcs()) {
      expect(arc.relation).toBe(arc.from === beta ? "outgoing" : "incoming");
      expect(arc.colour).toContain(
        arc.relation === "outgoing" ? "--hg-accent" : "--hg-fg-subtle",
      );
      expect(arc.arrowhead).toBe(true);
    }
    expect(new Set(arcs().map((arc) => arc.strokeWidth)).size).toBe(1);
    // The tooltip names both ends and the weight.
    expect(arcs()[0].title).toMatch(/^.+ → .+: weight \d+$/);

    // The first of a cycle depends on one below it: an arc with the order.
    await item(screen, "gamma").getByRole("button").first().hover();
    const gamma = nodeId("gamma");
    await expect
      .poll(() => arcs().every((arc) => arc.from === gamma || arc.to === gamma))
      .toBe(true);
    expect(arcs().some((arc) => arc.direction === "down")).toBe(true);
    expect(
      arcs()
        .filter((arc) => arc.direction === "down")
        .every((arc) => !arc.dotted),
    ).toBe(true);
  });

  it("removes the arcs when the pointer leaves the row", async () => {
    const screen = await renderTree();
    await expand(
      screen,
      "fixture-app",
      "org",
      "hg",
      "fixture",
      "basic",
      "cycle",
    );
    await item(screen, "alpha").getByRole("button").first().hover();
    await expect.poll(() => arcs().length).toBeGreaterThan(0);

    await screen.getByRole("tree").unhover();
    await screen.getByText("Hierarchy", { exact: true }).hover();

    await expect.poll(() => arcs().length).toBe(0);
  });

  it("draws the whole cycle when its bar is hovered", async () => {
    const screen = await renderTree();
    await expand(
      screen,
      "fixture-app",
      "org",
      "hg",
      "fixture",
      "basic",
      "cycle",
    );
    await expect.element(item(screen, "gamma")).toBeVisible();
    const cycle = new Set(["gamma", "alpha", "beta"].map(nodeId));

    // The tests run without the stylesheet, so the bar has no size to hover
    // with the pointer: the pointer arrives at it as an event.
    item(screen, "alpha")
      .element()
      .querySelector("[data-cycle-bar]")
      ?.dispatchEvent(new MouseEvent("mouseover", { bubbles: true }));

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

    // Back on the row: its own arcs again.
    await item(screen, "alpha").getByText("alpha").hover();
    const alpha = nodeId("alpha");
    await expect
      .poll(() => arcs().every((arc) => arc.from === alpha || arc.to === alpha))
      .toBe(true);
    expect(arcs().every((arc) => arc.relation !== "cycle")).toBe(true);
  });

  it("draws the arcs to External Types, with the order", async () => {
    const screen = await renderTree();
    await expand(screen, "fixture-app");
    await expect.element(item(screen, "org")).toBeVisible();

    await item(screen, "org").getByRole("button").first().hover();

    const org = nodeId("org");
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

    await item(screen, "util").getByRole("button").first().hover();

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
