// Makes a recorded GraphQL response independent of the machine and the moment
// it was recorded, so re-recording unchanged data yields identical files.
//
// - The workspace path (`server.workspace`) is replaced by `/fixture-app`
//   wherever it occurs, which covers the state directory, the manifest and the
//   output directories below it.
// - Times, the process id, the port, the server's version and its commit are
//   replaced by fixed values.
// - URLs keep their path but get a fixed origin.
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
