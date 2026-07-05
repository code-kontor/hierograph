import type { NodeLabelFormat } from "@/graph/nodeLabel";

export const LABEL_FORMAT_OPTIONS: { value: NodeLabelFormat; label: string }[] =
  [
    { value: "full", label: "Full" },
    { value: "abbreviated", label: "Abbreviated qualifier" },
    { value: "last-segment", label: "Own name" },
  ];

export const LABEL_FORMAT_STORAGE_KEY = "dsm.labelFormat";

export const SHOW_DIAGONAL_STORAGE_KEY = "dsm.showDiagonal";
export const SHOW_DIAGONAL_DEFAULT = true;
