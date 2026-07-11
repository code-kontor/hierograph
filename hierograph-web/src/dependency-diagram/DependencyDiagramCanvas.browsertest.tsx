import type { ElkNode } from "elkjs/lib/elk.bundled.js";
import { describe, expect, it, vi } from "vitest";
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

describe("DependencyDiagramCanvas", () => {
  it("renders and sizes the canvas to its container without error", async () => {
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas rootNode={FAKE_ROOT_NODE} />
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
        <DependencyDiagramCanvas rootNode={FAKE_ROOT_NODE} />
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

  it("activates the hit node on a click without movement", async () => {
    const onNodeActivate = vi.fn();
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas
          rootNode={NODE_ROOT_NODE}
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

    await expect
      .poll(() => onNodeActivate.mock.calls.length)
      .toBeGreaterThan(0);
    expect(onNodeActivate).toHaveBeenCalledWith("n1", "pkg.one");
  });

  it("does not activate on a drag past the click threshold", async () => {
    const onNodeActivate = vi.fn();
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas
          rootNode={NODE_ROOT_NODE}
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

    expect(onNodeActivate).not.toHaveBeenCalled();
  });

  it("shows a pointer cursor while hovering a node", async () => {
    const screen = await render(
      <div style={{ width: 400, height: 300 }}>
        <DependencyDiagramCanvas rootNode={NODE_ROOT_NODE} />
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
});
