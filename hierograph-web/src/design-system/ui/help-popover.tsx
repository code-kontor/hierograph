import { HelpCircle } from "lucide-react";
import type { ReactNode } from "react";

import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/design-system/ui/popover";

type HelpPopoverButtonProps = {
  label: string;
  children: ReactNode;
};

// Non-modal help popover.
export function HelpPopoverButton({ label, children }: HelpPopoverButtonProps) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          title={label}
          aria-label={label}
          className={ghostIconTriggerClassName}
        >
          <HelpCircle className="size-4" />
        </button>
      </PopoverTrigger>
      <PopoverContent aria-label={label}>{children}</PopoverContent>
    </Popover>
  );
}
