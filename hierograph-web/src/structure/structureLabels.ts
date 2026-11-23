// How a box on the map is named: relative to its container, so a level doesn't
// repeat what the box around it already says. The same rules as the start
// screen's tree (`start/treeLabels.ts`); screens don't import each other.
//
// - a view group: its name without the view (`layers:application` →
//   `application`);
// - a module: the artifact id, with a classifier in parentheses
//   (`io.hierograph:server:0.4.0:test` → `server (test)`);
// - a package or type below a package: the part after the container's name
//   (`a.b.C` below `a.b` → `C`);
// - anything else: its qualified name.
export function structureLabel(
  type: string,
  text: string,
  parentText: string,
): string {
  if (type === "view.group") {
    const colon = text.indexOf(":");
    return colon >= 0 ? text.slice(colon + 1) : text;
  }
  if (type === "java.module") {
    const parts = text.split(":");
    if (parts.length < 3) return text;
    return parts.length > 3 ? `${parts[1]} (${parts[3]})` : parts[1];
  }
  if (parentText && text.startsWith(`${parentText}.`)) {
    return text.slice(parentText.length + 1);
  }
  return text;
}

// Whether a node is a container on the map: a type's members aren't shown, as
// their dependencies aren't resolved yet.
export function isContainer(type: string, hasChildren: boolean): boolean {
  const isType =
    type.startsWith("java.") &&
    type !== "java.module" &&
    type !== "java.package";
  return hasChildren && !isType && type !== "external.type";
}

export type StructureKind =
  "group" | "module" | "external" | "package" | "type" | "member" | "other";

const TYPE_KINDS = new Set([
  "java.class",
  "java.interface",
  "java.enum",
  "java.record",
  "java.annotation",
]);

// What a box is, for its icon: the same kinds as the start screen's tree
// (`start/treeNodeKind`).
export function structureKind(type: string, text: string): StructureKind {
  if (type === "view.group") return "group";
  if (type === "java.module") {
    return text === "External Types" ? "external" : "module";
  }
  if (type === "java.package") return "package";
  if (TYPE_KINDS.has(type) || type === "external.type") return "type";
  if (type === "java.method" || type === "java.field") return "member";
  return "other";
}
