export type TreeLabelFormat = "full" | "shortened";

export function formatTreeLabel(
  text: string,
  nodeType: string,
  format: TreeLabelFormat,
): string {
  if (format === "full" || nodeType === "java.module") {
    return text;
  }
  const segments = text.split(".");
  if (segments.length < 2) {
    return text;
  }
  const abbreviated = segments
    .slice(0, -1)
    .map((s) => s.charAt(0))
    .concat(segments[segments.length - 1]);
  return abbreviated.join(".");
}
