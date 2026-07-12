import { Check, Copy } from "lucide-react";
import { twMerge } from "tailwind-merge";

import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";
import { useCopyToClipboard } from "@/design-system/useCopyToClipboard";

import {
  serializePathsForClipboard,
  type SerializePathsInput,
} from "./serializePaths";

// Takes a factory instead of a plain input: the row snapshots come from
// AsyncTree refs, which must only be read on interaction (a click handler),
// never during render (see the AsyncTree refs in PathsPanel). `null` means
// the panel ref is not attached yet — the click is a no-op in that case.
type PathsCopyButtonProps = { buildInput: () => SerializePathsInput | null };

export function PathsCopyButton({ buildInput }: PathsCopyButtonProps) {
  const { copied, copy } = useCopyToClipboard();

  function handleClick() {
    const input = buildInput();
    if (!input) return;
    copy(serializePathsForClipboard(input));
  }

  return (
    <button
      type="button"
      title={copied ? "Copied!" : "Copy paths state to clipboard"}
      aria-label="Copy paths state to clipboard"
      onClick={handleClick}
      className={twMerge(ghostIconTriggerClassName)}
    >
      {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
    </button>
  );
}
