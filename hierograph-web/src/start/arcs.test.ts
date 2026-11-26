import { describe, expect, it } from "vitest";

import { bracketPath, cycleDependencies, layoutArcs } from "./arcs";

const LAYOUT = { anchorX: 100, gap: 0, maxDown: 80, maxUp: 30 };

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
    // The arrowhead on the anchor of the row depended on.
  });

  it("starts the two sides a gap apart, around the anchor column", () => {
    const arcs = layoutArcs(
      rows,
      [
        { from: "a", to: "c", weight: 1 },
        { from: "c", to: "b", weight: 1 },
      ],
      { ...LAYOUT, gap: 6 },
    );

    expect(arcs[0].path).toMatch(/^M 103 10 H 1\d\d/);
    // An arc down ends a little past its anchor, its arrowhead clear of the
    // corners.
    expect(arcs[0].path).toMatch(/H 97$/);
    expect(arcs[1].path).toMatch(/^M 97 60 H \d\d/);
    expect(arcs[1].path).toMatch(/H 97$/);
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

  it("gives each arc of a side its own lane, evenly spaced, the shortest innermost", () => {
    const tall = [
      { id: "a", y: 0 },
      { id: "b", y: 100 },
      { id: "c", y: 110 },
      { id: "d", y: 400 },
    ];
    const arcs = layoutArcs(
      tall,
      [
        { from: "a", to: "d", weight: 1 },
        { from: "b", to: "d", weight: 1 },
        { from: "c", to: "d", weight: 1 },
      ],
      { ...LAYOUT, anchorX: 0 },
    );

    const bySpan = [...arcs].sort((x, y) => x.span - y.span);
    const offsets = bySpan.map((arc) => arc.offset);
    // Nested: longer spans further out, one lane pitch apart.
    expect(offsets).toEqual([8, 14, 20]);
  });

  it("lanes each group of a side on its own, the smaller centred on the larger", () => {
    // Two arcs into s from above (incoming), four from s down (outgoing):
    // all on the right, each set nested on its own.
    const tall = [
      { id: "a", y: 0 },
      { id: "b", y: 50 },
      { id: "s", y: 100 },
      { id: "c", y: 150 },
      { id: "d", y: 200 },
      { id: "e", y: 250 },
      { id: "f", y: 300 },
    ];
    const arcs = layoutArcs(
      tall,
      [
        { from: "a", to: "s", weight: 1 },
        { from: "b", to: "s", weight: 1 },
        { from: "s", to: "c", weight: 1 },
        { from: "s", to: "d", weight: 1 },
        { from: "s", to: "e", weight: 1 },
        { from: "s", to: "f", weight: 1 },
      ],
      { ...LAYOUT, anchorX: 0 },
      (dependency) => (dependency.from === "s" ? "outgoing" : "incoming"),
    );

    const offset = (from: string, to: string) =>
      arcs.find((arc) => arc.from === from && arc.to === to)?.offset;
    // Outgoing, four lanes: 8 to 26; incoming, two, centred on them.
    expect(["c", "d", "e", "f"].map((to) => offset("s", to))).toEqual([
      8, 14, 20, 26,
    ]);
    expect([offset("b", "s"), offset("a", "s")]).toEqual([14, 20]);
  });

  it("brings the lanes closer together when they don't fit, never onto one another", () => {
    const many = Array.from({ length: 12 }, (_, index) => ({
      id: `r${index}`,
      y: index * 20,
    }));
    const arcs = layoutArcs(
      [...many, { id: "z", y: 1000 }],
      many.map((row) => ({ from: row.id, to: "z", weight: 1 })),
      { ...LAYOUT, anchorX: 0, maxDown: 30 },
    );

    const offsets = arcs.map((arc) => arc.offset).sort((x, y) => x - y);
    expect(Math.max(...offsets)).toBeLessThanOrEqual(30);
    expect(new Set(offsets).size).toBe(offsets.length);
    const gaps = offsets
      .slice(1)
      .map((offset, index) => offset - offsets[index]);
    for (const gap of gaps) expect(gap).toBeCloseTo(gaps[0]);
  });

  it("leaves out dependencies on rows not shown", () => {
    expect(
      layoutArcs(rows, [{ from: "a", to: "x", weight: 1 }], LAYOUT),
    ).toEqual([]);
  });
});

describe("bracketPath", () => {
  it("ends `tail` past the start, back towards the labels", () => {
    expect(bracketPath(100, 50, 0, 1, 20, 6)).toMatch(/H 94$/);
  });

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

describe("cycleDependencies", () => {
  // The cycle a ⇄ b; a is expanded into a1 and a2; c stands outside.
  const rows = [
    { id: "a1", lineage: ["a1", "a", "root"] },
    { id: "a2", lineage: ["a2", "a", "root"] },
    { id: "b", lineage: ["b", "root"] },
    { id: "c", lineage: ["c", "root"] },
  ];
  const dependency = (from: string, to: string) => ({ from, to, weight: 1 });

  it("keeps the dependencies between rows of different members, also inside an expanded member", () => {
    const dependencies = [
      dependency("a1", "b"),
      dependency("b", "a2"),
      dependency("a1", "a2"),
      dependency("b", "c"),
      dependency("c", "a1"),
    ];

    expect(cycleDependencies(dependencies, ["a", "b"], rows)).toEqual([
      dependency("a1", "b"),
      dependency("b", "a2"),
    ]);
  });

  it("keeps nothing for rows it doesn't know", () => {
    expect(cycleDependencies([dependency("x", "b")], ["a", "b"], rows)).toEqual(
      [],
    );
  });
});
