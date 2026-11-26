import type { ComponentProps } from "react";
import { twMerge } from "tailwind-merge";

// How many characters of a name's end always stay visible.
const TAIL = 8;

type MiddleTruncatedProps = Omit<ComponentProps<"span">, "children"> & {
  text: string;
};

// A name cut in the middle when it doesn't fit, keeping its end: siblings
// that differ only at the end (`orders` and `orders-api`) stay apart. A
// short name is one piece; a long one its head, which truncates, and its
// tail, which doesn't.
export function MiddleTruncated({
  text,
  className,
  ...props
}: MiddleTruncatedProps) {
  if (text.length <= TAIL * 2) {
    return (
      <span className={twMerge("truncate", className)} {...props}>
        {text}
      </span>
    );
  }
  return (
    <span
      aria-label={text}
      className={twMerge("flex min-w-0", className)}
      {...props}
    >
      <span aria-hidden className="truncate">
        {text.slice(0, -TAIL)}
      </span>
      <span aria-hidden className="shrink-0">
        {text.slice(-TAIL)}
      </span>
    </span>
  );
}
