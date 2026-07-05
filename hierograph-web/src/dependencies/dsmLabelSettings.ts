import type { NodeLabelFormat } from "@/graph/nodeLabel";

export const LABEL_FORMAT_OPTIONS: { value: NodeLabelFormat; label: string }[] =
  [
    { value: "full", label: "Full" },
    { value: "abbreviated", label: "Abbreviated packages" },
    { value: "last-segment", label: "Last segment" },
  ];

export const LABEL_FORMAT_STORAGE_KEY = "dsm.labelFormat";
