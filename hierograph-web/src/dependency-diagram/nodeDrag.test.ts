import type { ElkExtendedEdge, ElkNode } from "elkjs/lib/elk.bundled.js";
import { describe, expect, it } from "vitest";

import { straightenIncidentEdges } from "./nodeDrag";

describe("straightenIncidentEdges", () => {
  it("re-routes a flat edge to the boxes' borders, with 0 bendpoints", () => {
    const nodeA: ElkNode = { id: "a", x: 0, y: 0, width: 20, height: 20 };
    const nodeB: ElkNode = { id: "b", x: 100, y: 0, width: 20, height: 20 };
    const edge: ElkExtendedEdge = {
      id: "e1",
      sources: ["a"],
      targets: ["b"],
      sections: [
        {
          id: "s1",
          startPoint: { x: 10, y: 10 },
          bendPoints: [{ x: 50, y: 10 }],
          endPoint: { x: 110, y: 10 },
        },
      ],
    };
    const root: ElkNode = {
      id: "root",
      children: [nodeA, nodeB],
      edges: [edge],
    };

    nodeB.x = 200;
    nodeB.y = 100;
    straightenIncidentEdges(root, "b");

    // a center (10,10) -> b center (210,110): the line exits a's border at
    // (20,15) and enters b's border at (200,105).
    expect(edge.sections).toEqual([
      {
        id: "e1-straight",
        startPoint: { x: 20, y: 15 },
        endPoint: { x: 200, y: 105 },
        bendPoints: [],
      },
    ]);
  });

  it("subtracts the owning container's world offset for a cross-level edge", () => {
    const outerLeaf: ElkNode = {
      id: "leaf",
      x: 0,
      y: 0,
      width: 20,
      height: 20,
    };
    const containerChild: ElkNode = {
      id: "c1",
      x: 0,
      y: 0,
      width: 20,
      height: 20,
    };
    const container: ElkNode = {
      id: "container",
      x: 100,
      y: 100,
      width: 200,
      height: 150,
      children: [containerChild],
    };
    const edge: ElkExtendedEdge = {
      id: "e1",
      sources: ["leaf"],
      targets: ["c1"],
      sections: [
        {
          id: "s1",
          startPoint: { x: 10, y: 10 },
          endPoint: { x: 90, y: 90 },
        },
      ],
    };
    const root: ElkNode = {
      id: "root",
      children: [outerLeaf, container],
      edges: [edge],
    };

    outerLeaf.x = 30;
    outerLeaf.y = 40;
    straightenIncidentEdges(root, "leaf");

    // leaf center in world coords: (40, 50); c1 center in world coords:
    // (100 + 10, 100 + 10) = (110, 110). Edge is owned by root -> offset (0, 0).
    // The line exits each 20x20 box at its border facing the other centre:
    // leaf border (50, 50 + 60/7), c1 border (100, 110 - 60/7).
    const section = edge.sections![0];
    expect(section.id).toBe("e1-straight");
    expect(section.bendPoints).toEqual([]);
    expect(section.startPoint.x).toBeCloseTo(50, 6);
    expect(section.startPoint.y).toBeCloseTo(50 + 60 / 7, 6);
    expect(section.endPoint.x).toBeCloseTo(100, 6);
    expect(section.endPoint.y).toBeCloseTo(110 - 60 / 7, 6);
  });

  it("leaves edges of unrelated nodes untouched", () => {
    const nodeA: ElkNode = { id: "a", x: 0, y: 0, width: 20, height: 20 };
    const nodeB: ElkNode = { id: "b", x: 100, y: 0, width: 20, height: 20 };
    const nodeC: ElkNode = { id: "c", x: 0, y: 100, width: 20, height: 20 };
    const untouchedEdge: ElkExtendedEdge = {
      id: "e2",
      sources: ["a"],
      targets: ["c"],
      sections: [
        {
          id: "s2",
          startPoint: { x: 10, y: 10 },
          endPoint: { x: 10, y: 110 },
        },
      ],
    };
    const root: ElkNode = {
      id: "root",
      children: [nodeA, nodeB, nodeC],
      edges: [untouchedEdge],
    };

    straightenIncidentEdges(root, "b");

    expect(untouchedEdge.sections).toEqual([
      { id: "s2", startPoint: { x: 10, y: 10 }, endPoint: { x: 10, y: 110 } },
    ]);
  });
});
