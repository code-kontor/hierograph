import type { ReactNode } from "react";

import { cn } from "@/design-system/cn";

type PaneProps = {
  title: ReactNode;
  toolbar?: ReactNode;
  titleBar?: ReactNode;
  subHeader?: ReactNode;
  children: ReactNode;
  bodyClassName?: string;
  // A title bar without its tint, just the hairline below it: for panes
  // beside one another that would otherwise stack bars on little content.
  plainHeader?: boolean;
};

export function Pane({
  title,
  toolbar,
  titleBar,
  subHeader,
  children,
  bodyClassName,
  plainHeader,
}: PaneProps) {
  const header = plainHeader ? "bg-transparent" : "bg-panel-header";
  return (
    <div className="bg-panel border-border-strong flex h-full flex-col overflow-hidden rounded-[8px] border shadow-[var(--hg-shadow)]">
      {titleBar ? (
        <div
          className={cn(
            "border-border flex h-[34px] shrink-0 items-stretch border-b",
            header,
          )}
        >
          {titleBar}
        </div>
      ) : (
        <div
          className={cn(
            "border-border flex h-[34px] shrink-0 items-center justify-between gap-2 border-b px-3",
            header,
          )}
        >
          <div className="text-muted-foreground font-mono text-[12px] tracking-[0.06em] uppercase">
            {title}
          </div>
          {toolbar && (
            <div className="flex min-w-0 items-center gap-1">{toolbar}</div>
          )}
        </div>
      )}
      {subHeader && (
        <div className="border-border shrink-0 border-b px-[14px] py-[9px]">
          {subHeader}
        </div>
      )}
      <div className={cn("min-h-0 flex-1 overflow-auto p-3", bodyClassName)}>
        {children}
      </div>
    </div>
  );
}
