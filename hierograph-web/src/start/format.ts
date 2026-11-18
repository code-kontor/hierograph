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

export function formatCount(count: number): string {
  return count.toLocaleString("en");
}

export function countLabel(count: number, singular: string, plural: string) {
  return `${formatCount(count)} ${count === 1 ? singular : plural}`;
}
