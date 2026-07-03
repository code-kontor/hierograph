import {
  createContext,
  type ReactNode,
  useContext,
  useMemo,
  useState,
} from "react";

type SelectionContextValue = {
  selectedIds: string[];
  setSelectedIds: (ids: string[]) => void;
};

const SelectionContext = createContext<SelectionContextValue | null>(null);

type SelectionProviderProps = {
  children: ReactNode;
};

export function SelectionProvider({ children }: SelectionProviderProps) {
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const value = useMemo(() => ({ selectedIds, setSelectedIds }), [selectedIds]);
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
