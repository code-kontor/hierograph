import { X } from "lucide-react";
import { twMerge } from "tailwind-merge";

import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";

type SelectionStatusProps = {
  // What is selected, by name.
  label: string;
  onClear: () => void;
};

// What a graph has selected, and the way to clear it: the chip in the corner
// of a graph's pane (docs/design-system.md, "Interaction").
export function SelectionStatus({ label, onClear }: SelectionStatusProps) {
  return (
    <span
      role="status"
      className="border-border text-fg-muted flex h-5 min-w-0 items-center gap-1 rounded-[4px] border pr-0.5 pl-1.5 font-mono text-[11px]"
    >
      <span className="shrink-0">Selected:</span>
      <span className="text-fg max-w-56 truncate" title={label}>
        {label}
      </span>
      <span className="text-fg-subtle shrink-0">· Esc to clear</span>
      <button
        type="button"
        aria-label="Clear selection"
        title="Clear selection (Esc)"
        onClick={onClear}
        className={twMerge(ghostIconTriggerClassName, "size-4")}
      >
        <X className="size-3" />
      </button>
    </span>
  );
}
