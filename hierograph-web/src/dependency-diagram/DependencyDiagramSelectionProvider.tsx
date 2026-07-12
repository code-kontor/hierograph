import { useNavigate, useSearch } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { useState } from "react";

import {
  type CellSelection,
  SelectionContext,
} from "@/selection/SelectionContext";
import { useFocusState } from "@/selection/useFocusState";

type DependencyDiagramSelectionProviderProps = {
  children: ReactNode;
};

// URL-read-through adapter for `/dependency-diagram`, mirroring
// `DsmSelectionProvider`: `selectedIds` is derived straight from the search
// params and every write goes through `navigate` — the URL is the source of
// truth for the tree selection. `cellSelection` (edge click, #0132) stays
// transient in local state, like `focus` — it is not part of the URL shape
// (see plan E3).
export function DependencyDiagramSelectionProvider({
  children,
}: DependencyDiagramSelectionProviderProps) {
  const search = useSearch({ from: "/dependency-diagram" });
  const navigate = useNavigate({ from: "/dependency-diagram" });
  const focus = useFocusState();
  const [cellSelection, setCellSelection] = useState<CellSelection | null>(
    null,
  );

  const selectedIds = search.subject_ids ?? [];

  // Committing a tree selection pushes a history entry and drops the drill
  // path + expand state (level 0..n depend on the root scope that just
  // changed) and clears the transient cell selection.
  const setSelectedIds = async (ids: string[]) => {
    setCellSelection(null);
    await navigate({
      search: (prev) => ({
        ...prev,
        subject_ids: ids.length > 0 ? ids : undefined,
        drill_ids: undefined,
        expanded_ids: undefined,
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
