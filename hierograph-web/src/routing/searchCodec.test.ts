import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
} from "@tanstack/react-router";
import { describe, expect, it } from "vitest";

import {
  parseEnum,
  parseIdList,
  routerParseSearch,
  routerStringifySearch,
  SIDES,
  stringifyIdList,
  TABS,
  validateCrossReferenceSearch,
  validateDsmSearch,
} from "./searchCodec";

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

describe("parseEnum", () => {
  it("returns allowed values and drops everything else", () => {
    expect(parseEnum("paths", TABS)).toBe("paths");
    expect(parseEnum("usages", TABS)).toBe("usages");
    expect(parseEnum("bogus", TABS)).toBeUndefined();
    expect(parseEnum(42, TABS)).toBeUndefined();
    expect(parseEnum("used-by", SIDES)).toBe("used-by");
    expect(parseEnum("left", SIDES)).toBeUndefined();
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
    const search = { subject_ids: ["42", "43"], from_id: 5, tab: "paths" };
    const parsed = routerParseSearch(routerStringifySearch(search));
    expect(parsed).toEqual({
      subject_ids: ["42", "43"],
      from_id: 5,
      tab: "paths",
    });
  });
});

describe("validateDsmSearch cascade", () => {
  it("keeps a full cell + tab when subject_ids are present", () => {
    expect(
      validateDsmSearch({
        subject_ids: ["42"],
        from_id: "1",
        to_id: "2",
        tab: "paths",
      }),
    ).toEqual({
      subject_ids: ["42"],
      from_id: "1",
      to_id: "2",
      tab: "paths",
    });
  });

  it("drops from_id/to_id (and thus tab) without subject_ids", () => {
    expect(
      validateDsmSearch({ from_id: "1", to_id: "2", tab: "paths" }),
    ).toEqual({
      subject_ids: undefined,
      from_id: undefined,
      to_id: undefined,
      tab: undefined,
    });
  });

  it("drops tab without a complete cell", () => {
    expect(
      validateDsmSearch({ subject_ids: ["42"], from_id: "1", tab: "paths" }),
    ).toEqual({
      subject_ids: ["42"],
      from_id: "1",
      to_id: undefined,
      tab: undefined,
    });
  });

  it("drops an unknown tab value but keeps the cell", () => {
    expect(
      validateDsmSearch({
        subject_ids: ["42"],
        from_id: "1",
        to_id: "2",
        tab: "bogus",
      }),
    ).toEqual({
      subject_ids: ["42"],
      from_id: "1",
      to_id: "2",
      tab: undefined,
    });
  });

  it("passes through unknown (non-existent) ids without crashing", () => {
    // Existence is not checked here — a stale deep-link id survives validation.
    expect(
      validateDsmSearch({ subject_ids: ["999999"], from_id: "1", to_id: "2" }),
    ).toEqual({
      subject_ids: ["999999"],
      from_id: "1",
      to_id: "2",
      tab: undefined,
    });
  });
});

describe("validateCrossReferenceSearch cascade", () => {
  it("keeps side/aggregated with a center selection", () => {
    expect(
      validateCrossReferenceSearch({
        center_ids: ["42"],
        side: "uses",
        aggregated: "used-by",
      }),
    ).toEqual({ center_ids: ["42"], side: "uses", aggregated: "used-by" });
  });

  it("drops side/aggregated without center_ids", () => {
    expect(
      validateCrossReferenceSearch({ side: "uses", aggregated: "used-by" }),
    ).toEqual({
      center_ids: undefined,
      side: undefined,
      aggregated: undefined,
    });
  });

  it("drops unknown enum values but keeps the center", () => {
    expect(
      validateCrossReferenceSearch({
        center_ids: ["42"],
        side: "sideways",
        aggregated: "left",
      }),
    ).toEqual({ center_ids: ["42"], side: undefined, aggregated: undefined });
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

  it("pushes per committed selection but replaces a tab toggle", async () => {
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

    // A tab toggle replaces — the URL updates but no new history entry appears.
    await router.navigate({
      to: "/",
      search: (prev) => ({ ...prev, tab: "paths" }),
      replace: true,
    });
    expect(router.state.location.search.tab).toBe("paths");
    expect(router.history.length).toBe(startLength + 3);
  });
});
