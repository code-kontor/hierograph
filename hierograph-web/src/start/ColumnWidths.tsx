import { type KeyboardEvent, type PointerEvent, useState } from "react";

// The narrowest a column gets, in percent of the table.
const MIN_WIDTH = 6;
// How far an arrow key moves a column border, in percent.
const KEY_STEP = 2;

// Column widths in percent of the table, remembered by the browser under
// `key` (a per-viewer convenience: without storage they start from
// `defaults` each time). `resize(index, delta)` moves the border after
// column `index` by `delta` percent, between it and the next column, so the
// table keeps its width.
export function useColumnWidths(key: string, defaults: number[]) {
  const [widths, setWidths] = useState<number[]>(() => {
    try {
      const stored = JSON.parse(localStorage.getItem(key) ?? "null");
      if (
        Array.isArray(stored) &&
        stored.length === defaults.length &&
        stored.every((width) => typeof width === "number")
      ) {
        return stored as number[];
      }
    } catch {
      // Unavailable or unreadable: the defaults.
    }
    return defaults;
  });
  const store = (next: number[]) => {
    setWidths(next);
    try {
      localStorage.setItem(key, JSON.stringify(next));
    } catch {
      // Not remembered, then.
    }
  };
  const resize = (index: number, delta: number) => {
    const left = widths[index];
    const right = widths[index + 1];
    const moved = Math.max(
      MIN_WIDTH - left,
      Math.min(right - MIN_WIDTH, delta),
    );
    const next = [...widths];
    next[index] = left + moved;
    next[index + 1] = right - moved;
    store(next);
  };
  const reset = () => store(defaults);
  return { widths, resize, reset };
}

type ColumnResizerProps = {
  // The column whose right border it moves.
  index: number;
  // What it resizes, for assistive technology.
  label: string;
  // The column's width, in percent.
  width: number;
  resize: (index: number, delta: number) => void;
  reset: () => void;
};

// The border at a header cell's right edge: drag it (or focus it and use
// ← →) to move width between the column and the next one; a double-click
// gives every column its default width. The header cell must be
// `relative`.
export function ColumnResizer({
  index,
  label,
  width,
  resize,
  reset,
}: ColumnResizerProps) {
  const start = (event: PointerEvent<HTMLDivElement>) => {
    const handle = event.currentTarget;
    const table = handle.closest("table");
    if (!table) return;
    event.preventDefault();
    handle.setPointerCapture(event.pointerId);
    const percentPerPixel = 100 / table.getBoundingClientRect().width;
    let last = event.clientX;
    const move = (moveEvent: globalThis.PointerEvent) => {
      const delta = (moveEvent.clientX - last) * percentPerPixel;
      last = moveEvent.clientX;
      resize(index, delta);
    };
    const end = () => {
      handle.removeEventListener("pointermove", move);
      handle.removeEventListener("pointerup", end);
      handle.removeEventListener("pointercancel", end);
    };
    handle.addEventListener("pointermove", move);
    handle.addEventListener("pointerup", end);
    handle.addEventListener("pointercancel", end);
  };
  const key = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "ArrowLeft") resize(index, -KEY_STEP);
    else if (event.key === "ArrowRight") resize(index, KEY_STEP);
    else return;
    event.preventDefault();
  };
  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      aria-valuenow={Math.round(width)}
      aria-valuemin={MIN_WIDTH}
      aria-valuemax={100}
      tabIndex={0}
      title="Drag to resize, double-click for the default widths"
      onPointerDown={start}
      onKeyDown={key}
      onDoubleClick={reset}
      // 12 px to grab, a hairline on hover or focus.
      className="hover:after:bg-border-strong focus-visible:after:bg-ring absolute top-0 -right-1.5 z-10 h-full w-3 cursor-col-resize outline-none after:absolute after:inset-y-1 after:left-1/2 after:w-px"
    />
  );
}
