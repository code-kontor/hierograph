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
  return <SelectionContext value={value}>{children}</SelectionContext>;
}

export function useSelection(): SelectionContextValue {
  const ctx = useContext(SelectionContext);
  if (!ctx) {
    throw new Error("useSelection must be used within a SelectionProvider");
  }
  return ctx;
}

export type DevPanelTab = "details" | "queries";

type DevPanelContextValue = {
  open: boolean;
  setOpen: (open: boolean) => void;
  tab: DevPanelTab;
  setTab: (tab: DevPanelTab) => void;
};

const DevPanelContext = createContext<DevPanelContextValue | null>(null);

type DevPanelProviderProps = {
  children: ReactNode;
};

// Visibility and active tab of the floating dev panel. Kept above the router
// outlet so it survives navigation (the panel itself is remounted per route)
// and can be reopened from the top-level navbar. Deliberately separate from
// SelectionProvider, which pages re-provide locally — this one stays global.
export function DevPanelProvider({ children }: DevPanelProviderProps) {
  const [open, setOpen] = useState(true);
  const [tab, setTab] = useState<DevPanelTab>("details");
  const value = useMemo(() => ({ open, setOpen, tab, setTab }), [open, tab]);
  return <DevPanelContext value={value}>{children}</DevPanelContext>;
}

export function useDevPanel(): DevPanelContextValue {
  const ctx = useContext(DevPanelContext);
  if (!ctx) {
    throw new Error("useDevPanel must be used within a DevPanelProvider");
  }
  return ctx;
}
