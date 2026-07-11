import { Maximize2, Minus, Plus } from "lucide-react";
import { twMerge } from "tailwind-merge";

import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";

type DependencyDiagramControlsProps = {
  onFit: () => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
};

export function DependencyDiagramControls({
  onFit,
  onZoomIn,
  onZoomOut,
}: DependencyDiagramControlsProps) {
  return (
    <div className="flex items-center gap-1">
      <button
        type="button"
        title="Zoom out"
        aria-label="Zoom out"
        onClick={onZoomOut}
        className={twMerge(ghostIconTriggerClassName)}
      >
        <Minus className="size-4" />
      </button>
      <button
        type="button"
        title="Zoom in"
        aria-label="Zoom in"
        onClick={onZoomIn}
        className={twMerge(ghostIconTriggerClassName)}
      >
        <Plus className="size-4" />
      </button>
      <button
        type="button"
        title="Fit to view"
        aria-label="Fit to view"
        onClick={onFit}
        className={twMerge(ghostIconTriggerClassName)}
      >
        <Maximize2 className="size-4" />
      </button>
    </div>
  );
}
