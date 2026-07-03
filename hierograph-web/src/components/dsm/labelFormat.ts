export type LabelFormat = "full" | "abbreviated" | "last-segment";

export const LABEL_FORMAT_OPTIONS: { value: LabelFormat; label: string }[] = [
  { value: "full", label: "Full" },
  { value: "abbreviated", label: "Abbreviated packages" },
  { value: "last-segment", label: "Last segment" },
];

export const LABEL_FORMAT_STORAGE_KEY = "dsm.labelFormat";

export function formatLabel(text: string, format: LabelFormat): string {
  if (!text) return text;
  switch (format) {
    case "full":
      return text;
    case "abbreviated": {
      const segments = text.split(".");
      if (segments.length <= 1) return text;
      const abbreviated = segments
        .slice(0, -1)
        .map((seg) => seg.charAt(0) || seg);
      return [...abbreviated, segments[segments.length - 1]].join(".");
    }
    case "last-segment":
      return text.split(".").at(-1) ?? text;
  }
}
