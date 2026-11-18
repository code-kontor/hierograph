import { twMerge } from "tailwind-merge";

import type { GraphStatusQuery } from "@/graphql/generated/graphql";

type GraphStatus = GraphStatusQuery["server"]["graph"];

type GraphStatusIndicatorProps = {
  status: GraphStatus | undefined;
  unreachable: boolean;
};

type Display = { label: string; dot: string; title?: string };

function display(
  status: GraphStatus | undefined,
  unreachable: boolean,
): Display {
  if (unreachable) {
    return { label: "Server not reachable", dot: "bg-status-error" };
  }
  if (!status) {
    return { label: "Connecting…", dot: "bg-[var(--hg-fg-subtle)]" };
  }
  const generation = status.generation
    ? `Graph generation ${status.generation}`
    : undefined;
  switch (status.state) {
    case "LOADED":
      return {
        label: "Graph loaded",
        dot: "bg-status-success",
        title: generation,
      };
    case "UPDATING":
      return {
        label: status.update
          ? `Updating graph (${status.update})`
          : "Updating graph",
        dot: "bg-status-warning",
        title: generation,
      };
    case "UNAVAILABLE":
      return { label: "No graph loaded", dot: "bg-status-error" };
  }
}

// The graph's state in the header: loaded, updating (with the updater's
// phase), no graph, or the server not reachable.
export function GraphStatusIndicator({
  status,
  unreachable,
}: GraphStatusIndicatorProps) {
  const { label, dot, title } = display(status, unreachable);
  return (
    <div
      role="status"
      title={title}
      className="text-fg-muted flex items-center gap-1.5 text-[12px]"
    >
      <span
        aria-hidden
        className={twMerge("block h-[7px] w-[7px] rounded-full", dot)}
      />
      {label}
    </div>
  );
}
