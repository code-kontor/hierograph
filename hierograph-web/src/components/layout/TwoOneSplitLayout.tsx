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
      autoSaveId="dependencies-2-1-v2"
      className="h-full"
    >
      <ResizablePanel defaultSize={60} minSize={20}>
        <ResizablePanelGroup
          direction="horizontal"
          autoSaveId="dependencies-2-1-top-v2"
        >
          <ResizablePanel defaultSize={33} minSize={15}>
            {topLeft}
          </ResizablePanel>
          <ResizableHandle withHandle />
          <ResizablePanel defaultSize={67} minSize={15}>
            {topRight}
          </ResizablePanel>
        </ResizablePanelGroup>
      </ResizablePanel>
      <ResizableHandle withHandle />
      <ResizablePanel defaultSize={40} minSize={15}>
        {bottom}
      </ResizablePanel>
    </ResizablePanelGroup>
  );
}
