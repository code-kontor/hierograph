import { describe, expect, it } from "vitest";

import { countLabel, formatTime, lastSegment, sourceKindLabel } from "./format";

describe("formatTime", () => {
  it("shows a dash for no time and the raw value for an unparsable one", () => {
    expect(formatTime(null)).toBe("—");
    expect(formatTime(undefined)).toBe("—");
    expect(formatTime("soon")).toBe("soon");
  });

  it("formats a valid time", () => {
    expect(formatTime("2026-01-01T00:00:00Z")).toMatch(/2026|25/);
  });
});

describe("lastSegment", () => {
  it("takes the last segment of a path", () => {
    expect(lastSegment("/work/fixture-app")).toBe("fixture-app");
    expect(lastSegment("/work/fixture-app/")).toBe("fixture-app");
    expect(lastSegment("C:\\work\\app")).toBe("app");
    expect(lastSegment("/")).toBe("/");
  });
});

describe("sourceKindLabel", () => {
  it("names the graph source", () => {
    expect(sourceKindLabel("manifest")).toBe("Build manifest");
    expect(sourceKindLabel("parquet")).toBe("Parquet snapshot");
    expect(sourceKindLabel(null)).toBe("Not configured");
  });
});

describe("countLabel", () => {
  it("picks singular or plural", () => {
    expect(countLabel(1, "module", "modules")).toBe("1 module");
    expect(countLabel(1234, "type", "types")).toBe("1,234 types");
  });
});
