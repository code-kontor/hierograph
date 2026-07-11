import { createContext, type ReactNode, useContext, useState } from "react";

type FocusBridgeValue = {
  focusedId: string | null;
  focusedName: string | null;
  setFocus: (focus: {
    focusedId: string | null;
    focusedName: string | null;
  }) => void;
};

const FocusBridgeContext = createContext<FocusBridgeValue | null>(null);

type FocusBridgeProviderProps = {
  children: ReactNode;
};

// Carries the currently focused node across screen boundaries: screens write
// via SelectionProvider's mirror effect, the globally-rendered DevPanel reads
// via useFocusBridge — decoupling the panel's focus read from any screen's
// own (per-screen) SelectionProvider instance.
export function FocusBridgeProvider({ children }: FocusBridgeProviderProps) {
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const [focusedName, setFocusedName] = useState<string | null>(null);

  const setFocus = (focus: {
    focusedId: string | null;
    focusedName: string | null;
  }) => {
    setFocusedId(focus.focusedId);
    setFocusedName(focus.focusedName);
  };

  return (
    <FocusBridgeContext value={{ focusedId, focusedName, setFocus }}>
      {children}
    </FocusBridgeContext>
  );
}

export function useFocusBridge(): FocusBridgeValue {
  const ctx = useContext(FocusBridgeContext);
  if (!ctx) {
    throw new Error("useFocusBridge must be used within a FocusBridgeProvider");
  }
  return ctx;
}

export function useOptionalFocusBridge(): FocusBridgeValue | null {
  return useContext(FocusBridgeContext);
}
