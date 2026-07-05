import { describe, expect, it } from "vitest";

import {
  serializeDsmForClipboard,
  type SerializeDsmInput,
} from "./serializeDsm";

const labels = [
  { id: "n1", text: "com.example.a.Foo" },
  { id: "n2", text: "com.example.b.Bar" },
];

const cells = [
  { row: 0, column: 1, value: 3 },
  { row: 1, column: 1, value: 2 },
];

const sccs = [{ nodePositions: [0, 1] }];

function buildInput(showDiagonal: boolean): SerializeDsmInput {
  return {
    subject: { kind: "single", name: "com.example", nodeType: "Package" },
    labels,
    cells,
    sccs,
    showDiagonal,
  };
}

describe("serializeDsmForClipboard", () => {
  it("hides diagonal cells when showDiagonal is false", () => {
    const text = serializeDsmForClipboard(buildInput(false));

    expect(text).toContain("Show diagonal: off");
    expect(text).toContain("com.example.a.Foo → com.example.b.Bar: 3");
    expect(text).not.toContain("com.example.b.Bar → com.example.b.Bar");
    expect(text).not.toMatch(/\|\s*2\s*\|\s*2\s*\|\s*2\s*\|/);
  });

  it("shows diagonal cells when showDiagonal is true", () => {
    const text = serializeDsmForClipboard(buildInput(true));

    expect(text).toContain("Show diagonal: on");
    expect(text).toContain("com.example.b.Bar → com.example.b.Bar: 2");
  });

  it("includes all sections", () => {
    const text = serializeDsmForClipboard(buildInput(true));

    expect(text).toContain("## Nodes (in matrix order)");
    expect(text).toContain("1. com.example.a.Foo");
    expect(text).toContain("2. com.example.b.Bar");
    expect(text).toContain("## Dependencies (source → target: weight)");
    expect(text).toContain("## Cycles (strongly connected components)");
    expect(text).toContain("Cycle 1: com.example.a.Foo, com.example.b.Bar");
    expect(text).toContain("## Matrix (rows = source, columns = target)");
  });

  it("renders the single-node selection header", () => {
    const text = serializeDsmForClipboard(buildInput(true));

    expect(text).toContain("Selection: com.example (Package)");
  });

  it("renders the multi-node selection header with listed fqns", () => {
    const text = serializeDsmForClipboard({
      subject: {
        kind: "multi",
        count: 2,
        nodes: [
          { text: "com.example.a.Foo", type: "Class" },
          { text: "com.example.b.Bar", type: "Class" },
        ],
      },
      labels,
      cells,
      sccs,
      showDiagonal: true,
    });

    expect(text).toContain("Selection: 2 selected nodes");
    expect(text).toContain("- com.example.a.Foo");
    expect(text).toContain("- com.example.b.Bar");
  });

  it("renders (none) placeholders for empty dependencies and cycles", () => {
    const text = serializeDsmForClipboard({
      subject: { kind: "single", name: "com.example" },
      labels,
      cells: [],
      sccs: [],
      showDiagonal: true,
    });

    expect(text).toContain(
      "## Dependencies (source → target: weight)\n\n_(none)_",
    );
    expect(text).toContain(
      "## Cycles (strongly connected components)\n\n_(none)_",
    );
  });
});
