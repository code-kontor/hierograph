import {
  createContext,
  type ReactNode,
  useContext,
  useEffect,
  useState,
} from "react";

import { useOptionalFocusBridge } from "./FocusBridge";

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
  const [selectedIds, setSelectedIdsState] = useState<string[]>([]);
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const [focusedName, setFocusedName] = useState<string | null>(null);
  const [cellSelection, setCellSelection] = useState<CellSelection | null>(
    null,
  );

  const setSelectedIds = (ids: string[]) => {
    setSelectedIdsState(ids);
    setCellSelection(null);
  };

  const bridge = useOptionalFocusBridge();
  useEffect(() => {
    if (!bridge) return;
    bridge.setFocus({ focusedId, focusedName });
    return () => bridge.setFocus({ focusedId: null, focusedName: null });
  }, [bridge, focusedId, focusedName]);

  return (
    <SelectionContext
      value={{
        selectedIds,
        setSelectedIds,
        focusedId,
        setFocusedId,
        focusedName,
        setFocusedName,
        cellSelection,
        setCellSelection,
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
