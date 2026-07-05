import { Maximize2 } from "lucide-react";
import { twMerge } from "tailwind-merge";

import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";

import {
  CELL_SIZE_MAX,
  CELL_SIZE_MIN,
  CELL_SIZE_STEP,
} from "./dsmLabelSettings";

type DsmZoomControlsProps = {
  fitToWindow: boolean;
  onFitToWindowChange: (value: boolean) => void;
  cellSize: number;
  onCellSizeChange: (value: number) => void;
};

export function DsmZoomControls({
  fitToWindow,
  onFitToWindowChange,
  cellSize,
  onCellSizeChange,
}: DsmZoomControlsProps) {
  return (
    <div className="flex items-center gap-1">
      <input
        type="range"
        min={CELL_SIZE_MIN}
        max={CELL_SIZE_MAX}
        step={CELL_SIZE_STEP}
        value={cellSize}
        disabled={fitToWindow}
        aria-label="Cell size"
        title="Cell size"
        onChange={(e) => onCellSizeChange(Number(e.target.value))}
        className="h-6 w-20 cursor-pointer disabled:cursor-not-allowed disabled:opacity-50"
      />
      <button
        type="button"
        aria-pressed={fitToWindow}
        title="Fit matrix to window"
        onClick={() => onFitToWindowChange(!fitToWindow)}
        className={twMerge(
          ghostIconTriggerClassName,
          fitToWindow && "bg-state-hover text-fg",
        )}
      >
        <Maximize2 className="size-4" />
      </button>
    </div>
  );
}
