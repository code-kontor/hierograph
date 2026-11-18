import { describe, expect, it } from "vitest";

import { ordersChildren, treeNodeKind, treeNodeLabel } from "./treeLabels";

describe("treeNodeLabel", () => {
  it("names a view group without its view", () => {
    expect(treeNodeLabel("view.group", "layers:application", "")).toBe(
      "application",
    );
  });

  it("names a module by its artifact id and classifier", () => {
    expect(treeNodeLabel("java.module", "io.hierograph:server:0.4.0", "")).toBe(
      "server",
    );
    expect(
      treeNodeLabel("java.module", "io.hierograph:server:0.4.0:test", ""),
    ).toBe("server (test)");
    expect(treeNodeLabel("java.module", "External Types", "")).toBe(
      "External Types",
    );
  });

  it("names packages and types relative to their parent package", () => {
    expect(treeNodeLabel("java.package", "java.nio", "java")).toBe("nio");
    expect(treeNodeLabel("java.package", "org.hg.app", "io.x:app:1")).toBe(
      "org.hg.app",
    );
    expect(treeNodeLabel("java.class", "org.hg.app.Main", "org.hg.app")).toBe(
      "Main",
    );
  });

  it("names a member by its signature", () => {
    expect(
      treeNodeLabel(
        "java.method",
        "org.hg.app.Main#run(int)",
        "org.hg.app.Main",
      ),
    ).toBe("run(int)");
  });
});

describe("treeNodeKind", () => {
  it("tells modules, the external module, packages, types and members apart", () => {
    expect(treeNodeKind("view.group", "l:a")).toBe("group");
    expect(treeNodeKind("java.module", "g:a:1")).toBe("module");
    expect(treeNodeKind("java.module", "External Types")).toBe("external");
    expect(treeNodeKind("java.package", "a")).toBe("package");
    expect(treeNodeKind("java.record", "a.R")).toBe("type");
    expect(treeNodeKind("external.type", "java.lang.String")).toBe("type");
    expect(treeNodeKind("java.field", "a.B#x")).toBe("member");
    expect(treeNodeKind("Unknown", "")).toBe("other");
  });
});

describe("ordersChildren", () => {
  it("orders all children but a type's members", () => {
    expect(ordersChildren("view.group", "l:a")).toBe(true);
    expect(ordersChildren("java.module", "g:a:1")).toBe(true);
    expect(ordersChildren("java.package", "a")).toBe(true);
    expect(ordersChildren("java.class", "a.B")).toBe(false);
  });
});
