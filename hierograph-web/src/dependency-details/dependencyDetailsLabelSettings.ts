import type { NodeLabelFormat } from "@/graph/nodeLabel";

export const LABEL_FORMAT_OPTIONS: { value: NodeLabelFormat; label: string }[] =
  [
    { value: "full", label: "Full" },
    { value: "abbreviated", label: "Abbreviated qualifier" },
    { value: "last-segment", label: "Own name" },
  ];

export const LABEL_FORMAT_STORAGE_KEY = "dependencyDetails.labelFormat";

export const AUTO_EXPAND_STORAGE_KEY =
  "dependencyDetails.autoExpandSingleChildren";

export function normalizeLabelFormat(value: string): NodeLabelFormat {
  return LABEL_FORMAT_OPTIONS.some((o) => o.value === value)
    ? (value as NodeLabelFormat)
    : "full";
}
