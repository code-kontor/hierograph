import { expect, it } from "vitest";

import { normalizeSubjectIds } from "./normalizeSubjectIds";

it("disjoint set — no ancestors shared, both ids kept and sorted", () => {
  const predecessorsById = new Map([
    ["alpha", new Set<string>()],
    ["beta", new Set<string>()],
  ]);
  expect(normalizeSubjectIds(["beta", "alpha"], predecessorsById)).toEqual([
    "alpha",
    "beta",
  ]);
});

it("nested — child dropped when ancestor is also selected", () => {
  const predecessorsById = new Map([
    ["parent", new Set<string>()],
    ["child", new Set(["parent"])],
  ]);
  expect(normalizeSubjectIds(["child", "parent"], predecessorsById)).toEqual([
    "parent",
  ]);
});

it("nested reverse order — same result regardless of input order", () => {
  const predecessorsById = new Map([
    ["parent", new Set<string>()],
    ["child", new Set(["parent"])],
  ]);
  expect(normalizeSubjectIds(["parent", "child"], predecessorsById)).toEqual([
    "parent",
  ]);
});

it("duplicates — deduplicated to a single entry", () => {
  const predecessorsById = new Map([["alpha", new Set<string>()]]);
  expect(normalizeSubjectIds(["alpha", "alpha"], predecessorsById)).toEqual([
    "alpha",
  ]);
});

it("empty input — returns empty array", () => {
  expect(normalizeSubjectIds([], new Map())).toEqual([]);
});

it("no predecessors info — all ids kept (safe fallback)", () => {
  // predecessorsById has no entry for either id
  expect(normalizeSubjectIds(["x", "y"], new Map())).toEqual(["x", "y"]);
});
