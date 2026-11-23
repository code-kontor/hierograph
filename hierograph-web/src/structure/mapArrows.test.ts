import { describe, expect, it } from "vitest";

import { spread } from "./mapArrows";

describe("spread", () => {
  it("leaves ends that are far enough apart where they want to be", () => {
    expect(spread([10, 50, 90], 0, 100)).toEqual([10, 50, 90]);
  });

  it("spreads a crowd around where it wants to be", () => {
    expect(spread([50, 50, 50], 0, 100)).toEqual([32, 50, 68]);
  });

  it("keeps a lone end in place while a crowd at the other end spreads", () => {
    // One end on the left, five crowding the right edge of a wide box: the
    // crowd moves left as far as it must, the lone end not at all.
    const placed = spread([150, 1500, 1500, 1500, 1500, 1500], 0, 1500);
    expect(placed[0]).toBe(150);
    expect(placed.slice(1)).toEqual([1428, 1446, 1464, 1482, 1500]);
  });

  it("pushes a crowd off an edge it can't fit against", () => {
    expect(spread([0, 0, 0], 0, 100)).toEqual([0, 18, 36]);
  });

  it("shrinks the spacing on an edge too short for it", () => {
    expect(spread([0, 0, 0, 0, 0], 0, 40)).toEqual([0, 10, 20, 30, 40]);
  });

  it("keeps every end at least the spacing from the next", () => {
    const placed = spread([5, 6, 7, 40, 41, 300, 301, 302], 0, 400);
    for (let i = 1; i < placed.length; i++) {
      expect(placed[i] - placed[i - 1]).toBeGreaterThanOrEqual(18 - 1e-9);
    }
    expect(placed[0]).toBeGreaterThanOrEqual(0);
    expect(placed.at(-1)).toBeLessThanOrEqual(400);
  });
});
