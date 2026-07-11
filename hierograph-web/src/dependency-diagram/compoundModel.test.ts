import type { ElkNode } from "elkjs/lib/elk.bundled.js";
import { describe, expect, it } from "vitest";

import { buildCompoundElkGraph } from "./compoundModel";
import { NODE_HEIGHT, NODE_WIDTH } from "./elkLayout";
import { buildDependencyGraph, type DependencyGraph } from "./graphModel";

// A -> B (column 0 = source A, row 1 = target B).
function rootGraph(): DependencyGraph {
  return buildDependencyGraph(
    [
      { id: "A", text: "pkg.a" },
      { id: "B", text: "pkg.b" },
    ],
    [{ row: 1, column: 0, value: 3 }],
  );
}

// a1 -> a2 inside A.
function childGraphOfA(): DependencyGraph {
  return buildDependencyGraph(
    [
      { id: "a1", text: "pkg.a.one" },
      { id: "a2", text: "pkg.a.two" },
    ],
    [{ row: 1, column: 0, value: 1 }],
  );
}

// b1 -> b2 inside B.
function childGraphOfB(): DependencyGraph {
  return buildDependencyGraph(
    [
      { id: "b1", text: "pkg.b.one" },
      { id: "b2", text: "pkg.b.two" },
    ],
    [{ row: 1, column: 0, value: 1 }],
  );
}

function childById(node: ElkNode, id: string): ElkNode | undefined {
  return node.children?.find((c) => c.id === id);
}

describe("buildCompoundElkGraph", () => {
  it("produces leaves only when nothing is expanded (flat structure)", () => {
    const root = buildCompoundElkGraph(rootGraph(), new Map(), new Set());

    expect(root.children).toHaveLength(2);
    for (const child of root.children ?? []) {
      expect(child.children).toBeUndefined();
      expect(child.width).toBe(NODE_WIDTH);
      expect(child.height).toBe(NODE_HEIGHT);
    }
    // One root edge (A -> B), scoped under the root container.
    expect(root.edges).toHaveLength(1);
    expect(root.edges?.[0].id).toBe("root:0-1");
    expect(root.edges?.[0].sources).toEqual(["A"]);
    expect(root.edges?.[0].targets).toEqual(["B"]);
  });

  it("nests children and internal edges for an expanded, loaded node", () => {
    const loaded = new Map([["A", childGraphOfA()]]);
    const root = buildCompoundElkGraph(rootGraph(), loaded, new Set(["A"]));

    const a = childById(root, "A");
    expect(a).toBeDefined();
    // Container: no fixed geometry, ELK grows it.
    expect(a?.width).toBeUndefined();
    expect(a?.height).toBeUndefined();
    expect(a?.children?.map((c) => c.id)).toEqual(["a1", "a2"]);
    // Its own internal edge, scoped by the container id.
    expect(a?.edges).toHaveLength(1);
    expect(a?.edges?.[0].id).toBe("A:0-1");
    expect(a?.edges?.[0].sources).toEqual(["a1"]);

    // B stays a leaf.
    const b = childById(root, "B");
    expect(b?.children).toBeUndefined();
    expect(b?.width).toBe(NODE_WIDTH);
  });

  it("scopes edge ids per container so merged matrices do not collide", () => {
    const loaded = new Map([
      ["A", childGraphOfA()],
      ["B", childGraphOfB()],
    ]);
    const root = buildCompoundElkGraph(
      rootGraph(),
      loaded,
      new Set(["A", "B"]),
    );

    const idA = childById(root, "A")?.edges?.[0].id;
    const idB = childById(root, "B")?.edges?.[0].id;
    // Both inner matrices have positional id "0-1" but stay distinct here.
    expect(idA).toBe("A:0-1");
    expect(idB).toBe("B:0-1");
    expect(idA).not.toBe(idB);
  });

  it("treats an expanded-but-unloaded node as a leaf", () => {
    const root = buildCompoundElkGraph(rootGraph(), new Map(), new Set(["A"]));

    const a = childById(root, "A");
    expect(a?.children).toBeUndefined();
    expect(a?.width).toBe(NODE_WIDTH);
  });

  it("recurses into grandchildren when they are also expanded and loaded", () => {
    const grandGraph = buildDependencyGraph(
      [{ id: "a1x", text: "pkg.a.one.x" }],
      [],
    );
    const loaded = new Map([
      ["A", childGraphOfA()],
      ["a1", grandGraph],
    ]);
    const root = buildCompoundElkGraph(
      rootGraph(),
      loaded,
      new Set(["A", "a1"]),
    );

    const a1 = childById(childById(root, "A") as ElkNode, "a1");
    expect(a1?.children?.map((c) => c.id)).toEqual(["a1x"]);
  });

  it("does not crash on empty root or empty child matrices", () => {
    const empty = buildDependencyGraph([], []);
    expect(() =>
      buildCompoundElkGraph(empty, new Map(), new Set()),
    ).not.toThrow();

    const loaded = new Map([["A", empty]]);
    const root = buildCompoundElkGraph(rootGraph(), loaded, new Set(["A"]));
    const a = childById(root, "A");
    // A container with no children (just a header) is acceptable.
    expect(a?.children).toEqual([]);
    expect(a?.edges).toEqual([]);
  });

  it("does not mutate its inputs", () => {
    const graph = rootGraph();
    const nodesBefore = graph.nodes.length;
    const edgesBefore = graph.edges.length;
    buildCompoundElkGraph(graph, new Map(), new Set());
    expect(graph.nodes).toHaveLength(nodesBefore);
    expect(graph.edges).toHaveLength(edgesBefore);
  });
});
