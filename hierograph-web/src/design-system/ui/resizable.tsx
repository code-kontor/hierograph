import { Panel, PanelGroup, PanelResizeHandle } from "react-resizable-panels";

import { cn } from "@/design-system/cn";

type ResizablePanelGroupProps = React.ComponentProps<typeof PanelGroup>;

function ResizablePanelGroup({
  className,
  ...props
}: ResizablePanelGroupProps) {
  return (
    <PanelGroup
      className={cn(
        "flex h-full w-full data-[panel-group-direction=vertical]:flex-col",
        className,
      )}
      {...props}
    />
  );
}

const ResizablePanel = Panel;

type ResizableHandleProps = React.ComponentProps<typeof PanelResizeHandle> & {
  withHandle?: boolean;
};

function ResizableHandle({
  withHandle,
  className,
  ...props
}: ResizableHandleProps) {
  return (
    <PanelResizeHandle
      className={cn(
        "group/handle hover:bg-state-hover focus-visible:ring-ring relative flex w-2.5 cursor-col-resize items-center justify-center bg-transparent transition-colors focus-visible:ring-1 focus-visible:outline-none data-[panel-group-direction=vertical]:h-2.5 data-[panel-group-direction=vertical]:w-full data-[panel-group-direction=vertical]:cursor-row-resize",
        className,
      )}
      {...props}
    >
      {withHandle && (
        <div className="bg-border-strong h-[30px] w-0.5 rounded-full group-data-[panel-group-direction=vertical]/handle:h-0.5 group-data-[panel-group-direction=vertical]/handle:w-[30px]" />
      )}
    </PanelResizeHandle>
  );
}

export { ResizableHandle, ResizablePanel, ResizablePanelGroup };
