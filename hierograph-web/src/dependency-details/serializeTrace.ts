export type TraceSide = "source" | "target";
export type TraceViewMode = "in-context" | "hits-only";

export type SerializeTraceInput = {
  from: { id: string; label: string };
  to: { id: string; label: string };
  sourceRows: { id: string; text: string; type: string }[];
  targetRows: { id: string; text: string; type: string }[];
  driver: { side: TraceSide; label: string; ids: string[] } | null;
  markedCounterpartIds: string[];
  viewMode: TraceViewMode;
  statusText: string;
};

function buildRowsSection(title: string, rows: { text: string }[]): string[] {
  return [
    title,
    "",
    ...(rows.length === 0
      ? ["_(none)_"]
      : rows.map((row, index) => `${index + 1}. ${row.text}`)),
  ];
}

function buildDriverLine(driver: SerializeTraceInput["driver"]): string {
  return driver ? `Driver: ${driver.label} (${driver.side})` : "Driver: (none)";
}

function buildCounterpartsSection(markedCounterpartIds: string[]): string[] {
  return [
    "## Marked counterparts",
    "",
    ...(markedCounterpartIds.length === 0
      ? ["_(none)_"]
      : markedCounterpartIds.map((id) => `- ${id}`)),
  ];
}

export function serializeTraceForClipboard(input: SerializeTraceInput): string {
  const {
    from,
    to,
    sourceRows,
    targetRows,
    driver,
    markedCounterpartIds,
    viewMode,
    statusText,
  } = input;

  const lines: string[] = [
    "# Paths",
    "",
    `From: ${from.label}`,
    `To: ${to.label}`,
    buildDriverLine(driver),
    `View: ${viewMode}`,
    "",
    ...buildRowsSection("## Source types (visible)", sourceRows),
    "",
    ...buildRowsSection("## Target types (visible)", targetRows),
    "",
    ...buildCounterpartsSection(markedCounterpartIds),
    "",
    `Status: ${statusText}`,
  ];

  return lines.join("\n");
}
