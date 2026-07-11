import { Check, Copy } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { twMerge } from "tailwind-merge";

import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";

import {
  serializePathsForClipboard,
  type SerializePathsInput,
} from "./serializePaths";

// Takes a factory instead of a plain input: the row snapshots come from
// AsyncTree refs, which must only be read on interaction (a click handler),
// never during render (see the AsyncTree refs in PathsPanel). `null` means
// the panel ref is not attached yet — the click is a no-op in that case.
type PathsCopyButtonProps = { buildInput: () => SerializePathsInput | null };

type CopyStatus = "idle" | "copied" | "error";

const RESET_DELAY_MS = 1500;

export function PathsCopyButton({ buildInput }: PathsCopyButtonProps) {
  const [status, setStatus] = useState<CopyStatus>("idle");
  const resetTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );

  useEffect(() => {
    return () => {
      clearTimeout(resetTimerRef.current);
    };
  }, []);

  async function handleClick() {
    const input = buildInput();
    if (!input) return;
    clearTimeout(resetTimerRef.current);
    const text = serializePathsForClipboard(input);
    try {
      if (!navigator.clipboard) {
        throw new Error("Clipboard API not available");
      }
      await navigator.clipboard.writeText(text);
      setStatus("copied");
    } catch {
      setStatus("error");
    }
    resetTimerRef.current = setTimeout(() => setStatus("idle"), RESET_DELAY_MS);
  }

  const title =
    status === "copied"
      ? "Copied!"
      : status === "error"
        ? "Copy failed"
        : "Copy paths state to clipboard";

  return (
    <button
      type="button"
      title={title}
      aria-label="Copy paths state to clipboard"
      onClick={handleClick}
      className={twMerge(ghostIconTriggerClassName)}
    >
      {status === "copied" ? (
        <Check className="size-4" />
      ) : (
        <Copy className="size-4" />
      )}
    </button>
  );
}
