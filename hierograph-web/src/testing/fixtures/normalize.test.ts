import { describe, expect, it } from "vitest";

import {
  FIXED_JVM,
  FIXED_ORIGIN,
  FIXED_PID,
  FIXED_TIME,
  FIXED_VERSION,
  FIXED_WORKSPACE,
  normalize,
} from "./normalize";

const recorded = {
  server: {
    url: "http://localhost:51234/mcp",
    uiUrl: "http://localhost:51234/ui/",
    port: 51234,
    pid: 91234,
    version: "0.4.0-SNAPSHOT",
    commit: { id: "127b0b2", dirty: true },
    startedAt: "2026-11-18T12:41:00.123Z",
    workspace: "/Users/someone/hierograph/examples/fixture-app",
    stateDir: "/Users/someone/hierograph/examples/fixture-app/.hierograph",
    artifacts: {
      notCompiled: [
        {
          artifact: "io.hierograph.examples:fixture-app:1.0.0:test",
          dirs: [
            "/Users/someone/hierograph/examples/fixture-app/target/test-classes",
          ],
        },
      ],
    },
    graph: {
      state: "LOADED",
      generation: "1",
      dataHash: "bmrtNIEm-A1CJU_I",
      loadedAt: "2026-11-18T12:41:04.855Z",
      counts: { modules: 1, total: 181 },
    },
    jvm: {
      javaVersion: "25.0.1",
      heap: { usedBytes: 123456789, maxBytes: null },
      garbageCollectors: [{ name: "ZGC Cycles", count: 7 }],
      processCpuLoad: null,
    },
  },
  views: [{ name: "layers", problems: [] }],
};

describe("normalize", () => {
  const result = normalize(recorded) as typeof recorded;

  it("replaces the workspace path everywhere", () => {
    expect(result.server.workspace).toBe(FIXED_WORKSPACE);
    expect(result.server.stateDir).toBe(`${FIXED_WORKSPACE}/.hierograph`);
    expect(result.server.artifacts.notCompiled[0].dirs).toEqual([
      `${FIXED_WORKSPACE}/target/test-classes`,
    ]);
  });

  it("fixes times, process, port, version, commit and URL origins", () => {
    expect(result.server.startedAt).toBe(FIXED_TIME);
    expect(result.server.graph.loadedAt).toBe(FIXED_TIME);
    expect(result.server.pid).toBe(FIXED_PID);
    expect(result.server.port).toBe(8765);
    expect(result.server.version).toBe(FIXED_VERSION);
    expect(result.server.commit).toEqual({ id: "0000000", dirty: false });
    expect(result.server.url).toBe(`${FIXED_ORIGIN}/mcp`);
    expect(result.server.uiUrl).toBe(`${FIXED_ORIGIN}/ui/`);
  });

  it("keeps the graph, the views and the artifact names", () => {
    expect(result.server.graph.generation).toBe("1");
    expect(result.server.graph.dataHash).toBe("bmrtNIEm-A1CJU_I");
    expect(result.server.graph.counts).toEqual({ modules: 1, total: 181 });
    expect(result.server.artifacts.notCompiled[0].artifact).toBe(
      "io.hierograph.examples:fixture-app:1.0.0:test",
    );
    expect(result.views).toEqual([{ name: "layers", problems: [] }]);
  });

  it("keeps nulls", () => {
    expect(
      normalize({
        server: { url: null, commit: null, graph: { loadedAt: null } },
      }),
    ).toEqual({
      server: { url: null, commit: null, graph: { loadedAt: null } },
    });
  });

  it("replaces the JVM's metrics by fixed ones, in the shape asked for", () => {
    expect(result.server.jvm).toEqual({
      javaVersion: FIXED_JVM.javaVersion,
      heap: {
        usedBytes: FIXED_JVM.heap.usedBytes,
        maxBytes: FIXED_JVM.heap.maxBytes,
      },
      garbageCollectors: FIXED_JVM.garbageCollectors.map(({ name, count }) => ({
        name,
        count,
      })),
      processCpuLoad: FIXED_JVM.processCpuLoad,
    });
  });

  it("is idempotent", () => {
    expect(normalize(result)).toEqual(result);
  });
});

describe("normalize, JVM metrics", () => {
  it("fails on a JVM metric without a fixed value", () => {
    expect(() => normalize({ server: { jvm: { somethingNew: 1 } } })).toThrow(
      /somethingNew/,
    );
  });
});
