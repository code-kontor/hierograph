// Makes a recorded GraphQL response independent of the machine and the moment
// it was recorded, so re-recording unchanged data yields identical files.
//
// - The workspace path (`server.workspace`) is replaced by `/fixture-app`
//   wherever it occurs, which covers the state directory, the manifest and the
//   output directories below it.
// - Times, the process id, the port, the server's version and its commit are
//   replaced by fixed values.
// - URLs keep their path but get a fixed origin.
// - The JVM's metrics (`jvm`) change with every request; they are replaced by
//   the fixed values below, keeping only the fields the query asked for.
//
// Everything else — the graph, its hash, the counts and the views — comes from
// the fixture-app and is deterministic.

export const FIXED_WORKSPACE = "/fixture-app";
export const FIXED_TIME = "2026-01-01T00:00:00Z";
export const FIXED_ORIGIN = "http://localhost:8765";
export const FIXED_PID = 4242;
export const FIXED_PORT = 8765;
export const FIXED_VERSION = "0.0.0-fixture";
export const FIXED_COMMIT = { id: "0000000", dirty: false, time: FIXED_TIME };

const MIB = 1024 * 1024;
export const FIXED_JVM = {
  javaVersion: "21.0.0",
  javaVendor: "Fixture Vendor",
  vmName: "Fixture VM",
  uptimeMs: 3_600_000,
  availableProcessors: 8,
  heap: {
    usedBytes: 512 * MIB,
    committedBytes: 1024 * MIB,
    maxBytes: 4096 * MIB,
    usedAfterGcBytes: 256 * MIB,
  },
  nonHeap: {
    usedBytes: 128 * MIB,
    committedBytes: 160 * MIB,
    maxBytes: null,
    usedAfterGcBytes: null,
  },
  garbageCollectors: [
    { name: "G1 Young Generation", count: 12, timeMs: 340 },
    { name: "G1 Concurrent GC", count: 3, timeMs: 25 },
    { name: "G1 Old Generation", count: 0, timeMs: 0 },
  ],
  threads: { live: 42, peak: 48, daemon: 30 },
  processCpuLoad: 0.05,
};

const TIME_KEYS = new Set(["startedAt", "loadedAt", "buildTime"]);
const URL_KEYS = new Set(["url", "uiUrl"]);

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

export function normalize(data: Json): Json {
  const workspace = findWorkspace(data);
  return visit(data, workspace);
}

function findWorkspace(data: Json): string | undefined {
  if (data === null || typeof data !== "object" || Array.isArray(data)) {
    return undefined;
  }
  const server = data.server;
  if (server === null || typeof server !== "object" || Array.isArray(server)) {
    return undefined;
  }
  return typeof server.workspace === "string" ? server.workspace : undefined;
}

function visit(value: Json, workspace: string | undefined, key?: string): Json {
  if (value === null) return null;
  if (Array.isArray(value)) {
    return value.map((item) => visit(item, workspace));
  }
  if (typeof value === "object") {
    if (key === "commit") return pick(value);
    if (key === "jvm") return shaped(value, FIXED_JVM as Json);
    const result: { [key: string]: Json } = {};
    for (const [childKey, child] of Object.entries(value)) {
      result[childKey] = visit(child, workspace, childKey);
    }
    return result;
  }
  if (key === "pid" && typeof value === "number") return FIXED_PID;
  if (key === "port" && typeof value === "number") return FIXED_PORT;
  if (key === "version" && typeof value === "string") return FIXED_VERSION;
  if (key !== undefined && TIME_KEYS.has(key) && typeof value === "string") {
    return FIXED_TIME;
  }
  if (key !== undefined && URL_KEYS.has(key) && typeof value === "string") {
    return FIXED_ORIGIN + new URL(value).pathname;
  }
  if (typeof value === "string" && workspace) {
    return value.split(workspace).join(FIXED_WORKSPACE);
  }
  return value;
}

// A recorded commit keeps only the fields the query asked for, with fixed values.
function pick(commit: { [key: string]: Json }): { [key: string]: Json } {
  const fixed: { [key: string]: Json } = FIXED_COMMIT;
  const result: { [key: string]: Json } = {};
  for (const key of Object.keys(commit)) {
    result[key] = key in fixed ? fixed[key] : commit[key];
  }
  return result;
}

// `fixed` cut to the shape of `recorded`: only the fields the query asked for,
// at every level. An array takes the shape of its first recorded element.
function shaped(recorded: Json, fixed: Json): Json {
  if (Array.isArray(fixed)) {
    const shape = Array.isArray(recorded) ? recorded[0] : undefined;
    return shape === undefined
      ? fixed
      : fixed.map((item) => shaped(shape, item));
  }
  if (
    fixed === null ||
    typeof fixed !== "object" ||
    recorded === null ||
    typeof recorded !== "object" ||
    Array.isArray(recorded)
  ) {
    return fixed;
  }
  const result: { [key: string]: Json } = {};
  for (const key of Object.keys(recorded)) {
    if (!(key in fixed)) {
      // A volatile value without a fixed one would make every recording differ.
      throw new Error(
        `No fixed value for the JVM metric "${key}": add it to FIXED_JVM in normalize.ts.`,
      );
    }
    result[key] = shaped(recorded[key], fixed[key]);
  }
  return result;
}
