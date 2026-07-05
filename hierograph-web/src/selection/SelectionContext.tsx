import {
  createContext,
  type ReactNode,
  useContext,
  useMemo,
  useState,
} from "react";

export type CellSelection = {
  sourceNodeId: string;
  targetNodeId: string;
};

type SelectionContextValue = {
  selectedIds: string[];
  setSelectedIds: (ids: string[]) => void;
  focusedId: string | null;
  setFocusedId: (id: string | null) => void;
  focusedName: string | null;
  setFocusedName: (name: string | null) => void;
  cellSelection: CellSelection | null;
  setCellSelection: (sel: CellSelection | null) => void;
};

const SelectionContext = createContext<SelectionContextValue | null>(null);

type SelectionProviderProps = {
  children: ReactNode;
};

export function SelectionProvider({ children }: SelectionProviderProps) {
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const [focusedName, setFocusedName] = useState<string | null>(null);
  const [cellSelection, setCellSelection] = useState<CellSelection | null>(
    null,
  );
  const value = useMemo(
    () => ({
      selectedIds,
      setSelectedIds,
      focusedId,
      setFocusedId,
      focusedName,
      setFocusedName,
      cellSelection,
      setCellSelection,
    }),
    [selectedIds, focusedId, focusedName, cellSelection],
  );
  return (
    <SelectionContext.Provider value={value}>
      {children}
    </SelectionContext.Provider>
  );
}

export function useSelection(): SelectionContextValue {
  const ctx = useContext(SelectionContext);
  if (!ctx) {
    throw new Error("useSelection must be used within a SelectionProvider");
  }
  return ctx;
}
