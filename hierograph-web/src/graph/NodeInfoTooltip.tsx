import { createPortal } from "react-dom";

const WIDTH = 320;
const HEIGHT = 96;

type NodeInfoTooltipProps = {
  /** Pointer position (clientX/clientY); the card clamps itself to the viewport. */
  x: number;
  y: number;
  shortName: string;
  type: string;
  fullName: string;
};

// Shared hover card for a graph node — used by the hierarchy tree rows and the
// dependency matrix side markers so both surfaces show the identical tooltip.
export function NodeInfoTooltip({
  x,
  y,
  shortName,
  type,
  fullName,
}: NodeInfoTooltipProps) {
  const left = Math.min(x, window.innerWidth - WIDTH - 8);
  const top = Math.min(y, window.innerHeight - 8 - HEIGHT);

  return createPortal(
    <div
      className="border-border-strong bg-popover pointer-events-none z-50 max-w-[320px] rounded-lg border px-[11px] py-2 shadow-[var(--hg-shadow)]"
      style={{ position: "fixed", left, top }}
    >
      <p className="text-fg font-mono text-[12.5px] font-semibold">
        {shortName}
      </p>
      <p className="text-fg-subtle font-mono text-[11px]">{type}</p>
      {/* Intentionally shows the full node text — not subject to the label-format setting (#47). */}
      <p className="text-fg-muted font-mono text-[11px] break-all">
        {fullName}
      </p>
    </div>,
    document.body,
  );
}
