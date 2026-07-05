import { describe, expect, it } from "vitest";

import { formatNodeLabel } from "./nodeLabel";

describe("formatNodeLabel", () => {
  it("returns the text unchanged for the full format", () => {
    expect(formatNodeLabel("net.example.pkg.Owner", "full")).toBe(
      "net.example.pkg.Owner",
    );
  });

  it("abbreviates every package segment except the last for abbreviated", () => {
    expect(formatNodeLabel("net.example.pkg.Owner", "abbreviated")).toBe(
      "n.e.p.Owner",
    );
  });

  it("keeps java.module nodes unshortened even when abbreviated", () => {
    expect(
      formatNodeLabel("net.example.pkg.Owner", "abbreviated", "java.module"),
    ).toBe("net.example.pkg.Owner");
  });

  it("returns only the last segment for last-segment", () => {
    expect(formatNodeLabel("net.example.pkg.Owner", "last-segment")).toBe(
      "Owner",
    );
  });

  it("returns empty input unchanged", () => {
    expect(formatNodeLabel("", "abbreviated")).toBe("");
  });

  it("returns a single-segment name unchanged when abbreviated", () => {
    expect(formatNodeLabel("Owner", "abbreviated")).toBe("Owner");
  });
});
