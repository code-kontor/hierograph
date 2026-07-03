import type { ReactNode } from "react";

import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";

type TwoOneSplitLayoutProps = {
  topLeft: ReactNode;
  topRight: ReactNode;
  bottom: ReactNode;
};

export function TwoOneSplitLayout({
  topLeft,
  topRight,
  bottom,
}: TwoOneSplitLayoutProps) {
  return (
    <ResizablePanelGroup
      direction="vertical"
      autoSaveId="dependencies-2-1"
      className="h-full"
    >
      <ResizablePanel defaultSize={55} minSize={20}>
        <ResizablePanelGroup
          direction="horizontal"
          autoSaveId="dependencies-2-1-top"
        >
          <ResizablePanel defaultSize={50} minSize={15}>
            {topLeft}
          </ResizablePanel>
          <ResizableHandle withHandle />
          <ResizablePanel defaultSize={50} minSize={15}>
            {topRight}
          </ResizablePanel>
        </ResizablePanelGroup>
      </ResizablePanel>
      <ResizableHandle withHandle />
      <ResizablePanel defaultSize={45} minSize={15}>
        {bottom}
      </ResizablePanel>
    </ResizablePanelGroup>
  );
}
