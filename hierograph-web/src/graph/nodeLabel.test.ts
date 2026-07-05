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

  describe("package (java.package)", () => {
    const text = "org.hg.fixture.basic.core";

    it("full", () => {
      expect(formatNodeLabel(text, "full", "java.package")).toBe(text);
    });

    it("last-segment (own name)", () => {
      expect(formatNodeLabel(text, "last-segment", "java.package")).toBe(
        "core",
      );
    });

    it("abbreviated", () => {
      expect(formatNodeLabel(text, "abbreviated", "java.package")).toBe(
        "o.h.f.b.core",
      );
    });
  });

  describe("type (java.class)", () => {
    const text = "org.hg.fixture.basic.core.AbstractBase";

    it("full", () => {
      expect(formatNodeLabel(text, "full", "java.class")).toBe(text);
    });

    it("last-segment (own name)", () => {
      expect(formatNodeLabel(text, "last-segment", "java.class")).toBe(
        "AbstractBase",
      );
    });

    it("abbreviated", () => {
      expect(formatNodeLabel(text, "abbreviated", "java.class")).toBe(
        "o.h.f.b.c.AbstractBase",
      );
    });
  });

  describe("nested type (java.class with $)", () => {
    const text = "org.hg.fixture.basic.core.Outer$Inner";

    it("last-segment splits off the outer class", () => {
      expect(formatNodeLabel(text, "last-segment", "java.class")).toBe("Inner");
    });

    it("abbreviated keeps the last segment whole (no $ split)", () => {
      expect(formatNodeLabel(text, "abbreviated", "java.class")).toBe(
        "o.h.f.b.c.Outer$Inner",
      );
    });
  });

  describe("external type (external.type)", () => {
    const text = "java.util.List";

    it("last-segment (own name)", () => {
      expect(formatNodeLabel(text, "last-segment", "external.type")).toBe(
        "List",
      );
    });

    it("abbreviated", () => {
      expect(formatNodeLabel(text, "abbreviated", "external.type")).toBe(
        "j.u.List",
      );
    });
  });

  describe("method (java.method)", () => {
    const text =
      "org.hg.fixture.basic.core.Pair#boolean equals(java.lang.Object)";

    it("full", () => {
      expect(formatNodeLabel(text, "full", "java.method")).toBe(text);
    });

    it("last-segment (own name)", () => {
      expect(formatNodeLabel(text, "last-segment", "java.method")).toBe(
        "equals",
      );
    });

    it("abbreviated", () => {
      expect(formatNodeLabel(text, "abbreviated", "java.method")).toBe(
        "o.h.f.b.c.Pair#equals",
      );
    });
  });

  describe("field (java.field)", () => {
    const text = "org.hg.fixture.basic.core.PlainClass#int publicField";

    it("full", () => {
      expect(formatNodeLabel(text, "full", "java.field")).toBe(text);
    });

    it("last-segment (own name)", () => {
      expect(formatNodeLabel(text, "last-segment", "java.field")).toBe(
        "publicField",
      );
    });

    it("abbreviated", () => {
      expect(formatNodeLabel(text, "abbreviated", "java.field")).toBe(
        "o.h.f.b.c.PlainClass#publicField",
      );
    });
  });

  describe("module GAV form (java.module)", () => {
    const text = "io.hierograph.examples:fixture-app:1.0.0";

    it("full", () => {
      expect(formatNodeLabel(text, "full", "java.module")).toBe(text);
    });

    it("last-segment (own name)", () => {
      expect(formatNodeLabel(text, "last-segment", "java.module")).toBe(
        "fixture-app",
      );
    });

    it("abbreviated", () => {
      expect(formatNodeLabel(text, "abbreviated", "java.module")).toBe(
        "fixture-app:1.0.0",
      );
    });
  });

  describe("module jar form (java.module)", () => {
    const text = "spring-messaging-7.0.8.jar";

    it("full", () => {
      expect(formatNodeLabel(text, "full", "java.module")).toBe(text);
    });

    it("last-segment (own name)", () => {
      expect(formatNodeLabel(text, "last-segment", "java.module")).toBe(
        "spring-messaging",
      );
    });

    it("abbreviated", () => {
      expect(formatNodeLabel(text, "abbreviated", "java.module")).toBe(
        "spring-messaging-7.0.8",
      );
    });
  });

  describe("unknown or missing nodeType behaves like the FQN family", () => {
    const text = "net.example.pkg.Owner";

    it("falls back to the FQN formatter for an unknown nodeType string", () => {
      expect(formatNodeLabel(text, "last-segment", "some.unknown.type")).toBe(
        "Owner",
      );
      expect(formatNodeLabel(text, "abbreviated", "some.unknown.type")).toBe(
        "n.e.p.Owner",
      );
    });

    it("falls back to the FQN formatter when nodeType is undefined", () => {
      expect(formatNodeLabel(text, "last-segment", undefined)).toBe("Owner");
      expect(formatNodeLabel(text, "abbreviated", undefined)).toBe(
        "n.e.p.Owner",
      );
    });
  });
});
