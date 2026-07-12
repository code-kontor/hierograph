import type { ElkNode } from "elkjs/lib/elk.bundled.js";
import { describe, expect, it, vi } from "vitest";
import { page, userEvent } from "vitest/browser";
import { render } from "vitest-browser-react";

import { DependencyDiagramCanvas } from "./DependencyDiagramCanvas";

const FAKE_ROOT_NODE: ElkNode = {
  id: "root",
  width: 200,
  height: 150,
  children: [],
  edges: [],
};

// Single child node filling the whole root area, so a click anywhere inside
// the fitted canvas is guaranteed to hit it regardless of the fit-to-view scale.
const NODE_ROOT_NODE: ElkNode = {
  id: "root",
  width: 400,
  height: 300,
  children: [
    {
      id: "n1",
      x: 0,
      y: 0,
      width: 400,
      height: 300,
      labels: [{ text: "pkg.one" }],
    },
  ],
  edges: [],
};

// A container filling the root, with an inner child filling the container, so a
// center click descends to the innermost node (innermost hit wins).
const NESTED_ROOT_NODE: ElkNode = {
  id: "root",
  width: 400,
  height: 300,
  children: [
    {
      id: "container",
      x: 0,
      y: 0,
      width: 400,
      height: 300,
      labels: [{ text: "pkg.outer" }],
      children: [
        {
          id: "inner",
          x: 0,
          y: 0,
          width: 400,
          height: 300,
          labels: [{ text: "pkg.inner" }],
        },
      ],
      edges: [],
    },
  ],
  edges: [],
};

describe("DependencyDiagramCanvas", () => {
  it("renders and sizes the canvas to its container without error", async () => {
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas rootNode={FAKE_ROOT_NODE} labelFormat="full" />
      </div>,
    );

    const canvas = screen.getByTestId("dependency-diagram-canvas");

    await expect
      .poll(() => (canvas.element() as HTMLCanvasElement).width)
      .toBeGreaterThan(0);
    await expect
      .poll(() => (canvas.element() as HTMLCanvasElement).height)
      .toBeGreaterThan(0);
  });

  it("handles a wheel event without throwing", async () => {
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas rootNode={FAKE_ROOT_NODE} labelFormat="full" />
      </div>,
    );

    const canvas = screen.getByTestId("dependency-diagram-canvas");
    await expect.poll(() => canvas.element().clientWidth).toBeGreaterThan(0);

    canvas.element().dispatchEvent(
      new WheelEvent("wheel", {
        deltaY: -100,
        clientX: 200,
        clientY: 150,
        bubbles: true,
        cancelable: true,
      }),
    );
  });

  it("click on the box is inert (no toggle, no drill)", async () => {
    const onNodeActivate = vi.fn();
    const onNodeToggleExpand = vi.fn();
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas
          rootNode={NODE_ROOT_NODE}
          labelFormat="full"
          onNodeActivate={onNodeActivate}
          onNodeToggleExpand={onNodeToggleExpand}
        />
      </div>,
    );

    const canvas = screen.getByTestId("dependency-diagram-canvas");
    await expect.poll(() => canvas.element().clientWidth).toBeGreaterThan(0);

    const rect = canvas.element().getBoundingClientRect();
    const clientX = rect.left + rect.width / 2;
    const clientY = rect.top + rect.height / 2;

    canvas.element().dispatchEvent(
      new PointerEvent("pointerdown", {
        clientX,
        clientY,
        pointerId: 1,
        bubbles: true,
      }),
    );
    canvas.element().dispatchEvent(
      new PointerEvent("pointerup", {
        clientX,
        clientY,
        pointerId: 1,
        bubbles: true,
      }),
    );

    expect(onNodeToggleExpand).not.toHaveBeenCalled();
    expect(onNodeActivate).not.toHaveBeenCalled();
  });

  it("double click does not drill", async () => {
    const onNodeActivate = vi.fn();
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas
          rootNode={NODE_ROOT_NODE}
          labelFormat="full"
          onNodeActivate={onNodeActivate}
        />
      </div>,
    );

    const canvas = screen.getByTestId("dependency-diagram-canvas");
    await expect.poll(() => canvas.element().clientWidth).toBeGreaterThan(0);

    const rect = canvas.element().getBoundingClientRect();
    const clientX = rect.left + rect.width / 2;
    const clientY = rect.top + rect.height / 2;

    canvas.element().dispatchEvent(
      new MouseEvent("dblclick", {
        clientX,
        clientY,
        bubbles: true,
      }),
    );

    expect(onNodeActivate).not.toHaveBeenCalled();
  });

  it("click on the innermost nested node is also inert", async () => {
    const onNodeToggleExpand = vi.fn();
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas
          rootNode={NESTED_ROOT_NODE}
          labelFormat="full"
          onNodeToggleExpand={onNodeToggleExpand}
        />
      </div>,
    );

    const canvas = screen.getByTestId("dependency-diagram-canvas");
    await expect.poll(() => canvas.element().clientWidth).toBeGreaterThan(0);

    const rect = canvas.element().getBoundingClientRect();
    const clientX = rect.left + rect.width / 2;
    const clientY = rect.top + rect.height / 2;

    canvas.element().dispatchEvent(
      new PointerEvent("pointerdown", {
        clientX,
        clientY,
        pointerId: 1,
        bubbles: true,
      }),
    );
    canvas.element().dispatchEvent(
      new PointerEvent("pointerup", {
        clientX,
        clientY,
        pointerId: 1,
        bubbles: true,
      }),
    );

    expect(onNodeToggleExpand).not.toHaveBeenCalled();
  });

  it("does not toggle expand or drill on a drag past the click threshold", async () => {
    const onNodeActivate = vi.fn();
    const onNodeToggleExpand = vi.fn();
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas
          rootNode={NODE_ROOT_NODE}
          labelFormat="full"
          onNodeActivate={onNodeActivate}
          onNodeToggleExpand={onNodeToggleExpand}
        />
      </div>,
    );

    const canvas = screen.getByTestId("dependency-diagram-canvas");
    await expect.poll(() => canvas.element().clientWidth).toBeGreaterThan(0);

    const rect = canvas.element().getBoundingClientRect();
    const clientX = rect.left + rect.width / 2;
    const clientY = rect.top + rect.height / 2;

    canvas.element().dispatchEvent(
      new PointerEvent("pointerdown", {
        clientX,
        clientY,
        pointerId: 1,
        bubbles: true,
      }),
    );
    canvas.element().dispatchEvent(
      new PointerEvent("pointermove", {
        clientX: clientX + 20,
        clientY: clientY + 20,
        pointerId: 1,
        bubbles: true,
      }),
    );
    canvas.element().dispatchEvent(
      new PointerEvent("pointerup", {
        clientX: clientX + 20,
        clientY: clientY + 20,
        pointerId: 1,
        bubbles: true,
      }),
    );

    expect(onNodeToggleExpand).not.toHaveBeenCalled();
    expect(onNodeActivate).not.toHaveBeenCalled();
  });

  it("drags the hit node to a new position, mutating the root node in place", async () => {
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas rootNode={NODE_ROOT_NODE} labelFormat="full" />
      </div>,
    );

    const canvas = screen.getByTestId("dependency-diagram-canvas");
    await expect.poll(() => canvas.element().clientWidth).toBeGreaterThan(0);

    const rect = canvas.element().getBoundingClientRect();
    const clientX = rect.left + rect.width / 2;
    const clientY = rect.top + rect.height / 2;
    const n1 = NODE_ROOT_NODE.children![0];
    const startX = n1.x;
    const startY = n1.y;

    canvas.element().dispatchEvent(
      new PointerEvent("pointerdown", {
        clientX,
        clientY,
        pointerId: 1,
        bubbles: true,
      }),
    );
    canvas.element().dispatchEvent(
      new PointerEvent("pointermove", {
        clientX: clientX + 20,
        clientY: clientY + 20,
        pointerId: 1,
        bubbles: true,
      }),
    );
    canvas.element().dispatchEvent(
      new PointerEvent("pointerup", {
        clientX: clientX + 20,
        clientY: clientY + 20,
        pointerId: 1,
        bubbles: true,
      }),
    );

    expect(n1.x).not.toBe(startX);
    expect(n1.y).not.toBe(startY);
  });

  it("shows the hover toolbar and wires its buttons to the callback props", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });

    const onNodeActivate = vi.fn();
    const onNodeToggleExpand = vi.fn();
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas
          rootNode={NODE_ROOT_NODE}
          labelFormat="full"
          onNodeActivate={onNodeActivate}
          onNodeToggleExpand={onNodeToggleExpand}
        />
      </div>,
    );

    const canvas = screen.getByTestId("dependency-diagram-canvas");
    await expect.poll(() => canvas.element().clientWidth).toBeGreaterThan(0);

    const rect = canvas.element().getBoundingClientRect();
    const clientX = rect.left + rect.width / 2;
    const clientY = rect.top + rect.height / 2;

    canvas.element().dispatchEvent(
      new PointerEvent("pointermove", {
        clientX,
        clientY,
        pointerId: 1,
        bubbles: true,
      }),
    );

    await expect
      .element(page.getByRole("button", { name: "Expand" }))
      .toBeVisible();

    await userEvent.click(page.getByRole("button", { name: "Expand" }));
    expect(onNodeToggleExpand).toHaveBeenCalledWith("n1");

    await userEvent.click(
      page.getByRole("button", { name: "Drill into node" }),
    );
    expect(onNodeActivate).toHaveBeenCalledWith("n1", "pkg.one");

    await userEvent.click(
      page.getByRole("button", { name: "Copy fully-qualified name" }),
    );
    await expect.poll(() => writeText.mock.calls.length).toBeGreaterThan(0);
    expect(writeText).toHaveBeenCalledWith("pkg.one");
    await expect
      .poll(() => document.querySelector(".lucide-check") !== null)
      .toBe(true);
  });

  it("shows collapse (minus) instead of expand (plus) for an already-expanded node", async () => {
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas
          rootNode={NODE_ROOT_NODE}
          labelFormat="full"
          expandedIds={new Set(["n1"])}
        />
      </div>,
    );

    const canvas = screen.getByTestId("dependency-diagram-canvas");
    await expect.poll(() => canvas.element().clientWidth).toBeGreaterThan(0);

    const rect = canvas.element().getBoundingClientRect();
    const clientX = rect.left + rect.width / 2;
    const clientY = rect.top + rect.height / 2;

    canvas.element().dispatchEvent(
      new PointerEvent("pointermove", {
        clientX,
        clientY,
        pointerId: 1,
        bubbles: true,
      }),
    );

    await expect
      .element(page.getByRole("button", { name: "Collapse" }))
      .toBeVisible();
    expect(
      page.getByRole("button", { name: "Expand" }).elements(),
    ).toHaveLength(0);
  });

  it("shows a pointer cursor while hovering a node", async () => {
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas rootNode={NODE_ROOT_NODE} labelFormat="full" />
      </div>,
    );

    const canvas = screen.getByTestId("dependency-diagram-canvas");
    await expect.poll(() => canvas.element().clientWidth).toBeGreaterThan(0);

    const rect = canvas.element().getBoundingClientRect();
    const clientX = rect.left + rect.width / 2;
    const clientY = rect.top + rect.height / 2;

    canvas.element().dispatchEvent(
      new PointerEvent("pointermove", {
        clientX,
        clientY,
        pointerId: 1,
        bubbles: true,
      }),
    );

    await expect
      .poll(() => (canvas.element() as HTMLElement).style.cursor)
      .toBe("pointer");
  });

  it("shows a hover tooltip after a delay and hides it on pointer leave", async () => {
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas rootNode={NODE_ROOT_NODE} labelFormat="full" />
      </div>,
    );

    const canvas = screen.getByTestId("dependency-diagram-canvas");
    await expect.poll(() => canvas.element().clientWidth).toBeGreaterThan(0);

    const rect = canvas.element().getBoundingClientRect();
    const clientX = rect.left + rect.width / 2;
    const clientY = rect.top + rect.height / 2;

    canvas.element().dispatchEvent(
      new PointerEvent("pointermove", {
        clientX,
        clientY,
        pointerId: 1,
        bubbles: true,
      }),
    );

    await expect.element(page.getByText("one", { exact: true })).toBeVisible();
    await expect
      .element(page.getByText("java.package", { exact: true }))
      .toBeVisible();
    await expect
      .element(page.getByText("pkg.one", { exact: true }))
      .toBeVisible();

    // React derives onPointerLeave from the native pointerout event (leave
    // events don't bubble), so the test dispatches pointerout, not pointerleave.
    canvas.element().dispatchEvent(
      new PointerEvent("pointerout", {
        clientX,
        clientY,
        pointerId: 1,
        bubbles: true,
        relatedTarget: document.body,
      }),
    );

    await expect
      .poll(() => document.body.textContent?.includes("java.package"))
      .toBe(false);
  });
});
