import { createContext, type ReactNode, useContext, useState } from "react";

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

// Visibility and active tab of the floating dev panel. Global (mounted above
// the router outlet) so it survives navigation and can be reopened from the
// top-level navbar; the panel itself is remounted per route. This state is
// dev-panel-owned and independent of any screen's selection.
export function DevPanelProvider({ children }: DevPanelProviderProps) {
  const [open, setOpen] = useState(true);
  const [tab, setTab] = useState<DevPanelTab>("details");
  return (
    <DevPanelContext value={{ open, setOpen, tab, setTab }}>
      {children}
    </DevPanelContext>
  );
}

export function useDevPanel(): DevPanelContextValue {
  const ctx = useContext(DevPanelContext);
  if (!ctx) {
    throw new Error("useDevPanel must be used within a DevPanelProvider");
  }
  return ctx;
}
