import type { ElkNode } from "elkjs/lib/elk.bundled.js";
import { describe, expect, it } from "vitest";
import { render } from "vitest-browser-react";

import { DependencyDiagramCanvas } from "./DependencyDiagramCanvas";

const FAKE_ROOT_NODE: ElkNode = {
  id: "root",
  width: 200,
  height: 150,
  children: [],
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
});
