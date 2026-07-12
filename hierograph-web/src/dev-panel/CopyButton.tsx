import { Check, Copy } from "lucide-react";

import { cn } from "@/design-system/cn";
import { useCopyToClipboard } from "@/design-system/useCopyToClipboard";

type CopyButtonProps = {
  value: string;
  label: string;
  className?: string;
};

// Small icon button that copies `value` to the clipboard and briefly flips to a
// checkmark. Used next to truncated values (node title, ids) so the full text is
// still recoverable even when the label is clipped.
export function CopyButton({ value, label, className }: CopyButtonProps) {
  const { copied, copy } = useCopyToClipboard();

  function handleCopy(e: React.MouseEvent) {
    e.stopPropagation();
    copy(value);
  }

  return (
    <button
      type="button"
      onClick={handleCopy}
      title={label}
      aria-label={label}
      className={cn(
        "text-fg-subtle hover:text-fg flex h-5 w-5 shrink-0 items-center justify-center rounded",
        className,
      )}
    >
      {copied ? <Check className="size-3" /> : <Copy className="size-3" />}
    </button>
  );
}
