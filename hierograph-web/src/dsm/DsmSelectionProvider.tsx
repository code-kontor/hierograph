import { useNavigate, useSearch } from "@tanstack/react-router";
import type { ReactNode } from "react";

import {
  type CellSelection,
  SelectionContext,
} from "@/selection/SelectionContext";
import { useFocusState } from "@/selection/useFocusState";

type DsmSelectionProviderProps = {
  children: ReactNode;
};

// URL-read-through adapter for `/dsm`: exposes the exact `useSelection()` shape,
// but `selectedIds`/`cellSelection` are derived straight from the search params
// and every write goes through `navigate` — the URL is the source of truth. No
// mirror `useState`, no URL→state→navigate effect (that would loop). Focus stays
// transient via `useFocusState` (mirrored into the FocusBridge, #0096 intact).
export function DsmSelectionProvider({ children }: DsmSelectionProviderProps) {
  const search = useSearch({ from: "/dsm" });
  const navigate = useNavigate({ from: "/dsm" });
  const focus = useFocusState();

  const selectedIds = search.subject_ids ?? [];
  const cellSelection: CellSelection | null =
    search.from_id !== undefined && search.to_id !== undefined
      ? { sourceNodeId: search.from_id, targetNodeId: search.to_id }
      : null;

  // Committing a tree selection pushes a history entry and drops the cell +
  // tab (mirrors the old `setSelectedIds` → `setCellSelection(null)` coupling).
  const setSelectedIds = (ids: string[]) => {
    navigate({
      search: (prev) => ({
        ...prev,
        subject_ids: ids.length > 0 ? ids : undefined,
        from_id: undefined,
        to_id: undefined,
        tab: undefined,
      }),
    });
  };

  // Selecting/clearing a matrix cell pushes a history entry; clearing the cell
  // also drops the tab (no cell → no inspector tab).
  const setCellSelection = (sel: CellSelection | null) => {
    navigate({
      search: (prev) => ({
        ...prev,
        from_id: sel?.sourceNodeId,
        to_id: sel?.targetNodeId,
        tab: sel ? prev.tab : undefined,
      }),
    });
  };

  return (
    <SelectionContext
      value={{
        selectedIds,
        setSelectedIds,
        cellSelection,
        setCellSelection,
        ...focus,
      }}
    >
      {children}
    </SelectionContext>
  );
}
