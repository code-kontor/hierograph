import type { ElkNode } from "elkjs/lib/elk.bundled.js";
import { describe, expect, it } from "vitest";

import { resolvePointerHit } from "./pointerHitTest";

// A root with a top-level edge in open space, plus an expanded container holding
// two leaf children and its own edge routed through the container's interior
// (the diagonal from (100,50) to (300,250), passing through the container
// centre (200,150)). Coordinates are ELK world coordinates.
const ROOT: ElkNode = {
  id: "root",
  width: 700,
  height: 300,
  children: [
    {
      id: "container",
      x: 0,
      y: 0,
      width: 400,
      height: 300,
      children: [
        { id: "left", x: 0, y: 0, width: 100, height: 100 },
        { id: "right", x: 300, y: 200, width: 100, height: 100 },
      ],
      edges: [
        {
          id: "left->right",
          sources: ["left"],
          targets: ["right"],
          sections: [
            {
              id: "s1",
              startPoint: { x: 100, y: 50 },
              endPoint: { x: 300, y: 250 },
            },
          ],
        },
      ],
    },
    { id: "sibling", x: 600, y: 100, width: 100, height: 100 },
  ],
  edges: [
    {
      id: "container->sibling",
      sources: ["container"],
      targets: ["sibling"],
      sections: [
        {
          id: "t1",
          startPoint: { x: 400, y: 150 },
          endPoint: { x: 600, y: 150 },
        },
      ],
    },
  ],
};

// A container whose own edge is routed horizontally through its top header band
// (the segment at y=14, inside the 28px header). Used to check that a hit in the
// header band resolves to the container even over an edge, so its collapse
// toolbar stays reachable. Coordinates are ELK world coordinates; the container
// sits at the origin so container-relative sections coincide with world coords.
const ROOT_HEADER_EDGE: ElkNode = {
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
      children: [
        { id: "left", x: 12, y: 40, width: 100, height: 100 },
        { id: "right", x: 288, y: 160, width: 100, height: 100 },
      ],
      edges: [
        {
          id: "through-header",
          sources: ["left"],
          targets: ["right"],
          sections: [
            {
              id: "s1",
              startPoint: { x: 0, y: 14 },
              endPoint: { x: 400, y: 14 },
            },
          ],
        },
      ],
    },
  ],
};

const TOLERANCE = 6;

describe("resolvePointerHit", () => {
  it("prefers an edge nested inside a container over the container itself", () => {
    // Container centre (200,150): on the interior edge, inside the container but
    // outside either leaf child.
    const hit = resolvePointerHit(ROOT, 200, 150, TOLERANCE);
    expect(hit).toEqual({
      kind: "edge",
      edge: expect.objectContaining({ id: "left->right" }),
    });
  });

  it("prefers a leaf child over an edge routed under it", () => {
    // (50,50) is inside the 'left' leaf; leaves win over edges.
    const hit = resolvePointerHit(ROOT, 50, 50, TOLERANCE);
    expect(hit).toEqual({
      kind: "node",
      node: expect.objectContaining({ id: "left" }),
    });
  });

  it("returns the container when the interior point is off every edge", () => {
    // (50,250) is in the container interior, outside both leaves, and far from
    // the diagonal edge.
    const hit = resolvePointerHit(ROOT, 50, 250, TOLERANCE);
    expect(hit).toEqual({
      kind: "node",
      node: expect.objectContaining({ id: "container" }),
    });
  });

  it("hits a top-level edge in open space", () => {
    // (500,150) is on the root's own edge, outside every node.
    const hit = resolvePointerHit(ROOT, 500, 150, TOLERANCE);
    expect(hit).toEqual({
      kind: "edge",
      edge: expect.objectContaining({ id: "container->sibling" }),
    });
  });

  it("returns null when the point is over nothing", () => {
    expect(resolvePointerHit(ROOT, 690, 290, TOLERANCE)).toBeNull();
  });

  it("resolves to the container in its header band even over an edge routed through it", () => {
    // (200,14) is in the container's top header band, on the edge routed through
    // it — the header must win so the collapse toolbar stays reachable there.
    // (Interior edges below the band still win: see the (200,150) case above.)
    const hit = resolvePointerHit(ROOT_HEADER_EDGE, 200, 14, TOLERANCE);
    expect(hit).toEqual({
      kind: "node",
      node: expect.objectContaining({ id: "container" }),
    });
  });
});
