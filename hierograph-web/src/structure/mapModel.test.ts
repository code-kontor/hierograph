import { describe, expect, it } from "vitest";

import { type MapBox, type MapModel, restrictModel } from "./mapModel";

// root ─ a (open: x, y in a tangle, z), b
function model(): MapModel {
  const box = (id: string, parentId: string | null) =>
    ({ id, parentId }) as MapBox;
  return {
    rootId: "root",
    boxes: new Map([
      ["a", box("a", null)],
      ["b", box("b", null)],
      ["x", box("x", "a")],
      ["y", box("y", "a")],
      ["z", box("z", "a")],
    ]),
    rowsOf: new Map([
      ["root", [[{ kind: "box", id: "a" }], [{ kind: "box", id: "b" }]]],
      [
        "a",
        [
          [{ kind: "tangle", rows: [["x"], ["y"]] }],
          [{ kind: "box", id: "z" }],
        ],
      ],
    ]),
    frontier: ["b", "x", "y", "z"],
    feedback: [{ containerId: "a", sourceId: "y", targetId: "x", weight: 1 }],
    weights: new Map(),
    pending: [],
  };
}

describe("restrictModel", () => {
  it("keeps only the given boxes, dropping empty rows and tangle rows", () => {
    const restricted = restrictModel(model(), new Set(["a", "x"]));

    expect(restricted.rowsOf.get("root")).toEqual([[{ kind: "box", id: "a" }]]);
    expect(restricted.rowsOf.get("a")).toEqual([
      [{ kind: "tangle", rows: [["x"]] }],
    ]);
    expect([...restricted.boxes.keys()]).toEqual(["a", "x"]);
    expect(restricted.frontier).toEqual(["x"]);
    expect(restricted.feedback).toEqual([]);
  });

  it("counts the weights again over what is kept", () => {
    expect(model().weights.size).toBe(0);
    const restricted = restrictModel(model(), new Set(["a", "x", "z"]));

    expect(restricted.weights.get("a")).toBe(1);
    expect(restricted.weights.get("x")).toBe(1);
  });
});
