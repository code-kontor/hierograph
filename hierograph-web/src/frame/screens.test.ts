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
  const scope = (
    generation: string,
    view: string | undefined,
    viewsVersion = "v1",
  ) => ({ generation, view, viewsVersion });

  it("puts the vertical, the generation, the view and its version first", () => {
    expect(graphQueryKey("dsm", scope("7", "layers"), "matrix", 42)).toEqual([
      "dsm",
      "graph",
      "7",
      "layers",
      "v1",
      "matrix",
      42,
    ]);
  });

  it("differs between generations and hierarchy views", () => {
    const base = graphQueryKey("dsm", scope("7", undefined));
    expect(base).toEqual(["dsm", "graph", "7", null, null]);
    expect(graphQueryKey("dsm", scope("8", undefined))).not.toEqual(base);
    expect(graphQueryKey("dsm", scope("7", "layers"))).not.toEqual(base);
  });

  it("follows the views' version only in a view", () => {
    expect(graphQueryKey("dsm", scope("7", undefined, "v2"))).toEqual(
      graphQueryKey("dsm", scope("7", undefined, "v1")),
    );
    expect(graphQueryKey("dsm", scope("7", "layers", "v2"))).not.toEqual(
      graphQueryKey("dsm", scope("7", "layers", "v1")),
    );
  });
});
