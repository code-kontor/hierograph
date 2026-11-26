import { describe, expect, it } from "vitest";

import {
  buildFilesModeLabel,
  countLabel,
  formatBytes,
  formatDuration,
  formatMillis,
  formatPercent,
  formatTime,
  lastSegment,
  relativeTo,
  sourceKindLabel,
  testsIncludedLabel,
  watchModeLabel,
} from "./format";

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

describe("formatBytes", () => {
  it("uses binary units, one decimal below 10", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(512 * 1024 * 1024)).toBe("512 MiB");
    expect(formatBytes(4 * 1024 ** 3)).toBe("4 GiB");
    expect(formatBytes(1.5 * 1024 ** 3)).toBe("1.5 GiB");
  });
});

describe("formatDuration", () => {
  it("shows the two largest units", () => {
    expect(formatDuration(42_000)).toBe("42 s");
    expect(formatDuration(5 * 60_000 + 3000)).toBe("5 min 3 s");
    expect(formatDuration(3_600_000)).toBe("1 h 0 min");
    expect(formatDuration(2 * 86_400_000 + 3 * 3_600_000)).toBe("2 d 3 h");
  });
});

describe("formatMillis / formatPercent", () => {
  it("formats short spans and fractions", () => {
    expect(formatMillis(340)).toBe("340 ms");
    expect(formatMillis(1250)).toBe("1.3 s");
    expect(formatMillis(2000)).toBe("2 s");
    expect(formatPercent(0.05)).toBe("5%");
  });
});

describe("relativeTo", () => {
  it("shortens paths below the base and leaves others alone", () => {
    expect(relativeTo("/ws/app", "/ws/app/.hierograph/manifest.json")).toBe(
      ".hierograph/manifest.json",
    );
    expect(relativeTo("/ws/app/", "/ws/app/target")).toBe("target");
    expect(relativeTo("/ws/app", "/ws/app")).toBe(".");
    expect(relativeTo("/ws/app", "/ws/apple/x")).toBe("/ws/apple/x");
  });
});

describe("watchModeLabel / buildFilesModeLabel", () => {
  it("says what happens and keeps unknown modes", () => {
    expect(watchModeLabel("polling")).toMatch(/periodically/);
    expect(watchModeLabel("native")).toMatch(/on change/);
    expect(watchModeLabel("future")).toBe("future");
    expect(buildFilesModeLabel("notify")).toMatch(/out of date/);
    expect(buildFilesModeLabel("regenerate")).toMatch(/regenerated/);
    expect(buildFilesModeLabel(null)).toBe("—");
  });
});

describe("testsIncludedLabel", () => {
  const testOutput = {
    type: "java.module",
    text: "io.hierograph:server:0.4.0:test",
  };

  it("says yes or no without a view that leaves tests out", () => {
    expect(testsIncludedLabel(true, undefined)).toBe("yes");
    expect(testsIncludedLabel(false, [testOutput])).toBe("no");
    expect(
      testsIncludedLabel(true, [{ type: "java.package", text: "a.test" }]),
    ).toBe("yes");
  });

  it("names the test outputs the view leaves out", () => {
    expect(testsIncludedLabel(true, [testOutput])).toBe(
      "yes, 1 test output left out in this view",
    );
    expect(
      testsIncludedLabel(true, [
        testOutput,
        { ...testOutput, text: "io.hierograph:web:0.4.0:test" },
      ]),
    ).toBe("yes, 2 test outputs left out in this view");
  });
});
