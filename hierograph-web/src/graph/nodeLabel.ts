export type NodeLabelFormat = "full" | "abbreviated" | "last-segment";

export function formatNodeLabel(
  text: string,
  format: NodeLabelFormat,
  nodeType?: string,
): string {
  if (!text) {
    return text;
  }
  if (format === "full" || nodeType === "java.module") {
    return text;
  }
  if (format === "last-segment") {
    return text.split(".").at(-1) ?? text;
  }
  // format === "abbreviated"
  const segments = text.split(".");
  if (segments.length <= 1) {
    return text;
  }
  const abbreviated = segments.slice(0, -1).map((seg) => seg.charAt(0) || seg);
  return [...abbreviated, segments[segments.length - 1]].join(".");
}
