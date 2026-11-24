import type { LucideIcon } from "lucide-react";
import { CircleAlert, Info, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "@/design-system/cn";

// A note inside a pane, about what the pane shows: what the graph doesn't
// have yet (warning), what a view leaves out (info), what went wrong (error).
// Every notice is laid out alike: the icon in a column of its own, beside it
// a title, and below the title the explanation and whatever the note lists.
// For a screen-sized state (loading, empty, failed) use `Message`.
type NoticeVariant = "info" | "warning" | "error";

const BOX: Record<NoticeVariant, string> = {
  info: "border-status-info bg-status-info-bg",
  warning: "border-status-warning bg-status-warning-bg",
  error: "border-status-error bg-status-error-bg",
};

const ACCENT: Record<NoticeVariant, string> = {
  info: "text-status-info",
  warning: "text-status-warning",
  error: "text-status-error",
};

const DEFAULT_ICON: Record<NoticeVariant, LucideIcon> = {
  info: Info,
  warning: TriangleAlert,
  error: CircleAlert,
};

type NoticeProps = {
  variant: NoticeVariant;
  title: ReactNode;
  icon?: LucideIcon;
  // The notice's accessible name: it is a region of its own.
  label: string;
  children?: ReactNode;
  className?: string;
};

function Notice({
  variant,
  title,
  icon,
  label,
  children,
  className,
}: NoticeProps) {
  const Icon = icon ?? DEFAULT_ICON[variant];
  return (
    <section
      aria-label={label}
      data-slot="notice"
      className={cn(
        "flex gap-2 rounded-[6px] border px-2.5 py-2 text-[12px]",
        BOX[variant],
        className,
      )}
    >
      <Icon
        aria-hidden
        className={cn("mt-px size-3.5 shrink-0", ACCENT[variant])}
      />
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <h3 className={cn("font-semibold", ACCENT[variant])}>{title}</h3>
        {children}
      </div>
    </section>
  );
}

export { Notice };
