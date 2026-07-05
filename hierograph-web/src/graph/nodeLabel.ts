export type NodeLabelFormat = "full" | "abbreviated" | "last-segment";

type NodeTypeFamily =
  "package" | "type" | "external-type" | "member" | "module" | "unknown";

const familyByNodeType: Record<string, NodeTypeFamily> = {
  "java.package": "package",
  "java.class": "type",
  "java.interface": "type",
  "java.enum": "type",
  "java.record": "type",
  "java.annotation": "type",
  "external.type": "external-type",
  "java.method": "member",
  "java.field": "member",
  "java.module": "module",
};

function familyOf(nodeType?: string): NodeTypeFamily {
  if (nodeType === undefined) {
    return "unknown";
  }
  return familyByNodeType[nodeType] ?? "unknown";
}

function formatFqn(text: string, format: NodeLabelFormat): string {
  if (format === "full") {
    return text;
  }
  if (format === "last-segment") {
    const lastDotSegment = text.split(".").at(-1) ?? text;
    return lastDotSegment.split("$").at(-1) ?? lastDotSegment;
  }
  // format === "abbreviated"
  const segments = text.split(".");
  if (segments.length <= 1) {
    return text;
  }
  const abbreviated = segments.slice(0, -1).map((seg) => seg.charAt(0) || seg);
  return [...abbreviated, segments[segments.length - 1]].join(".");
}

function formatMember(text: string, format: NodeLabelFormat): string {
  if (format === "full") {
    return text;
  }
  const separatorIndex = text.indexOf("#");
  if (separatorIndex === -1) {
    return formatFqn(text, format);
  }
  const owner = text.slice(0, separatorIndex);
  const signature = text.slice(separatorIndex + 1);
  const memberName =
    signature.split("(")[0].trim().split(/\s+/).at(-1) ?? signature;
  if (format === "last-segment") {
    return memberName;
  }
  // format === "abbreviated"
  return `${formatFqn(owner, "abbreviated")}#${memberName}`;
}

function formatModule(text: string, format: NodeLabelFormat): string {
  if (format === "full") {
    return text;
  }
  if (text.endsWith(".jar")) {
    const base = text.slice(0, -4);
    if (format === "last-segment") {
      return base.replace(/-\d[\w.]*$/, "");
    }
    return base;
  }
  const parts = text.split(":");
  if (parts.length >= 2) {
    const artifact = parts[1];
    if (format === "last-segment") {
      return artifact;
    }
    const version = parts.at(-1);
    return `${artifact}:${version}`;
  }
  return text;
}

export function formatNodeLabel(
  text: string,
  format: NodeLabelFormat,
  nodeType?: string,
): string {
  if (!text) {
    return text;
  }
  const family = familyOf(nodeType);
  switch (family) {
    case "member":
      return formatMember(text, format);
    case "module":
      return formatModule(text, format);
    case "package":
    case "type":
    case "external-type":
    case "unknown":
      return formatFqn(text, format);
  }
}
