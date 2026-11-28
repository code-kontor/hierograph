import { ChevronRight } from "lucide-react";
import { ContextMenu as ContextMenuPrimitive } from "radix-ui";
import * as React from "react";

import { cn } from "@/design-system/cn";

// The right-click menu, styled like the dropdown menu (`dropdown-menu.tsx`).

function ContextMenu({
  ...props
}: React.ComponentProps<typeof ContextMenuPrimitive.Root>) {
  return <ContextMenuPrimitive.Root {...props} />;
}

function ContextMenuTrigger({
  ...props
}: React.ComponentProps<typeof ContextMenuPrimitive.Trigger>) {
  return <ContextMenuPrimitive.Trigger {...props} />;
}

type ContextMenuContentProps = React.ComponentProps<
  typeof ContextMenuPrimitive.Content
>;

function ContextMenuContent({ className, ...props }: ContextMenuContentProps) {
  return (
    <ContextMenuPrimitive.Portal>
      <ContextMenuPrimitive.Content
        className={cn(
          "bg-popover border-border-strong z-50 min-w-[214px] rounded-[7px] border p-1 shadow-[var(--hg-shadow)]",
          "data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=open]:zoom-in-95",
          "data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=closed]:zoom-out-95",
          className,
        )}
        {...props}
      />
    </ContextMenuPrimitive.Portal>
  );
}

type ContextMenuItemProps = React.ComponentProps<
  typeof ContextMenuPrimitive.Item
>;

function ContextMenuItem({ className, ...props }: ContextMenuItemProps) {
  return (
    <ContextMenuPrimitive.Item
      className={cn(
        "text-fg flex h-[30px] cursor-pointer items-center gap-[9px] rounded-[5px] px-2 text-[12.5px] outline-none select-none",
        "focus:bg-state-hover data-[highlighted]:bg-state-hover",
        "data-[disabled]:pointer-events-none data-[disabled]:opacity-50",
        "[&_svg]:text-fg-subtle [&_svg]:size-[14px] [&_svg]:shrink-0",
        className,
      )}
      {...props}
    />
  );
}

function ContextMenuSub({
  ...props
}: React.ComponentProps<typeof ContextMenuPrimitive.Sub>) {
  return <ContextMenuPrimitive.Sub {...props} />;
}

type ContextMenuSubTriggerProps = React.ComponentProps<
  typeof ContextMenuPrimitive.SubTrigger
>;

// An item that opens a submenu, a chevron at its right end; styled like an
// item, and marked while its submenu is open.
function ContextMenuSubTrigger({
  className,
  children,
  ...props
}: ContextMenuSubTriggerProps) {
  return (
    <ContextMenuPrimitive.SubTrigger
      className={cn(
        "text-fg flex h-[30px] cursor-pointer items-center gap-[9px] rounded-[5px] px-2 text-[12.5px] outline-none select-none",
        "focus:bg-state-hover data-[highlighted]:bg-state-hover data-[state=open]:bg-state-hover",
        "data-[disabled]:pointer-events-none data-[disabled]:opacity-50",
        "[&_svg]:text-fg-subtle [&_svg]:size-[14px] [&_svg]:shrink-0",
        className,
      )}
      {...props}
    >
      {children}
      <ChevronRight className="ml-auto" />
    </ContextMenuPrimitive.SubTrigger>
  );
}

type ContextMenuSubContentProps = React.ComponentProps<
  typeof ContextMenuPrimitive.SubContent
>;

function ContextMenuSubContent({
  className,
  ...props
}: ContextMenuSubContentProps) {
  return (
    <ContextMenuPrimitive.Portal>
      <ContextMenuPrimitive.SubContent
        className={cn(
          "bg-popover border-border-strong z-50 min-w-[214px] rounded-[7px] border p-1 shadow-[var(--hg-shadow)]",
          "data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=open]:zoom-in-95",
          "data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=closed]:zoom-out-95",
          className,
        )}
        {...props}
      />
    </ContextMenuPrimitive.Portal>
  );
}

type ContextMenuSeparatorProps = React.ComponentProps<
  typeof ContextMenuPrimitive.Separator
>;

function ContextMenuSeparator({
  className,
  ...props
}: ContextMenuSeparatorProps) {
  return (
    <ContextMenuPrimitive.Separator
      className={cn("bg-border mx-[6px] my-1 h-px", className)}
      {...props}
    />
  );
}

type ContextMenuShortcutProps = React.ComponentProps<"span">;

// The gesture or key that does what the item does, at the item's right end,
// so the menu teaches it. Left out of the item's accessible name.
function ContextMenuShortcut({
  className,
  ...props
}: ContextMenuShortcutProps) {
  return (
    <span
      data-slot="context-menu-shortcut"
      aria-hidden
      className={cn(
        "text-fg-subtle ml-auto pl-4 font-mono text-[12px] tracking-tight",
        className,
      )}
      {...props}
    />
  );
}

export {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuShortcut,
  ContextMenuSub,
  ContextMenuSubContent,
  ContextMenuSubTrigger,
  ContextMenuTrigger,
};
