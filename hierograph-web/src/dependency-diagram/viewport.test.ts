import { describe, expect, it } from "vitest";

import {
  clampScale,
  fitToView,
  MAX_SCALE,
  MIN_SCALE,
  pan,
  screenToWorld,
  type Viewport,
  worldToScreen,
  zoomAt,
} from "./viewport";

const VIEWPORTS: Viewport[] = [
  { scale: 1, translateX: 0, translateY: 0 },
  { scale: 2, translateX: 50, translateY: -30 },
  { scale: 0.5, translateX: -100, translateY: 200 },
];

function expectClose(a: number, b: number) {
  expect(a).toBeCloseTo(b, 6);
}

describe("worldToScreen / screenToWorld", () => {
  it("round-trips for various points and viewports", () => {
    for (const vp of VIEWPORTS) {
      for (const [x, y] of [
        [0, 0],
        [10, 20],
        [-5, 300],
      ]) {
        const screen = worldToScreen(vp, x, y);
        const world = screenToWorld(vp, screen.x, screen.y);
        expectClose(world.x, x);
        expectClose(world.y, y);
      }
    }
  });
});

describe("zoomAt", () => {
  it("keeps the world point under the cursor fixed", () => {
    const vp: Viewport = { scale: 1, translateX: 10, translateY: 20 };
    const sx = 150;
    const sy = 80;
    const worldBefore = screenToWorld(vp, sx, sy);

    const zoomed = zoomAt(vp, sx, sy, 1.5);

    const screenAfter = worldToScreen(zoomed, worldBefore.x, worldBefore.y);
    expectClose(screenAfter.x, sx);
    expectClose(screenAfter.y, sy);
  });

  it("clamps scale and still keeps the anchor point fixed", () => {
    const vp: Viewport = { scale: 1, translateX: 0, translateY: 0 };
    const sx = 40;
    const sy = 60;
    const worldBefore = screenToWorld(vp, sx, sy);

    const zoomedUp = zoomAt(vp, sx, sy, 1000);
    expect(zoomedUp.scale).toBe(MAX_SCALE);
    const screenAfterUp = worldToScreen(zoomedUp, worldBefore.x, worldBefore.y);
    expectClose(screenAfterUp.x, sx);
    expectClose(screenAfterUp.y, sy);

    const zoomedDown = zoomAt(vp, sx, sy, 0.0001);
    expect(zoomedDown.scale).toBe(MIN_SCALE);
    const screenAfterDown = worldToScreen(
      zoomedDown,
      worldBefore.x,
      worldBefore.y,
    );
    expectClose(screenAfterDown.x, sx);
    expectClose(screenAfterDown.y, sy);
  });
});

describe("fitToView", () => {
  it("centers the content", () => {
    const vp = fitToView(100, 50, 400, 400, 0);
    const topLeft = worldToScreen(vp, 0, 0);
    const bottomRight = worldToScreen(vp, 100, 50);
    const centerX = (topLeft.x + bottomRight.x) / 2;
    const centerY = (topLeft.y + bottomRight.y) / 2;
    expectClose(centerX, 200);
    expectClose(centerY, 200);
  });

  it("scales content to fill up to the padding", () => {
    const vp = fitToView(100, 100, 300, 300, 24);
    expectClose(vp.scale, (300 - 48) / 100);
  });

  it("does not scale small content above MAX_SCALE", () => {
    const vp = fitToView(1, 1, 1000, 1000, 24);
    expect(vp.scale).toBe(MAX_SCALE);
  });

  it("falls back to identity when content size is 0 or undefined", () => {
    expect(fitToView(0, 0, 400, 400, 24)).toEqual({
      scale: 1,
      translateX: 0,
      translateY: 0,
    });
    expect(fitToView(undefined, undefined, 400, 400, 24)).toEqual({
      scale: 1,
      translateX: 0,
      translateY: 0,
    });
  });

  it("falls back to identity when view size is 0", () => {
    expect(fitToView(100, 100, 0, 0, 24)).toEqual({
      scale: 1,
      translateX: 0,
      translateY: 0,
    });
  });
});

describe("pan", () => {
  it("translates without changing scale", () => {
    const vp: Viewport = { scale: 2, translateX: 10, translateY: -5 };
    const panned = pan(vp, 30, -20);
    expect(panned).toEqual({ scale: 2, translateX: 40, translateY: -25 });
  });
});

describe("clampScale", () => {
  it("clamps to [MIN_SCALE, MAX_SCALE]", () => {
    expect(clampScale(0)).toBe(MIN_SCALE);
    expect(clampScale(100)).toBe(MAX_SCALE);
    expect(clampScale(1)).toBe(1);
  });
});
