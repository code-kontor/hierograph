import { describe, expect, it } from "vitest";

import { graphQueryKey } from "./GraphScope";
import { currentScreen, type Screen } from "./screens";

const SCREENS = [
  { path: "/", label: "Start", needsGraph: false },
  { path: "/dsm", label: "DSM", needsGraph: true },
] as unknown as Screen[];

describe("currentScreen", () => {
  it("finds the start screen only for the root", () => {
    expect(currentScreen(SCREENS, "/")?.label).toBe("Start");
    expect(currentScreen(SCREENS, "")?.label).toBe("Start");
  });

  it("finds a screen for its path and the paths below it", () => {
    expect(currentScreen(SCREENS, "/dsm")?.label).toBe("DSM");
    expect(currentScreen(SCREENS, "/dsm/")?.label).toBe("DSM");
    expect(currentScreen(SCREENS, "/dsm/details")?.label).toBe("DSM");
  });

  it("finds none for an unknown path", () => {
    expect(currentScreen(SCREENS, "/dsmx")).toBeUndefined();
    expect(currentScreen(SCREENS, "/unknown")).toBeUndefined();
  });
});

describe("graphQueryKey", () => {
  it("puts the vertical, the generation and the hierarchy view first", () => {
    expect(
      graphQueryKey("dsm", { generation: "7", view: "layers" }, "matrix", 42),
    ).toEqual(["dsm", "graph", "7", "layers", "matrix", 42]);
  });

  it("differs between generations and hierarchy views", () => {
    const base = graphQueryKey("dsm", { generation: "7", view: undefined });
    expect(base).toEqual(["dsm", "graph", "7", null]);
    expect(
      graphQueryKey("dsm", { generation: "8", view: undefined }),
    ).not.toEqual(base);
    expect(
      graphQueryKey("dsm", { generation: "7", view: "layers" }),
    ).not.toEqual(base);
  });
});
