import type { ElkNode } from "elkjs/lib/elk.bundled.js";
import { describe, expect, it } from "vitest";

import { hitTestNode } from "./hitTest";

function makeRoot(children: ElkNode[]): ElkNode {
  return { id: "root", children, edges: [] };
}

describe("hitTestNode", () => {
  it("returns the node whose box contains the point", () => {
    const nodeA: ElkNode = { id: "a", x: 0, y: 0, width: 100, height: 50 };
    const nodeB: ElkNode = { id: "b", x: 200, y: 0, width: 100, height: 50 };
    const root = makeRoot([nodeA, nodeB]);

    expect(hitTestNode(root, 50, 25)).toBe(nodeA);
    expect(hitTestNode(root, 250, 25)).toBe(nodeB);
  });

  it("returns null when the point is outside all boxes", () => {
    const nodeA: ElkNode = { id: "a", x: 0, y: 0, width: 100, height: 50 };
    const root = makeRoot([nodeA]);

    expect(hitTestNode(root, 150, 25)).toBeNull();
  });

  it("treats the box edge as inclusive", () => {
    const nodeA: ElkNode = { id: "a", x: 10, y: 10, width: 100, height: 50 };
    const root = makeRoot([nodeA]);

    expect(hitTestNode(root, 10, 10)).toBe(nodeA);
    expect(hitTestNode(root, 110, 60)).toBe(nodeA);
  });

  it("returns null when rootNode has no children", () => {
    expect(hitTestNode({ id: "root" }, 0, 0)).toBeNull();
    expect(hitTestNode(makeRoot([]), 0, 0)).toBeNull();
  });

  it("finds the correct node among multiple disjoint boxes", () => {
    const nodeA: ElkNode = { id: "a", x: 0, y: 0, width: 50, height: 50 };
    const nodeB: ElkNode = { id: "b", x: 100, y: 0, width: 50, height: 50 };
    const nodeC: ElkNode = { id: "c", x: 200, y: 0, width: 50, height: 50 };
    const root = makeRoot([nodeA, nodeB, nodeC]);

    expect(hitTestNode(root, 25, 25)).toBe(nodeA);
    expect(hitTestNode(root, 125, 25)).toBe(nodeB);
    expect(hitTestNode(root, 225, 25)).toBe(nodeC);
    expect(hitTestNode(root, 75, 25)).toBeNull();
  });

  it("skips children missing geometry instead of crashing", () => {
    const incomplete: ElkNode = { id: "incomplete", x: 0, y: 0, width: 100 };
    const nodeB: ElkNode = { id: "b", x: 0, y: 0, width: 100, height: 50 };
    const root = makeRoot([incomplete, nodeB]);

    expect(hitTestNode(root, 50, 25)).toBe(nodeB);
  });
});
