import type { ReactNode } from "react";

type PaneProps = {
  title: string;
  children: ReactNode;
};

export function Pane({ title, children }: PaneProps) {
  return (
    <div className="flex h-full flex-col">
      <div className="shrink-0 border-b px-3 py-2 text-sm font-medium">
        {title}
      </div>
      <div className="min-h-0 flex-1 overflow-auto p-3">{children}</div>
    </div>
  );
}
