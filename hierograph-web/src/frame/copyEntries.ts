import { treeNodeKind, treeNodeLabel } from "@/design-system/graph/treeLabels";

// What the context menus' Copy submenu offers (#88), as IntelliJ IDEA's
// "Copy Path/Reference…" does: each entry with the value it copies, and only
// those that make sense for the element.

export type CopyEntry = {
  label: string;
  value: string;
};

export type CopyNode = {
  text: string;
  type: string;
};

// Where a type's source file is, as the server's node properties name it:
// relative to the workspace, to its source root and to the repository root,
// with `/`. Each one only when known.
export type SourceLocation = {
  workspacePath?: string;
  sourceRootPath?: string;
  repositoryPath?: string;
};

// The entries for a node:
// - every node: its simple name (a nested type's own name, a package's last
//   segment, a module's artifact id);
// - a type or a package: its fully qualified name, a nested type's with `.`
//   (`a.B$C` → `a.B.C`), as Java and Kotlin source write it;
// - a type with a known source file: its path, absolute (below `workspace`),
//   from the source root and from the repository root;
// - a module: its coordinates, `groupId:artifactId:version`.
export function nodeCopyEntries(
  node: CopyNode,
  source: SourceLocation | undefined,
  workspace: string | undefined,
): CopyEntry[] {
  const kind = treeNodeKind(node.type, node.text);
  const entries: CopyEntry[] = [
    { label: "Simple name", value: simpleName(node) },
  ];
  if (kind === "type" || kind === "package") {
    entries.push({ label: "Fully qualified name", value: qualifiedName(node) });
  }
  if (kind === "type" && source !== undefined) {
    if (source.workspacePath !== undefined && workspace) {
      entries.push({
        label: "Absolute path",
        value: joinPath(workspace, source.workspacePath),
      });
    }
    if (source.sourceRootPath !== undefined) {
      entries.push({
        label: "Path from source root",
        value: source.sourceRootPath,
      });
    }
    if (source.repositoryPath !== undefined) {
      entries.push({
        label: "Path from repository root",
        value: source.repositoryPath,
      });
    }
  }
  if (kind === "module") {
    const coordinates = moduleCoordinates(node.text);
    if (coordinates !== undefined) {
      entries.push({ label: "Module coordinates", value: coordinates });
    }
  }
  return entries;
}

// The entries for a dependency: both ends by their qualified names, joined by
// an arrow, and each end alone.
export function dependencyCopyEntries(
  source: CopyNode,
  target: CopyNode,
): CopyEntry[] {
  const from = qualifiedName(source);
  const to = qualifiedName(target);
  return [
    { label: "Dependency", value: `${from} → ${to}` },
    { label: "Source", value: from },
    { label: "Target", value: to },
  ];
}

// Whether the node's entries need its source file from the server: a type
// of the workspace's code (a library type has none).
export function needsSourceLocation(node: CopyNode): boolean {
  return (
    treeNodeKind(node.type, node.text) === "type" &&
    node.type !== "external.type"
  );
}

// Reads the source location from a node's properties.
export function sourceLocation(
  properties: { key: string; value?: string | null }[],
): SourceLocation {
  const value = (key: string) =>
    properties.find((entry) => entry.key === key)?.value ?? undefined;
  return {
    workspacePath: value("workspacePath"),
    sourceRootPath: value("sourceRootPath"),
    repositoryPath: value("repositoryPath"),
  };
}

function simpleName(node: CopyNode): string {
  switch (treeNodeKind(node.type, node.text)) {
    case "type":
    case "package":
      return lastSegment(qualifiedName(node));
    default:
      // A module's artifact id, a group's name, a member's signature.
      return treeNodeLabel(node.type, node.text, undefined);
  }
}

// A type's or package's qualified name, as source writes it; anything else's
// text.
function qualifiedName(node: CopyNode): string {
  return treeNodeKind(node.type, node.text) === "type"
    ? node.text.replaceAll("$", ".")
    : node.text;
}

function lastSegment(name: string): string {
  return name.slice(name.lastIndexOf(".") + 1);
}

// `groupId:artifactId:version` of a module's artifact key, without a
// classifier (`…:0.4.0:test` names the module's tests).
function moduleCoordinates(key: string): string | undefined {
  const parts = key.split(":");
  return parts.length < 3 ? undefined : parts.slice(0, 3).join(":");
}

// A path below the workspace, with the workspace's separator: `\` for a
// Windows path.
function joinPath(workspace: string, relative: string): string {
  const windows = workspace.includes("\\") && !workspace.includes("/");
  const separator = windows ? "\\" : "/";
  const base = workspace.replace(/[\\/]+$/, "");
  return `${base}${separator}${windows ? relative.replaceAll("/", "\\") : relative}`;
}
