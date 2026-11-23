import { describe, expect, it } from "vitest";

import {
  expansionFor,
  type GivenEnd,
  MAX_GIVEN,
  parseGiven,
  placeGiven,
  type ResolvedPair,
} from "./givenDependencies";
import type { MapBox, MapModel } from "./mapModel";

// root 0 ─ module 1 ─ package 11 ─ types 111, 112 (members 1111, 1112 in 111)
//                  └ package 12 ─ type 121
//        └ module 2 ─ package 21 ─ type 211
const PARENTS: Record<string, string> = {
  "1": "0",
  "2": "0",
  "11": "1",
  "12": "1",
  "21": "2",
  "111": "11",
  "112": "11",
  "121": "12",
  "211": "21",
  "1111": "111",
  "1112": "111",
};
const TYPES: Record<string, string> = {
  "0": "Unknown",
  "1": "java.module",
  "2": "java.module",
  "11": "java.package",
  "12": "java.package",
  "21": "java.package",
  "111": "java.class",
  "112": "java.class",
  "121": "java.class",
  "211": "java.class",
  "1111": "java.method",
  "1112": "java.method",
};

function end(id: string): GivenEnd {
  const ancestors = [];
  for (let at = PARENTS[id]; at !== undefined; at = PARENTS[at]) {
    ancestors.push({ id: at, type: TYPES[at], hasChildren: true });
  }
  return { id, text: `node ${id}`, type: TYPES[id], ancestors };
}

function pair(sourceId: string, targetId: string, weight = 1): ResolvedPair {
  return {
    sourceId,
    targetId,
    source: PARENTS[sourceId] ? end(sourceId) : null,
    target: PARENTS[targetId] ? end(targetId) : null,
    weight,
  };
}

// A map showing these boxes (the root is never a box).
function mapOf(...ids: string[]): MapModel {
  const boxes = new Map<string, MapBox>();
  for (const id of ids) {
    const parentId = PARENTS[id] === "0" ? null : PARENTS[id];
    boxes.set(id, { id, parentId } as MapBox);
  }
  return { rootId: "0", boxes } as MapModel;
}

describe("parseGiven", () => {
  it("reads pairs and grouped targets, in order, without duplicates", () => {
    expect(parseGiven(["1-2", "3-4.5", "1-2"])).toEqual({
      pairs: [
        { sourceId: "1", targetId: "2" },
        { sourceId: "3", targetId: "4" },
        { sourceId: "3", targetId: "5" },
      ],
      malformed: [],
      cut: 0,
    });
  });

  it("reports entries that aren't pairs of ids", () => {
    expect(parseGiven(["1", "a-2", "1-2-3", "1-", "1-2.x"]).malformed).toEqual([
      "1",
      "a-2",
      "1-2-3",
      "1-",
      "1-2.x",
    ]);
  });

  it("cuts pairs over the cap and counts them", () => {
    const entries = Array.from(
      { length: MAX_GIVEN + 3 },
      (_, index) => `1-${index + 10}`,
    );
    const parsed = parseGiven(entries);
    expect(parsed.pairs).toHaveLength(MAX_GIVEN);
    expect(parsed.cut).toBe(3);
  });

  it("is empty without entries", () => {
    expect(parseGiven(undefined)).toEqual({ pairs: [], malformed: [], cut: 0 });
  });
});

describe("placeGiven", () => {
  it("draws a dependency between the visible boxes its ends lie in", () => {
    const placement = placeGiven(mapOf("1", "2"), [pair("111", "211", 3)]);
    expect(placement.arrows).toEqual([
      { fromBox: "1", toBox: "2", count: 1, weight: 3 },
    ]);
    expect(placement.pairs[0]).toMatchObject({
      drawn: true,
      fromBox: "1",
      toBox: "2",
    });
  });

  it("joins the dependencies between the same two boxes into one arrow", () => {
    const placement = placeGiven(mapOf("1", "2"), [
      pair("111", "211", 3),
      pair("121", "211", 2),
    ]);
    expect(placement.arrows).toEqual([
      { fromBox: "1", toBox: "2", count: 2, weight: 5 },
    ]);
  });

  it("marks the boxes the arrows join and the boxes around them", () => {
    const placement = placeGiven(mapOf("1", "2", "11", "12"), [
      pair("111", "211"),
    ]);
    expect([...placement.involved].sort()).toEqual(["1", "11", "2"]);
  });

  it("tells why a dependency isn't drawn", () => {
    const placement = placeGiven(mapOf("1", "2"), [
      pair("111", "999"),
      pair("1", "111"),
      pair("111", "211", 0),
      pair("111", "121"),
    ]);
    expect(placement.pairs.map((p) => (p.drawn ? "drawn" : p.reason))).toEqual([
      "unknown",
      "nested",
      "none",
      "inside",
    ]);
    expect(placement.pairs[3]).toMatchObject({ box: "1" });
    expect(placement.arrows).toEqual([]);
  });
});

describe("expansionFor", () => {
  it("opens the containers down to the innermost one holding both ends", () => {
    expect(expansionFor([pair("111", "121")])).toEqual(["1"]);
    expect(expansionFor([pair("111", "112")])).toEqual(["1", "11"]);
  });

  it("opens nothing for ends in different top-level boxes", () => {
    expect(expansionFor([pair("111", "211")])).toEqual([]);
  });

  it("doesn't open a type for two of its members", () => {
    expect(expansionFor([pair("1111", "1112")])).toEqual([]);
  });

  it("skips unknown, nested and missing dependencies", () => {
    expect(
      expansionFor([
        pair("111", "999"),
        pair("1", "111"),
        pair("111", "112", 0),
      ]),
    ).toEqual([]);
  });

  it("opens shallow containers first, at most the limit", () => {
    expect(expansionFor([pair("111", "112"), pair("111", "121")], 1)).toEqual([
      "1",
    ]);
  });
});
