import { useEffect } from "react";

// Turns the browser's own context menu off for the whole page: a right click
// opens the app's menu where there is one, and nothing elsewhere. Text fields
// keep theirs, for cut, copy and paste.
export function useNoNativeContextMenu() {
  useEffect(() => {
    const onContextMenu = (event: MouseEvent) => {
      if (isEditable(event.target)) return;
      event.preventDefault();
    };
    document.addEventListener("contextmenu", onContextMenu);
    return () => document.removeEventListener("contextmenu", onContextMenu);
  }, []);
}

function isEditable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return (
    target.isContentEditable ||
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement
  );
}
