import { describe, expect, it } from "vitest";

import {
  dependencyCopyEntries,
  needsSourceLocation,
  nodeCopyEntries,
  sourceLocation,
} from "./copyEntries";

const type = {
  text: "io.hierograph.algorithms.GraphAlgorithms",
  type: "java.class",
};

describe("nodeCopyEntries", () => {
  it("offers a type's names and, with its source file, its paths", () => {
    const entries = nodeCopyEntries(
      type,
      {
        workspacePath:
          "core/src/main/kotlin/io/hierograph/algorithms/GraphAlgorithms.kt",
        sourceRootPath: "io/hierograph/algorithms/GraphAlgorithms.kt",
        repositoryPath:
          "project/core/src/main/kotlin/io/hierograph/algorithms/GraphAlgorithms.kt",
      },
      "/home/dev/project/",
    );
    expect(entries).toEqual([
      { label: "Simple name", value: "GraphAlgorithms" },
      {
        label: "Fully qualified name",
        value: "io.hierograph.algorithms.GraphAlgorithms",
      },
      {
        label: "Absolute path",
        value:
          "/home/dev/project/core/src/main/kotlin/io/hierograph/algorithms/GraphAlgorithms.kt",
      },
      {
        label: "Path from source root",
        value: "io/hierograph/algorithms/GraphAlgorithms.kt",
      },
      {
        label: "Path from repository root",
        value:
          "project/core/src/main/kotlin/io/hierograph/algorithms/GraphAlgorithms.kt",
      },
    ]);
  });

  it("names a nested type as source writes it, and offers no path it doesn't know", () => {
    const entries = nodeCopyEntries(
      { text: "a.b.Outer$Inner", type: "java.class" },
      { sourceRootPath: "a/b/Outer.kt" },
      "/ws",
    );
    expect(entries).toEqual([
      { label: "Simple name", value: "Inner" },
      { label: "Fully qualified name", value: "a.b.Outer.Inner" },
      { label: "Path from source root", value: "a/b/Outer.kt" },
    ]);
  });

  it("joins a Windows workspace with backslashes", () => {
    const entries = nodeCopyEntries(
      type,
      { workspacePath: "src/A.java" },
      "C:\\dev\\project",
    );
    expect(entries.find((e) => e.label === "Absolute path")?.value).toBe(
      "C:\\dev\\project\\src\\A.java",
    );
  });

  it("offers a package's names", () => {
    expect(
      nodeCopyEntries(
        { text: "io.hierograph.algorithms", type: "java.package" },
        undefined,
        "/ws",
      ),
    ).toEqual([
      { label: "Simple name", value: "algorithms" },
      { label: "Fully qualified name", value: "io.hierograph.algorithms" },
    ]);
  });

  it("offers a module's artifact id and coordinates, without a classifier", () => {
    expect(
      nodeCopyEntries(
        { text: "io.hierograph:server:0.4.0:test", type: "java.module" },
        undefined,
        "/ws",
      ),
    ).toEqual([
      { label: "Simple name", value: "server (test)" },
      { label: "Module coordinates", value: "io.hierograph:server:0.4.0" },
    ]);
  });

  it("offers External Types and a view group only by name", () => {
    expect(
      nodeCopyEntries(
        { text: "External Types", type: "java.module" },
        undefined,
        "/ws",
      ),
    ).toEqual([{ label: "Simple name", value: "External Types" }]);
    expect(
      nodeCopyEntries(
        { text: "layers:application", type: "view.group" },
        undefined,
        "/ws",
      ),
    ).toEqual([{ label: "Simple name", value: "application" }]);
  });
});

describe("dependencyCopyEntries", () => {
  it("joins both ends' qualified names by an arrow", () => {
    expect(
      dependencyCopyEntries(
        { text: "a.B$C", type: "java.class" },
        { text: "x.y", type: "java.package" },
      ),
    ).toEqual([
      { label: "Dependency", value: "a.B.C → x.y" },
      { label: "Source", value: "a.B.C" },
      { label: "Target", value: "x.y" },
    ]);
  });
});

describe("needsSourceLocation", () => {
  it("asks the server only for a type of the workspace", () => {
    expect(needsSourceLocation(type)).toBe(true);
    expect(
      needsSourceLocation({ text: "java.util.List", type: "external.type" }),
    ).toBe(false);
    expect(needsSourceLocation({ text: "a.b", type: "java.package" })).toBe(
      false,
    );
  });
});

describe("sourceLocation", () => {
  it("reads the keys it knows, leaving out the missing ones", () => {
    expect(
      sourceLocation([
        { key: "fqn", value: "a.B" },
        { key: "sourceRootPath", value: "a/B.kt" },
        { key: "repositoryPath", value: null },
      ]),
    ).toEqual({
      workspacePath: undefined,
      sourceRootPath: "a/B.kt",
      repositoryPath: undefined,
    });
  });
});
