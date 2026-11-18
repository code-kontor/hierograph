// How the hierarchy tree names a node: relative to its parent, so a level
// doesn't repeat what the level above already says.

export type TreeNodeKind =
  "group" | "module" | "external" | "package" | "type" | "member" | "other";

const TYPE_KINDS = new Set([
  "java.class",
  "java.interface",
  "java.enum",
  "java.record",
  "java.annotation",
]);

export function treeNodeKind(type: string, text: string): TreeNodeKind {
  if (type === "view.group") return "group";
  if (type === "java.module") {
    return text === "External Types" ? "external" : "module";
  }
  if (type === "java.package") return "package";
  if (TYPE_KINDS.has(type) || type === "external.type") return "type";
  if (type === "java.method" || type === "java.field") return "member";
  return "other";
}

// - a view group: its name without the view (`layers:application` →
//   `application`);
// - a module: the artifact id, with a classifier in parentheses
//   (`io.hierograph:server:0.4.0:test` → `server (test)`);
// - a member: its signature without the owner (`a.B#run()` → `run()`);
// - a package or type below a package: the part after the parent's name
//   (`a.b.C` below `a.b` → `C`);
// - anything else: its qualified name.
export function treeNodeLabel(
  type: string,
  text: string,
  parentText: string | undefined,
): string {
  if (type === "view.group") {
    const colon = text.indexOf(":");
    return colon >= 0 ? text.slice(colon + 1) : text;
  }
  if (type === "java.module") return moduleLabel(text);
  const hash = text.indexOf("#");
  if (hash >= 0) return text.slice(hash + 1);
  if (parentText && text.startsWith(`${parentText}.`)) {
    return text.slice(parentText.length + 1);
  }
  return text;
}

function moduleLabel(text: string): string {
  const parts = text.split(":");
  if (parts.length < 3) return text;
  const artifactId = parts[1];
  const classifier = parts.length > 3 ? parts[3] : undefined;
  return classifier ? `${artifactId} (${classifier})` : artifactId;
}
