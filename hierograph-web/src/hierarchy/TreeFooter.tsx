import { useSelection } from "@/selection";

export function TreeFooter() {
  const { selectedIds, focusedName } = useSelection();

  return (
    <div className="bg-panel-header border-border flex shrink-0 items-center gap-[14px] border-t px-3 py-[6px] font-mono text-[11px] text-[var(--hg-fg-subtle)]">
      <span>{selectedIds.length} selected</span>
      <span className="text-[var(--hg-border-strong)]">·</span>
      <span>
        focus:{" "}
        <span className="text-[var(--hg-fg-muted)]">{focusedName ?? ""}</span>
      </span>
      <span className="flex-1" />
      <span>click = select · ⌘-click = multi · ▸ = expand</span>
    </div>
  );
}
