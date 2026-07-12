import { describe, expect, it } from "vitest";

import { buildDependencyGraph } from "./graphModel";

const nodes = [
  { id: "a", text: "a", type: "java.package" },
  { id: "b", text: "b", type: "java.package" },
  { id: "c", text: "c", type: "java.package" },
];

describe("buildDependencyGraph", () => {
  it("carries ordered nodes through unchanged", () => {
    const { nodes: result } = buildDependencyGraph(nodes, []);
    expect(result).toEqual(nodes);
  });

  it("builds an edge from row (source, dependent node) to column (target, used node)", () => {
    const { edges } = buildDependencyGraph(nodes, [
      { row: 1, column: 0, value: 3 },
    ]);
    expect(edges).toEqual([
      { id: "1-0", sourceId: "b", targetId: "a", weight: 3 },
    ]);
  });

  it("filters out cells with value <= 0", () => {
    const { edges } = buildDependencyGraph(nodes, [
      { row: 1, column: 0, value: 0 },
      { row: 2, column: 0, value: -1 },
    ]);
    expect(edges).toEqual([]);
  });

  it("skips the diagonal (self-dependency)", () => {
    const { edges } = buildDependencyGraph(nodes, [
      { row: 1, column: 1, value: 5 },
    ]);
    expect(edges).toEqual([]);
  });

  it("returns an empty graph for empty input", () => {
    expect(buildDependencyGraph([], [])).toEqual({ nodes: [], edges: [] });
  });

  it("produces stable, unique edge ids", () => {
    const { edges } = buildDependencyGraph(nodes, [
      { row: 1, column: 0, value: 1 },
      { row: 2, column: 0, value: 2 },
      { row: 2, column: 1, value: 3 },
    ]);
    expect(edges.map((e) => e.id)).toEqual(["1-0", "2-0", "2-1"]);
  });

  it("ensures edge direction aligns with DSM convention: row is source (dependent node), column is target (used node)", () => {
    const orderedNodes = [
      { id: "lib", text: "lib", type: "java.package" },
      { id: "app", text: "app", type: "java.package" },
    ];
    const { edges } = buildDependencyGraph(orderedNodes, [
      { row: 1, column: 0, value: 25 },
    ]);
    expect(edges).toHaveLength(1);
    expect(edges[0]).toEqual({
      id: "1-0",
      sourceId: "app",
      targetId: "lib",
      weight: 25,
    });
  });
});
