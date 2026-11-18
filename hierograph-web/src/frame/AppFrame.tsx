import type { ReactNode } from "react";

import lupeUrl from "@/assets/hierograph-lupe.svg";

type AppFrameProps = {
  children: ReactNode;
};

// The app shell every screen is rendered into. For now only the brand bar and
// the content area; navigation, graph status and the hierarchy-view parameter
// follow.
export function AppFrame({ children }: AppFrameProps) {
  return (
    <div className="flex h-svh flex-col">
      <header className="border-border-strong bg-panel flex h-[52px] shrink-0 items-center gap-7 border-b px-[18px]">
        <div className="flex items-center gap-[9px]">
          <img src={lupeUrl} alt="" className="block h-[26px] w-auto" />
          <span className="text-fg font-sans text-[18px] font-bold tracking-[-0.01em]">
            hierograph
          </span>
        </div>
      </header>
      <main className="min-h-0 flex-1 p-3">{children}</main>
    </div>
  );
}
