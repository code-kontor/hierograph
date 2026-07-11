import { createContext, type ReactNode, useContext, useState } from "react";

import { useFocusState } from "./useFocusState";

export type CellSelection = {
  sourceNodeId: string;
  targetNodeId: string;
};

export type SelectionContextValue = {
  selectedIds: string[];
  setSelectedIds: (ids: string[]) => void;
  focusedId: string | null;
  setFocusedId: (id: string | null) => void;
  focusedName: string | null;
  setFocusedName: (name: string | null) => void;
  cellSelection: CellSelection | null;
  setCellSelection: (sel: CellSelection | null) => void;
};

// Exported so a route-specific provider (e.g. the DSM's URL-backed
// `DsmSelectionProvider`) can supply the same context value under the same
// `useSelection()` API without re-declaring the context.
export const SelectionContext = createContext<SelectionContextValue | null>(
  null,
);

type SelectionProviderProps = {
  children: ReactNode;
};

export function SelectionProvider({ children }: SelectionProviderProps) {
  const [selectedIds, setSelectedIdsState] = useState<string[]>([]);
  const [cellSelection, setCellSelection] = useState<CellSelection | null>(
    null,
  );

  const setSelectedIds = (ids: string[]) => {
    setSelectedIdsState(ids);
    setCellSelection(null);
  };

  const focus = useFocusState();

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

export function useSelection(): SelectionContextValue {
  const ctx = useContext(SelectionContext);
  if (!ctx) {
    throw new Error("useSelection must be used within a SelectionProvider");
  }
  return ctx;
}
