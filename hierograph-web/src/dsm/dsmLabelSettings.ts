import type { NodeLabelFormat } from "@/graph/nodeLabel";

import { MAX_BOX_SIZE, MIN_BOX_SIZE } from "./dsmModel";

export const LABEL_FORMAT_OPTIONS: { value: NodeLabelFormat; label: string }[] =
  [
    { value: "full", label: "Full" },
    { value: "abbreviated", label: "Abbreviated qualifier" },
    { value: "last-segment", label: "Own name" },
  ];

export const LABEL_FORMAT_STORAGE_KEY = "dsm.labelFormat";

export const SHOW_DIAGONAL_STORAGE_KEY = "dsm.showDiagonal";
export const SHOW_DIAGONAL_DEFAULT = true;

export const FIT_TO_WINDOW_STORAGE_KEY = "dsm.fitToWindow";
export const FIT_TO_WINDOW_DEFAULT = false;
export const CELL_SIZE_STORAGE_KEY = "dsm.cellSize";
export const CELL_SIZE_DEFAULT = 36;
export const CELL_SIZE_STEP = 4;
export const CELL_SIZE_MIN = MIN_BOX_SIZE;
export const CELL_SIZE_MAX = MAX_BOX_SIZE;
