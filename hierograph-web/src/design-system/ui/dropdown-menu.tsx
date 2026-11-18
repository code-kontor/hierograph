import { CheckIcon, MoreVertical } from "lucide-react";
import { DropdownMenu as DropdownMenuPrimitive } from "radix-ui";
import * as React from "react";

import { cn } from "@/design-system/cn";

// Ghost ⋮-trigger class reused by TreeSettingsMenu
export const ghostIconTriggerClassName =
  "hover:bg-state-hover focus-visible:ring-ring flex size-6 items-center justify-center rounded-[4px] text-[var(--hg-fg-subtle)] outline-none focus-visible:ring-2 disabled:cursor-not-allowed disabled:opacity-50";

function DropdownMenu({
  ...props
}: React.ComponentProps<typeof DropdownMenuPrimitive.Root>) {
  return <DropdownMenuPrimitive.Root {...props} />;
}

function DropdownMenuTrigger({
  ...props
}: React.ComponentProps<typeof DropdownMenuPrimitive.Trigger>) {
  return <DropdownMenuPrimitive.Trigger {...props} />;
}

type DropdownMenuGhostTriggerProps = React.ComponentProps<
  typeof DropdownMenuPrimitive.Trigger
>;

function DropdownMenuGhostTrigger({
  className,
  ...props
}: DropdownMenuGhostTriggerProps) {
  return (
    <DropdownMenuPrimitive.Trigger
      className={cn(ghostIconTriggerClassName, className)}
      {...props}
    >
      <MoreVertical className="size-4" />
    </DropdownMenuPrimitive.Trigger>
  );
}

type DropdownMenuContentProps = React.ComponentProps<
  typeof DropdownMenuPrimitive.Content
>;

function DropdownMenuContent({
  className,
  sideOffset = 4,
  align = "end",
  ...props
}: DropdownMenuContentProps) {
  return (
    <DropdownMenuPrimitive.Portal>
      <DropdownMenuPrimitive.Content
        sideOffset={sideOffset}
        align={align}
        className={cn(
          "bg-popover border-border-strong z-50 min-w-[214px] rounded-[7px] border p-1 shadow-[var(--hg-shadow)]",
          "data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=open]:zoom-in-95",
          "data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=closed]:zoom-out-95",
          className,
        )}
        {...props}
      />
    </DropdownMenuPrimitive.Portal>
  );
}

type DropdownMenuCheckboxItemProps = React.ComponentProps<
  typeof DropdownMenuPrimitive.CheckboxItem
>;

// Note: use onSelect={(e) => e.preventDefault()} at the call site to keep the
// menu open after toggling a checkbox item (Radix closes by default on select).
function DropdownMenuCheckboxItem({
  className,
  children,
  checked,
  ...props
}: DropdownMenuCheckboxItemProps) {
  return (
    <DropdownMenuPrimitive.CheckboxItem
      className={cn(
        "text-fg flex h-[30px] cursor-pointer items-center gap-[9px] rounded-[5px] px-2 text-[12.5px] outline-none select-none",
        "focus:bg-state-hover data-[highlighted]:bg-state-hover",
        "data-[disabled]:pointer-events-none data-[disabled]:opacity-50",
        className,
      )}
      checked={checked}
      {...props}
    >
      <span className="flex w-[15px] shrink-0 items-center justify-center">
        <DropdownMenuPrimitive.ItemIndicator>
          <CheckIcon className="size-[14px] text-[var(--hg-accent)]" />
        </DropdownMenuPrimitive.ItemIndicator>
      </span>
      {children}
    </DropdownMenuPrimitive.CheckboxItem>
  );
}

type DropdownMenuRadioGroupProps = React.ComponentProps<
  typeof DropdownMenuPrimitive.RadioGroup
>;

function DropdownMenuRadioGroup({ ...props }: DropdownMenuRadioGroupProps) {
  return <DropdownMenuPrimitive.RadioGroup {...props} />;
}

type DropdownMenuRadioItemProps = React.ComponentProps<
  typeof DropdownMenuPrimitive.RadioItem
>;

// Note: use onSelect={(e) => e.preventDefault()} at the call site to keep the
// menu open after selecting a radio item (Radix closes by default on select).
function DropdownMenuRadioItem({
  className,
  children,
  ...props
}: DropdownMenuRadioItemProps) {
  return (
    <DropdownMenuPrimitive.RadioItem
      className={cn(
        "text-fg flex h-[30px] cursor-pointer items-center gap-[9px] rounded-[5px] px-2 text-[12.5px] outline-none select-none",
        "focus:bg-state-hover data-[highlighted]:bg-state-hover",
        "data-[disabled]:pointer-events-none data-[disabled]:opacity-50",
        className,
      )}
      {...props}
    >
      <span className="flex w-[15px] shrink-0 items-center justify-center">
        <DropdownMenuPrimitive.ItemIndicator>
          <CheckIcon className="size-[14px] text-[var(--hg-accent)]" />
        </DropdownMenuPrimitive.ItemIndicator>
      </span>
      {children}
    </DropdownMenuPrimitive.RadioItem>
  );
}

type DropdownMenuLabelProps = React.ComponentProps<
  typeof DropdownMenuPrimitive.Label
>;

function DropdownMenuLabel({ className, ...props }: DropdownMenuLabelProps) {
  return (
    <DropdownMenuPrimitive.Label
      className={cn(
        "text-fg-subtle px-2 pt-[5px] pb-[3px] font-mono text-[11px] tracking-[0.06em] uppercase",
        className,
      )}
      {...props}
    />
  );
}

type DropdownMenuSeparatorProps = React.ComponentProps<
  typeof DropdownMenuPrimitive.Separator
>;

function DropdownMenuSeparator({
  className,
  ...props
}: DropdownMenuSeparatorProps) {
  return (
    <DropdownMenuPrimitive.Separator
      className={cn("bg-border mx-[6px] my-1 h-px", className)}
      {...props}
    />
  );
}

export {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuGhostTrigger,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
};
