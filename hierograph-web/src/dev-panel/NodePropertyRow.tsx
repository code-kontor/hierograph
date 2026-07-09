import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/design-system/ui/tooltip";

type NodePropertyRowProps = {
  propertyKey: string;
  value: string | null;
};

export function NodePropertyRow({ propertyKey, value }: NodePropertyRowProps) {
  return (
    <div className="border-border grid grid-cols-[132px_minmax(0,1fr)] border-b last:border-b-0">
      <div className="border-border bg-panel-header text-fg-muted border-r px-3 py-1.5 font-mono text-xs">
        {propertyKey}
      </div>
      {value ? (
        <Tooltip>
          <TooltipTrigger asChild>
            <div className="text-fg min-w-0 cursor-default overflow-hidden px-3 py-1.5 font-mono text-xs text-ellipsis whitespace-nowrap">
              {value}
            </div>
          </TooltipTrigger>
          <TooltipContent className="max-w-[min(80vw,520px)] font-mono break-all">
            {value}
          </TooltipContent>
        </Tooltip>
      ) : (
        <div className="text-fg min-w-0 overflow-hidden px-3 py-1.5 font-mono text-xs text-ellipsis whitespace-nowrap">
          —
        </div>
      )}
    </div>
  );
}
