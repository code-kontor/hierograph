import type { ReactNode } from "react";

import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";

type OneOneSplitLayoutProps = {
  top: ReactNode;
  bottom: ReactNode;
};

export function OneOneSplitLayout({ top, bottom }: OneOneSplitLayoutProps) {
  return (
    <ResizablePanelGroup
      direction="vertical"
      autoSaveId="xref-1-1"
      className="h-full"
    >
      <ResizablePanel defaultSize={50} minSize={15}>
        {top}
      </ResizablePanel>
      <ResizableHandle withHandle />
      <ResizablePanel defaultSize={50} minSize={15}>
        {bottom}
      </ResizablePanel>
    </ResizablePanelGroup>
  );
}
