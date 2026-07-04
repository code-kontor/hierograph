import type { ReactNode } from "react";

type PaneProps = {
  title: string;
  toolbar?: ReactNode;
  children: ReactNode;
};

export function Pane({ title, toolbar, children }: PaneProps) {
  return (
    <div className="bg-panel border-border-strong flex h-full flex-col overflow-hidden rounded-[8px] border shadow-[var(--hg-shadow)]">
      <div className="bg-panel-header border-border flex h-[34px] shrink-0 items-center justify-between gap-2 border-b px-3">
        <div className="text-muted-foreground font-mono text-[11px] tracking-[0.06em] uppercase">
          {title}
        </div>
        {toolbar && <div className="flex items-center gap-1">{toolbar}</div>}
      </div>
      <div className="min-h-0 flex-1 overflow-auto p-3">{children}</div>
    </div>
  );
}
