import { useEffect, useState } from "react";

import { useOptionalFocusBridge } from "./FocusBridge";

export type FocusState = {
  focusedId: string | null;
  setFocusedId: (id: string | null) => void;
  focusedName: string | null;
  setFocusedName: (name: string | null) => void;
};

// Transient focus state (never serialized to the URL) plus the mirror into the
// globally-mounted FocusBridge, so the DEV DevPanel reads the focused node
// regardless of which screen's selection provider is mounted. Shared by the
// state-backed `SelectionProvider` and the URL-backed `DsmSelectionProvider`
// so both keep the deliberate provider isolation (#0096) intact.
export function useFocusState(): FocusState {
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const [focusedName, setFocusedName] = useState<string | null>(null);

  const bridge = useOptionalFocusBridge();
  useEffect(() => {
    if (!bridge) return;
    bridge.setFocus({ focusedId, focusedName });
    return () => bridge.setFocus({ focusedId: null, focusedName: null });
  }, [bridge, focusedId, focusedName]);

  return { focusedId, setFocusedId, focusedName, setFocusedName };
}
