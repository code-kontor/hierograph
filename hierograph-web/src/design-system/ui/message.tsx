import { cva, type VariantProps } from "class-variance-authority";
import type { LucideIcon } from "lucide-react";
import { CircleAlert, Inbox, Info, Loader2 } from "lucide-react";
import type { AriaRole, ReactNode } from "react";

import { cn } from "@/design-system/cn";

const messageVariants = cva(
  "flex items-start gap-2.5 rounded-[8px] border px-3.5 py-3 text-[13px]",
  {
    variants: {
      variant: {
        loading: "border-border text-muted-foreground",
        empty: "border-dashed border-border-strong text-muted-foreground",
        info: "border-status-info bg-status-info-bg text-muted-foreground",
        error: "border-status-error bg-status-error-bg text-muted-foreground",
      },
    },
  },
);

const iconClassnameByVariant: Record<
  NonNullable<VariantProps<typeof messageVariants>["variant"]>,
  string
> = {
  loading: "text-primary animate-spin",
  empty: "text-[var(--hg-fg-subtle)]",
  info: "text-status-info",
  error: "text-status-error",
};

const defaultIconByVariant: Record<
  NonNullable<VariantProps<typeof messageVariants>["variant"]>,
  LucideIcon
> = {
  loading: Loader2,
  empty: Inbox,
  info: Info,
  error: CircleAlert,
};

const roleByVariant: Record<
  NonNullable<VariantProps<typeof messageVariants>["variant"]>,
  AriaRole | undefined
> = {
  loading: "status",
  empty: undefined,
  info: undefined,
  error: "alert",
};

type MessageProps = {
  variant: "loading" | "empty" | "info" | "error";
  title?: string;
  children?: ReactNode;
  icon?: LucideIcon;
  className?: string;
};

function Message({ variant, title, children, icon, className }: MessageProps) {
  const Icon = icon ?? defaultIconByVariant[variant];

  return (
    <div
      data-slot="message"
      role={roleByVariant[variant]}
      className={cn(messageVariants({ variant }), className)}
    >
      <Icon
        className={cn("size-4 shrink-0", iconClassnameByVariant[variant])}
      />
      <div className="flex flex-col gap-0.5">
        {title && (
          <div
            className={cn(
              variant === "error"
                ? "text-status-error font-semibold"
                : "text-foreground font-semibold",
            )}
          >
            {title}
          </div>
        )}
        {children}
      </div>
    </div>
  );
}

export { Message };
