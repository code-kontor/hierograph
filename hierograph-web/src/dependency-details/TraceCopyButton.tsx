import { Check, Copy } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { twMerge } from "tailwind-merge";

import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";

import {
  serializeTraceForClipboard,
  type SerializeTraceInput,
} from "./serializeTrace";

// Takes a factory instead of a plain input: the row snapshots come from
// AsyncTree refs, which must only be read on interaction (a click handler),
// never during render (see the AsyncTree refs in TracePanel).
type TraceCopyButtonProps = { buildInput: () => SerializeTraceInput };

type CopyStatus = "idle" | "copied" | "error";

const RESET_DELAY_MS = 1500;

export function TraceCopyButton({ buildInput }: TraceCopyButtonProps) {
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
    clearTimeout(resetTimerRef.current);
    const text = serializeTraceForClipboard(buildInput());
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
        : "Copy trace state to clipboard";

  return (
    <button
      type="button"
      title={title}
      aria-label="Copy trace state to clipboard"
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
