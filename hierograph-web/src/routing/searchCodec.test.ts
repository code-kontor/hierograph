import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
} from "@tanstack/react-router";
import { describe, expect, it } from "vitest";

import {
  isIdListKey,
  parseEnum,
  parseIdList,
  parseSingleId,
  routerParseSearch,
  routerStringifySearch,
  stringifyIdList,
} from "./searchCodec";

describe("isIdListKey", () => {
  it("recognises params ending in _ids", () => {
    expect(isIdListKey("subject_ids")).toBe(true);
    expect(isIdListKey("expanded_ids")).toBe(true);
  });

  it("rejects single ids, other params and the bare suffix", () => {
    expect(isIdListKey("from_id")).toBe(false);
    expect(isIdListKey("view")).toBe(false);
    expect(isIdListKey("_ids")).toBe(false);
  });
});

describe("parseIdList / stringifyIdList", () => {
  it("splits a comma string into trimmed, non-empty ids", () => {
    expect(parseIdList("42,43")).toEqual(["42", "43"]);
    expect(parseIdList(" 42 , 43 ,")).toEqual(["42", "43"]);
  });

  it("accepts an array and a lone numeric value", () => {
    expect(parseIdList(["42", "43"])).toEqual(["42", "43"]);
    // A single numeric id is decoded as a number by the default codec.
    expect(parseIdList(42)).toEqual(["42"]);
  });

  it("collapses empty input to undefined", () => {
    expect(parseIdList("")).toBeUndefined();
    expect(parseIdList(",")).toBeUndefined();
    expect(parseIdList([])).toBeUndefined();
    expect(parseIdList(undefined)).toBeUndefined();
  });

  it("round-trips through stringifyIdList", () => {
    expect(stringifyIdList(["42", "43"])).toBe("42,43");
    expect(parseIdList(stringifyIdList(["42", "43"]))).toEqual(["42", "43"]);
    expect(stringifyIdList([])).toBeUndefined();
    expect(stringifyIdList(undefined)).toBeUndefined();
  });
});

describe("parseSingleId", () => {
  it("normalises numbers to strings and drops blanks and other types", () => {
    expect(parseSingleId(42)).toBe("42");
    expect(parseSingleId("42")).toBe("42");
    expect(parseSingleId("")).toBeUndefined();
    expect(parseSingleId(null)).toBeUndefined();
    expect(parseSingleId(["42"])).toBeUndefined();
  });
});

describe("parseEnum", () => {
  const MODES = ["live", "frozen"] as const;

  it("returns allowed values and drops everything else", () => {
    expect(parseEnum("live", MODES)).toBe("live");
    expect(parseEnum("frozen", MODES)).toBe("frozen");
    expect(parseEnum("bogus", MODES)).toBeUndefined();
    expect(parseEnum(42, MODES)).toBeUndefined();
  });
});

describe("router parse/stringify wrappers", () => {
  it("decodes comma id lists to string[] and leaves other keys alone", () => {
    expect(routerParseSearch("?subject_ids=42,43&tab=paths")).toEqual({
      subject_ids: ["42", "43"],
      tab: "paths",
    });
    // Percent-encoded commas decode identically.
    expect(routerParseSearch("?center_ids=42%2C43")).toEqual({
      center_ids: ["42", "43"],
    });
    // A single id survives even though the default codec decodes it as a number.
    expect(routerParseSearch("?subject_ids=42")).toEqual({
      subject_ids: ["42"],
    });
  });

  it("decodes any _ids param, not a fixed list", () => {
    expect(routerParseSearch("?some_new_ids=1,2")).toEqual({
      some_new_ids: ["1", "2"],
    });
  });

  it("drops an empty id list", () => {
    expect(routerParseSearch("?subject_ids=,&view=layers")).toEqual({
      view: "layers",
    });
  });

  it("emits readable comma lists, never JSON arrays", () => {
    const str = routerStringifySearch({ subject_ids: ["42", "43"] });
    expect(str).toContain("subject_ids=42,43");
    expect(str).not.toContain("%5B"); // no `[`
    expect(str).not.toContain("%2C"); // literal comma, not encoded
  });

  it("emits a lone numeric id unquoted (no JSON re-serialization)", () => {
    // A single numeric id must not come out as `subject_ids=%22360%22`.
    const str = routerStringifySearch({ subject_ids: ["360"] });
    expect(str).toBe("?subject_ids=360");
    expect(str).not.toContain("%22");
  });

  it("round-trips arbitrary params through the wrappers", () => {
    const search = {
      subject_ids: ["42", "43"],
      from_id: 5,
      view: "layers",
    };
    const parsed = routerParseSearch(routerStringifySearch(search));
    expect(parsed).toEqual({
      subject_ids: ["42", "43"],
      from_id: 5,
      view: "layers",
    });
  });
});

describe("router integration (memory history)", () => {
  it("builds a location whose searchStr uses comma-separated ids", () => {
    const router = createRouter({
      routeTree: createRootRoute(),
      history: createMemoryHistory({ initialEntries: ["/"] }),
      parseSearch: routerParseSearch,
      stringifySearch: routerStringifySearch,
    });

    const location = router.buildLocation({
      to: "/",
      search: { subject_ids: ["42", "43"] },
    });

    expect(location.searchStr).toContain("subject_ids=42,43");
    expect(location.searchStr).not.toContain("%5B");
  });

  it("pushes per committed selection but replaces a toggle", async () => {
    const rootRoute = createRootRoute({
      validateSearch: (search: Record<string, unknown>) =>
        search as { subject_ids?: string[]; tab?: string },
    });
    const router = createRouter({
      routeTree: rootRoute,
      history: createMemoryHistory({ initialEntries: ["/"] }),
      parseSearch: routerParseSearch,
      stringifySearch: routerStringifySearch,
    });
    await router.load();

    const startLength = router.history.length;

    // Selection chain A → A,B → A,B,C — each a pushed history entry.
    await router.navigate({ to: "/", search: { subject_ids: ["A"] } });
    await router.navigate({ to: "/", search: { subject_ids: ["A", "B"] } });
    await router.navigate({
      to: "/",
      search: { subject_ids: ["A", "B", "C"] },
    });
    expect(router.state.location.search.subject_ids).toEqual(["A", "B", "C"]);
    expect(router.history.length).toBe(startLength + 3);

    // A toggle replaces — the URL updates but no new history entry appears.
    await router.navigate({
      to: "/",
      search: (prev) => ({ ...prev, tab: "paths" }),
      replace: true,
    });
    expect(router.state.location.search.tab).toBe("paths");
    expect(router.history.length).toBe(startLength + 3);
  });
});
