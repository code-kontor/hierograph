import { useEffect, useRef, useState } from "react";

export function useCopyToClipboard(resetDelayMs = 1500) {
  const [copied, setCopied] = useState(false);
  const resetTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );

  useEffect(() => {
    return () => clearTimeout(resetTimerRef.current);
  }, []);

  // Returns void on purpose: success/failure is fully handled here (icon flip
  // resp. console.warn), so callers can stay synchronous event handlers.
  function copy(text: string) {
    navigator.clipboard.writeText(text).then(
      () => {
        setCopied(true);
        clearTimeout(resetTimerRef.current);
        resetTimerRef.current = setTimeout(
          () => setCopied(false),
          resetDelayMs,
        );
      },
      (err: unknown) => {
        console.warn("Failed to copy to clipboard", err);
      },
    );
  }

  return { copied, copy };
}
