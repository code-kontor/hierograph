import { buildMatrixElements } from "./dsmModel";

export type DsmSubject =
  | { kind: "single"; name: string; nodeType?: string }
  | { kind: "multi"; count: number; nodes: { text: string; type?: string }[] };

export type SerializeDsmInput = {
  subject: DsmSubject;
  labels: { id: string; text: string; type?: string }[];
  cells: { row: number; column: number; value: number }[];
  sccs: { nodePositions: number[] }[];
  showDiagonal: boolean;
};

function isVisible(
  cell: { row: number; column: number; value: number },
  showDiagonal: boolean,
): boolean {
  return cell.value !== 0 && (cell.row !== cell.column || showDiagonal);
}

function buildSelectionLines(subject: DsmSubject): string[] {
  if (subject.kind === "single") {
    return [
      `Selection: ${subject.name}${subject.nodeType ? ` (${subject.nodeType})` : ""}`,
    ];
  }
  return [
    `Selection: ${subject.count} selected nodes`,
    ...subject.nodes.map((node) => `- ${node.text}`),
  ];
}

function buildNodesSection(labels: { text: string }[]): string[] {
  return [
    "## Nodes (in matrix order)",
    "",
    ...labels.map((label, index) => `${index + 1}. ${label.text}`),
  ];
}

function buildDependenciesSection(
  labels: { text: string }[],
  cells: { row: number; column: number; value: number }[],
  showDiagonal: boolean,
): string[] {
  const visibleCells = cells
    .filter((cell) => isVisible(cell, showDiagonal))
    .sort((a, b) => a.row - b.row || a.column - b.column);

  const lines =
    visibleCells.length === 0
      ? ["_(none)_"]
      : visibleCells.map(
          (cell) =>
            `${labels[cell.row].text} → ${labels[cell.column].text}: ${cell.value}`,
        );

  return ["## Dependencies (source → target: weight)", "", ...lines];
}

function buildCyclesSection(
  labels: { text: string }[],
  sccs: { nodePositions: number[] }[],
): string[] {
  const cycles = sccs.filter((scc) => scc.nodePositions.length >= 2);

  const lines =
    cycles.length === 0
      ? ["_(none)_"]
      : cycles.map(
          (scc, index) =>
            `Cycle ${index + 1}: ${scc.nodePositions.map((p) => labels[p].text).join(", ")}`,
        );

  return ["## Cycles (strongly connected components)", "", ...lines];
}

function buildMatrixSection(
  labels: { text: string }[],
  cells: { row: number; column: number; value: number }[],
  showDiagonal: boolean,
): string[] {
  const elements = buildMatrixElements(cells);
  const columnNumbers = labels.map((_, index) => index + 1);

  const headerRow = `|  | ${columnNumbers.join(" | ")} |`;
  const separatorRow = `| --- | ${columnNumbers.map(() => "---").join(" | ")} |`;
  const dataRows = labels.map((_, row) => {
    const rowCells = columnNumbers.map((_, column) => {
      const cell = elements[column]?.[row];
      return cell && isVisible(cell, showDiagonal) ? String(cell.value) : "";
    });
    return `| ${row + 1} | ${rowCells.join(" | ")} |`;
  });

  return [
    "## Matrix (rows = source, columns = target)",
    "",
    headerRow,
    separatorRow,
    ...dataRows,
  ];
}

export function serializeDsmForClipboard(input: SerializeDsmInput): string {
  const { subject, labels, cells, sccs, showDiagonal } = input;

  const visibleDependencyCount = cells.filter((cell) =>
    isVisible(cell, showDiagonal),
  ).length;

  const lines: string[] = [
    "# Dependency Structure Matrix",
    "",
    ...buildSelectionLines(subject),
    `Show diagonal: ${showDiagonal ? "on" : "off"}`,
    `Nodes: ${labels.length}`,
    `Dependencies: ${visibleDependencyCount}`,
    "",
    ...buildNodesSection(labels),
    "",
    ...buildDependenciesSection(labels, cells, showDiagonal),
    "",
    ...buildCyclesSection(labels, sccs),
    "",
    ...buildMatrixSection(labels, cells, showDiagonal),
  ];

  return lines.join("\n");
}
