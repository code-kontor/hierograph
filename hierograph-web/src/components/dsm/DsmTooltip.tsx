type DsmTooltipProps = {
  text: string;
  x: number;
  y: number;
};

export function DsmTooltip({ text, x, y }: DsmTooltipProps) {
  return (
    <div
      className="bg-popover text-popover-foreground pointer-events-none absolute z-10 rounded border px-2 py-1 text-xs whitespace-nowrap shadow"
      style={{ left: x + 12, top: y + 12 }}
    >
      {text}
    </div>
  );
}
