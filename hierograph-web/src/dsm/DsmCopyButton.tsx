import { Check, Copy } from "lucide-react";
import { twMerge } from "tailwind-merge";

import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";
import { useCopyToClipboard } from "@/design-system/useCopyToClipboard";

import {
  serializeDsmForClipboard,
  type SerializeDsmInput,
} from "./serializeDsm";

type DsmCopyButtonProps = { input: SerializeDsmInput };

export function DsmCopyButton({ input }: DsmCopyButtonProps) {
  const { copied, copy } = useCopyToClipboard();

  function handleClick() {
    copy(serializeDsmForClipboard(input));
  }

  return (
    <button
      type="button"
      title={copied ? "Copied!" : "Copy matrix to clipboard"}
      aria-label="Copy matrix to clipboard"
      onClick={handleClick}
      className={twMerge(ghostIconTriggerClassName)}
    >
      {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
    </button>
  );
}
