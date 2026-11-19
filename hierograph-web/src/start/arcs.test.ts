import { describe, expect, it } from "vitest";

import { bracketPath, layoutArcs } from "./arcs";

const LAYOUT = { anchorX: 100, maxDown: 80, maxUp: 30 };

describe("layoutArcs", () => {
  const rows = [
    { id: "a", y: 10 },
    { id: "b", y: 35 },
    { id: "c", y: 60 },
  ];

  it("puts a dependency on a row below on the right, one on a row above on the left", () => {
    const arcs = layoutArcs(
      rows,
      [
        { from: "a", to: "c", weight: 3 },
        { from: "c", to: "b", weight: 1 },
      ],
      LAYOUT,
    );

    expect(arcs.map((arc) => [arc.from, arc.to, arc.direction])).toEqual([
      ["a", "c", "down"],
      ["c", "b", "up"],
    ]);
    // Down to the right of the anchors, up to their left.
    expect(arcs[0].path).toMatch(/^M 100 10 H 1\d\d(\.\d+)? Q 1\d\d/);
    expect(arcs[1].path).toMatch(/^M 100 60 H \d\d(\.\d+)? Q \d\d/);
  });

  it("draws long arcs first, so short ones end up on top", () => {
    const arcs = layoutArcs(
      rows,
      [
        { from: "a", to: "b", weight: 1 },
        { from: "a", to: "c", weight: 1 },
      ],
      LAYOUT,
    );

    expect(arcs.map((arc) => arc.to)).toEqual(["c", "b"]);
  });

  it("leaves out dependencies on rows not shown", () => {
    expect(
      layoutArcs(rows, [{ from: "a", to: "x", weight: 1 }], LAYOUT),
    ).toEqual([]);
  });
});

describe("bracketPath", () => {
  it("reaches out by the offset on the given side and comes back", () => {
    // Corners with a radius of at most 8 px.
    expect(bracketPath(100, 0, 50, -1, 20)).toBe(
      "M 100 0 H 88 Q 80 0 80 8 V 42 Q 80 50 88 50 H 100",
    );
    expect(bracketPath(100, 50, 0, 1, 20)).toBe(
      "M 100 50 H 112 Q 120 50 120 42 V 8 Q 120 0 112 0 H 100",
    );
    // A short span curves all the way.
    expect(bracketPath(100, 0, 6, -1, 20)).toBe(
      "M 100 0 H 83 Q 80 0 80 3 V 3 Q 80 6 83 6 H 100",
    );
  });
});
