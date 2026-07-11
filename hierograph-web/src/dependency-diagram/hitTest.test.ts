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

  it("returns the inner nested child, not the enclosing container", () => {
    // Container at world (100, 100); child at container-relative (20, 30),
    // i.e. world (120, 130).
    const inner: ElkNode = { id: "inner", x: 20, y: 30, width: 50, height: 40 };
    const container: ElkNode = {
      id: "container",
      x: 100,
      y: 100,
      width: 200,
      height: 150,
      children: [inner],
    };
    const root = makeRoot([container]);

    // World (130, 140) -> inside inner (container-relative 30, 40).
    expect(hitTestNode(root, 130, 140)).toBe(inner);
  });

  it("returns the container when the point is in its header band (no child)", () => {
    const inner: ElkNode = { id: "inner", x: 20, y: 30, width: 50, height: 40 };
    const container: ElkNode = {
      id: "container",
      x: 100,
      y: 100,
      width: 200,
      height: 150,
      children: [inner],
    };
    const root = makeRoot([container]);

    // World (110, 110): inside the container but above/left of the child.
    expect(hitTestNode(root, 110, 110)).toBe(container);
  });

  it("maps a container-relative child offset to world coordinates", () => {
    // Child at container-relative x=5, container at world x=200 -> world 205.
    const inner: ElkNode = { id: "inner", x: 5, y: 5, width: 10, height: 10 };
    const container: ElkNode = {
      id: "container",
      x: 200,
      y: 0,
      width: 100,
      height: 100,
      children: [inner],
    };
    const root = makeRoot([container]);

    expect(hitTestNode(root, 208, 8)).toBe(inner);
    // Just outside the child (world 204 < 205) -> falls back to container.
    expect(hitTestNode(root, 204, 8)).toBe(container);
  });
});
