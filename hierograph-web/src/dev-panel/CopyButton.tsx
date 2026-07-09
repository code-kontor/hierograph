import { Check, Copy } from "lucide-react";
import { useState } from "react";

import { cn } from "@/design-system/cn";

type CopyButtonProps = {
  value: string;
  label: string;
  className?: string;
};

// Small icon button that copies `value` to the clipboard and briefly flips to a
// checkmark. Used next to truncated values (node title, ids) so the full text is
// still recoverable even when the label is clipped.
export function CopyButton({ value, label, className }: CopyButtonProps) {
  const [copied, setCopied] = useState(false);

  function handleCopy(e: React.MouseEvent) {
    e.stopPropagation();
    navigator.clipboard.writeText(value).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    });
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
