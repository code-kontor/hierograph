import { describe, expect, it } from "vitest";

import {
  serializeTraceForClipboard,
  type SerializeTraceInput,
} from "./serializeTrace";

const baseInput: SerializeTraceInput = {
  from: { id: "n1", label: "com.example.source" },
  to: { id: "n2", label: "com.example.target" },
  sourceRows: [
    { id: "s1", text: "com.example.source.SubClass", type: "Class" },
  ],
  targetRows: [
    { id: "t1", text: "com.example.target.BaseClass", type: "Class" },
  ],
  driver: {
    side: "source",
    label: "SubClass",
    ids: ["s1"],
  },
  markedCounterpartIds: ["t1"],
  viewMode: "in-context",
  statusText: "SubClass → 1 type in com.example.target",
};

describe("serializeTraceForClipboard", () => {
  it("renders From/To, driver, view mode and status for an active driver", () => {
    const text = serializeTraceForClipboard(baseInput);

    expect(text).toContain("From: com.example.source");
    expect(text).toContain("To: com.example.target");
    expect(text).toContain("Driver: SubClass (source)");
    expect(text).toContain("View: in-context");
    expect(text).toContain("## Marked counterparts");
    expect(text).toContain("- t1");
    expect(text).toContain("Status: SubClass → 1 type in com.example.target");
  });

  it("renders a (none) driver and empty counterparts when nothing is selected", () => {
    const text = serializeTraceForClipboard({
      ...baseInput,
      driver: null,
      markedCounterpartIds: [],
      statusText: "Select a type to trace its counterparts.",
    });

    expect(text).toContain("Driver: (none)");
    expect(text).toContain("## Marked counterparts\n\n_(none)_");
  });

  it("lists visible source and target rows", () => {
    const text = serializeTraceForClipboard(baseInput);

    expect(text).toContain("## Source types (visible)");
    expect(text).toContain("1. com.example.source.SubClass");
    expect(text).toContain("## Target types (visible)");
    expect(text).toContain("1. com.example.target.BaseClass");
  });
});
