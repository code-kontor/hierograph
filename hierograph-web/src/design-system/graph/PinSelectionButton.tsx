import { Pin } from "lucide-react";
import { twMerge } from "tailwind-merge";

import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";

type PinSelectionButtonProps = {
  pressed: boolean;
  // Without a selection, there is nothing to pin.
  disabled: boolean;
  onPress: () => void;
};

// Pins a graph's selection, so clicks in the graph don't change it; only a
// menu does, and clearing it unpins it. Pinned, it is in the warning orange:
// clicks are held, a mode that must not go unnoticed (docs/design-system.md,
// "Interaction").
export function PinSelectionButton({
  pressed,
  disabled,
  onPress,
}: PinSelectionButtonProps) {
  return (
    <button
      type="button"
      aria-label="Pin the selection"
      aria-pressed={pressed}
      title={
        pressed
          ? "Unpin the selection"
          : "Pin the selection: clicks don't change it"
      }
      disabled={disabled}
      onClick={onPress}
      className={twMerge(
        ghostIconTriggerClassName,
        "size-5",
        pressed &&
          "bg-status-warning-bg text-status-warning hover:bg-status-warning-bg hover:text-status-warning",
      )}
    >
      <Pin className="size-3.5" />
    </button>
  );
}
