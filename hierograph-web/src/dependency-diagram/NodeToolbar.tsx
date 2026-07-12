import { Check, Copy, Minus, Plus, Search } from "lucide-react";
import { useState } from "react";
import { createPortal } from "react-dom";

import { cn } from "@/design-system/cn";

type CopyFqnButtonProps = {
  fqn: string;
};

// Copies the node's fully-qualified name; briefly flips to a checkmark on
// success. Local re-implementation of dev-panel/CopyButton's copy+flip logic
// (that vertical is not importable from here — see eslint boundaries).
function CopyFqnButton({ fqn }: CopyFqnButtonProps) {
  const [copied, setCopied] = useState(false);

  function handleCopy(e: React.MouseEvent) {
    e.stopPropagation();
    navigator.clipboard.writeText(fqn).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    });
  }

  return (
    <button
      type="button"
      onClick={handleCopy}
      title="Copy fully-qualified name"
      aria-label="Copy fully-qualified name"
      className="text-fg-subtle hover:text-fg flex h-6 w-6 shrink-0 items-center justify-center rounded"
    >
      {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
    </button>
  );
}

type NodeToolbarProps = {
  left: number;
  top: number;
  nodeId: string;
  fqn: string;
  isExpanded: boolean;
  onToggleExpand: (id: string) => void;
  onDrill: (id: string, label: string) => void;
  onPointerEnter: () => void;
  onPointerLeave: () => void;
};

// Floating per-box action toolbar (expand/collapse, drill, copy fqn), shown
// on hover next to the box it belongs to. Positioned like NodeInfoTooltip
// (DOM portal, fixed client coordinates) so it renders above the canvas and
// its clicks never reach the canvas's own pointer handlers.
export function NodeToolbar({
  left,
  top,
  nodeId,
  fqn,
  isExpanded,
  onToggleExpand,
  onDrill,
  onPointerEnter,
  onPointerLeave,
}: NodeToolbarProps) {
  return createPortal(
    <div
      className={cn(
        "border-border-strong bg-popover pointer-events-auto z-50 flex gap-0.5 rounded-lg border p-0.5 shadow-[var(--hg-shadow)]",
      )}
      style={{ position: "fixed", left, top }}
      onPointerEnter={onPointerEnter}
      onPointerLeave={onPointerLeave}
    >
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          onToggleExpand(nodeId);
        }}
        title={isExpanded ? "Collapse" : "Expand"}
        aria-label={isExpanded ? "Collapse" : "Expand"}
        className="text-fg-subtle hover:text-fg flex h-6 w-6 shrink-0 items-center justify-center rounded"
      >
        {isExpanded ? (
          <Minus className="size-3.5" />
        ) : (
          <Plus className="size-3.5" />
        )}
      </button>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          onDrill(nodeId, fqn);
        }}
        title="Drill into node"
        aria-label="Drill into node"
        className="text-fg-subtle hover:text-fg flex h-6 w-6 shrink-0 items-center justify-center rounded"
      >
        <Search className="size-3.5" />
      </button>
      <CopyFqnButton fqn={fqn} />
    </div>,
    document.body,
  );
}
