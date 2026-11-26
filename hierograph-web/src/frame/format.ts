// Display helpers of the start screen.

// An ISO-8601 time in the browser's locale and time zone.
export function formatTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "medium",
  });
}

// The last segment of a path: the workspace's name.
export function lastSegment(path: string): string {
  const segments = path.split(/[/\\]/).filter((part) => part.length > 0);
  return segments.at(-1) ?? path;
}

export function sourceKindLabel(kind: string | null | undefined): string {
  switch (kind) {
    case "manifest":
      return "Build manifest";
    case "parquet":
      return "Parquet snapshot";
    default:
      return "Not configured";
  }
}

export function yesNo(value: boolean | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return value ? "yes" : "no";
}

// Whether the graph has the tests, and whether the current view shows them:
// a view can leave test outputs out (modules keyed `…:test`), which a plain
// "yes" would hide.
export function testsIncludedLabel(
  includeTests: boolean | null | undefined,
  excluded: readonly { type: string; text: string }[] | null | undefined,
): string {
  const label = yesNo(includeTests);
  if (includeTests !== true || !excluded) return label;
  const left = excluded.filter(
    (node) => node.type === "java.module" && node.text.endsWith(":test"),
  ).length;
  if (left === 0) return label;
  return `${label}, ${left === 1 ? "1 test output" : `${left} test outputs`} left out in this view`;
}

export function formatCount(count: number): string {
  return count.toLocaleString("en");
}

export function countLabel(count: number, singular: string, plural: string) {
  return `${formatCount(count)} ${count === 1 ? singular : plural}`;
}

const BYTE_UNITS = ["B", "KiB", "MiB", "GiB", "TiB"];

// A byte count in binary units: one decimal below 10, none above
// (`1.5 GiB`, `512 MiB`).
export function formatBytes(bytes: number): string {
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < BYTE_UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const digits = unit > 0 && value < 10 ? 1 : 0;
  const text = value.toFixed(digits).replace(/\.0$/, "");
  return `${text} ${BYTE_UNITS[unit]}`;
}

// A duration in its two largest units (`2 d 3 h`, `1 h 5 min`, `42 s`).
export function formatDuration(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days} d ${hours} h`;
  if (hours > 0) return `${hours} h ${minutes} min`;
  if (minutes > 0) return `${minutes} min ${seconds % 60} s`;
  return `${seconds} s`;
}

// A short time span: milliseconds below a second, seconds above.
export function formatMillis(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)} ms`;
  return `${(ms / 1000).toFixed(1).replace(/\.0$/, "")} s`;
}

// A fraction from 0 to 1 as a whole percentage.
export function formatPercent(fraction: number): string {
  return `${Math.round(fraction * 100)}%`;
}

// A path below `base`, relative to it (`.hierograph/manifest.json`); any other
// path unchanged.
export function relativeTo(base: string, path: string): string {
  const prefix = base.endsWith("/") ? base : `${base}/`;
  if (path === base) return ".";
  return path.startsWith(prefix) ? path.slice(prefix.length) : path;
}

// How build outputs are watched, as what happens rather than the mechanism.
export function watchModeLabel(mode: string | null | undefined): string {
  switch (mode) {
    case "polling":
      return "checked for changes periodically (polling)";
    case "native":
      return "picked up on change (file-system events)";
    case "off":
      return "not watched: the graph changes only on a reload";
    default:
      return mode ?? "—";
  }
}

// What happens when a build file (a POM, a Gradle script) changes.
export function buildFilesModeLabel(mode: string | null | undefined): string {
  switch (mode) {
    case "notify":
      return "on change, reported as out of date until the next build";
    case "regenerate":
      return "on change, the manifest is regenerated";
    case "off":
      return "not watched";
    default:
      return mode ?? "—";
  }
}
