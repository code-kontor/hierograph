import { HelpCircle } from "lucide-react";
import type { ReactNode } from "react";

import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/design-system/ui/popover";

type TabHelpButtonProps = {
  label: string;
  children: ReactNode;
};

// Non-modal help popover shown above the active tab.
export function TabHelpButton({ label, children }: TabHelpButtonProps) {
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

export const USAGES_HELP_LABEL = "About the Usages tab";
export const TRACE_HELP_LABEL = "About the Paths tab";

export function UsagesHelpContent() {
  return (
    <p>
      Usages lists the concrete references between the two selected types — each
      row is one atomic edge (calls, extends, implements, references) shown as{" "}
      <em>From type · usage · To type</em>. Use it to enumerate or search every
      reference behind a matrix cell.
    </p>
  );
}

export function TraceHelpContent() {
  return (
    <p>
      Paths lets you click a type on one side to make it the driver
      (highlighted); its counterparts on the other side are marked and revealed.
      There is exactly one driver at a time — selecting on one side clears the
      other. Toggle <em>In context ↔ hits only</em> to switch between the full
      surrounding tree and just the matched types.
    </p>
  );
}
