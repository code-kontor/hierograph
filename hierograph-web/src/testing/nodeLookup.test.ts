import { describe, expect, it } from "vitest";

import { resolveNodeId, resolveNodeIds } from "./nodeLookup";

describe("resolveNodeId", () => {
  it("resolves a basic-tenant fqn to a non-empty id", () => {
    const id = resolveNodeId("org.hg.fixture.basic.rel.source");
    expect(id).toBeTypeOf("string");
    expect(id.length).toBeGreaterThan(0);
  });

  it("resolves a deeply nested locations-tenant fqn", () => {
    expect(
      resolveNodeId("org.hg.fixture.locations.lib.order.detail.OrderLine"),
    ).toBeTruthy();
  });

  it("resolves distinct ids for distinct fqns", () => {
    const [source, target] = resolveNodeIds(
      "org.hg.fixture.basic.rel.source",
      "org.hg.fixture.basic.rel.target",
    );
    expect(source).not.toEqual(target);
  });

  it("throws a descriptive error for an unknown fqn", () => {
    expect(() => resolveNodeId("org.hg.does.not.Exist")).toThrow(
      /no node with fqn/i,
    );
  });
});
