import type { ElkExtendedEdge, ElkNode } from "elkjs/lib/elk.bundled.js";
import { describe, expect, it } from "vitest";

import { hitTestEdge } from "./edgeHitTest";

function makeRoot(children: ElkNode[], edges: ElkExtendedEdge[]): ElkNode {
  return { id: "root", children, edges };
}

describe("hitTestEdge", () => {
  it("hits a straight edge near its segment (within tolerance)", () => {
    const edge: ElkExtendedEdge = {
      id: "e1",
      sources: ["a"],
      targets: ["b"],
      sections: [
        {
          id: "s1",
          startPoint: { x: 0, y: 0 },
          endPoint: { x: 100, y: 0 },
        },
      ],
    };
    const root = makeRoot([], [edge]);

    expect(hitTestEdge(root, 50, 2, 5)).toBe(edge);
  });

  it("returns null when far away", () => {
    const edge: ElkExtendedEdge = {
      id: "e1",
      sources: ["a"],
      targets: ["b"],
      sections: [
        {
          id: "s1",
          startPoint: { x: 0, y: 0 },
          endPoint: { x: 100, y: 0 },
        },
      ],
    };
    const root = makeRoot([], [edge]);

    expect(hitTestEdge(root, 50, 100, 5)).toBeNull();
  });

  it("treats the tolerance boundary as inclusive", () => {
    const edge: ElkExtendedEdge = {
      id: "e1",
      sources: ["a"],
      targets: ["b"],
      sections: [
        {
          id: "s1",
          startPoint: { x: 0, y: 0 },
          endPoint: { x: 100, y: 0 },
        },
      ],
    };
    const root = makeRoot([], [edge]);

    expect(hitTestEdge(root, 50, 5, 5)).toBe(edge);
    expect(hitTestEdge(root, 50, 5.0001, 5)).toBeNull();
  });

  it("returns the closest edge among multiple candidates", () => {
    const edgeNear: ElkExtendedEdge = {
      id: "near",
      sources: ["a"],
      targets: ["b"],
      sections: [
        {
          id: "s1",
          startPoint: { x: 0, y: 0 },
          endPoint: { x: 100, y: 0 },
        },
      ],
    };
    const edgeFar: ElkExtendedEdge = {
      id: "far",
      sources: ["c"],
      targets: ["d"],
      sections: [
        {
          id: "s2",
          startPoint: { x: 0, y: 50 },
          endPoint: { x: 100, y: 50 },
        },
      ],
    };
    const root = makeRoot([], [edgeFar, edgeNear]);

    expect(hitTestEdge(root, 50, 2, 20)).toBe(edgeNear);
  });

  it("hits a multi-segment edge on a middle segment via bendPoints", () => {
    const edge: ElkExtendedEdge = {
      id: "e1",
      sources: ["a"],
      targets: ["b"],
      sections: [
        {
          id: "s1",
          startPoint: { x: 0, y: 0 },
          bendPoints: [
            { x: 50, y: 0 },
            { x: 50, y: 100 },
          ],
          endPoint: { x: 100, y: 100 },
        },
      ],
    };
    const root = makeRoot([], [edge]);

    expect(hitTestEdge(root, 50, 50, 5)).toBe(edge);
  });

  it("checks all sections of a multi-section edge", () => {
    const edge: ElkExtendedEdge = {
      id: "e1",
      sources: ["a"],
      targets: ["b"],
      sections: [
        {
          id: "s1",
          startPoint: { x: 0, y: 0 },
          endPoint: { x: 100, y: 0 },
        },
        {
          id: "s2",
          startPoint: { x: 0, y: 200 },
          endPoint: { x: 100, y: 200 },
        },
      ],
    };
    const root = makeRoot([], [edge]);

    expect(hitTestEdge(root, 50, 202, 5)).toBe(edge);
  });

  it("falls back to a straight center-to-center line when sections are missing", () => {
    const nodeA: ElkNode = { id: "a", x: 0, y: 0, width: 20, height: 20 };
    const nodeB: ElkNode = { id: "b", x: 100, y: 0, width: 20, height: 20 };
    const edge: ElkExtendedEdge = {
      id: "e1",
      sources: ["a"],
      targets: ["b"],
    };
    const root = makeRoot([nodeA, nodeB], [edge]);

    // centers: (10, 10) -> (110, 10)
    expect(hitTestEdge(root, 60, 12, 5)).toBe(edge);
  });

  it("returns null when rootNode.edges is empty or missing", () => {
    expect(hitTestEdge(makeRoot([], []), 0, 0, 5)).toBeNull();
    expect(hitTestEdge({ id: "root" }, 0, 0, 5)).toBeNull();
  });

  it("hits a nested edge inside a translated container at world coordinates", () => {
    // Container at world (100, 100). Its internal edge section is
    // container-relative: (0,0) -> (100,0), i.e. world (100,100) -> (200,100).
    const innerEdge: ElkExtendedEdge = {
      id: "container:0-1",
      sources: ["c1"],
      targets: ["c2"],
      sections: [
        {
          id: "s1",
          startPoint: { x: 0, y: 0 },
          endPoint: { x: 100, y: 0 },
        },
      ],
    };
    const container: ElkNode = {
      id: "container",
      x: 100,
      y: 100,
      width: 200,
      height: 150,
      children: [
        { id: "c1", x: 0, y: 0, width: 40, height: 20 },
        { id: "c2", x: 100, y: 0, width: 40, height: 20 },
      ],
      edges: [innerEdge],
    };
    const root = makeRoot([container], []);

    // World (150, 102): near the shifted section -> hit.
    expect(hitTestEdge(root, 150, 102, 5)).toBe(innerEdge);
    // Same point without the container offset (150, 2): far from world section.
    expect(hitTestEdge(root, 150, 2, 5)).toBeNull();
  });

  it("skips an edge with missing geometry instead of crashing, other edges still hit", () => {
    const brokenEdge: ElkExtendedEdge = {
      id: "broken",
      sources: ["missing-a"],
      targets: ["missing-b"],
    };
    const goodEdge: ElkExtendedEdge = {
      id: "good",
      sources: ["a"],
      targets: ["b"],
      sections: [
        {
          id: "s1",
          startPoint: { x: 0, y: 0 },
          endPoint: { x: 100, y: 0 },
        },
      ],
    };
    const root = makeRoot([], [brokenEdge, goodEdge]);

    expect(hitTestEdge(root, 50, 2, 5)).toBe(goodEdge);
  });
});
