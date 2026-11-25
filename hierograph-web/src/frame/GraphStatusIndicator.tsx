import { TriangleAlert } from "lucide-react";
import { twMerge } from "tailwind-merge";

import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/design-system/ui/popover";
import {
  ServerStatus,
  STATUS_TABS,
  type StatusTab,
} from "@/frame/ServerStatus";
import type { GraphStatusQuery } from "@/graphql/generated/graphql";

type Server = GraphStatusQuery["server"];
type GraphStatus = Server["graph"];

type GraphStatusIndicatorProps = {
  server: Server | undefined;
  unreachable: boolean;
  // The open tab of the status panel (`status=`), `undefined` while closed.
  tab: StatusTab | undefined;
  onTab: (tab: StatusTab | undefined) => void;
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

// What needs the user's attention, shown on the indicator now that the
// details are behind a click, each with the tab that explains it: artifacts
// not compiled yet (not in the graph; the workspace), and a server built from
// uncommitted changes (the server).
type Attention = { note: string; tab: StatusTab };

export function attentionOf(server: Server | undefined): Attention[] {
  const notes: Attention[] = [];
  const notCompiled = server?.artifacts?.notCompiled.length ?? 0;
  if (notCompiled > 0) {
    notes.push({
      note:
        notCompiled === 1
          ? "1 artifact not compiled yet"
          : `${notCompiled} artifacts not compiled yet`,
      tab: "workspace",
    });
  }
  if (server?.commit?.dirty === true) {
    notes.push({
      note: "server built from uncommitted changes",
      tab: "server",
    });
  }
  return notes;
}

// The tab the panel opens on: the one that explains what the indicator
// shows. The graph's own state comes first when it isn't loaded (the graph
// tab), then what needs attention, in its order; otherwise the first tab.
export function tabToOpen(
  server: Server | undefined,
  unreachable: boolean,
): StatusTab {
  if (unreachable || server?.graph.state === "UNAVAILABLE") return "graph";
  return attentionOf(server)[0]?.tab ?? STATUS_TABS[0];
}

// The graph's state in the header: loaded, updating (with the updater's
// phase), no graph, or the server not reachable, marked when something needs
// attention. Clicking it opens the status panel (the graph, the workspace,
// the server and its JVM) on the tab that explains a warning or an error,
// otherwise on the graph.
export function GraphStatusIndicator({
  server,
  unreachable,
  tab,
  onTab,
}: GraphStatusIndicatorProps) {
  const { label, dot, title } = display(server?.graph, unreachable);
  const attention = attentionOf(server).map((entry) => entry.note);
  const details = [title, ...attention].filter(Boolean).join("\n");
  return (
    <Popover
      open={tab !== undefined}
      onOpenChange={(open) =>
        onTab(open ? tabToOpen(server, unreachable) : undefined)
      }
    >
      <PopoverTrigger
        title={details || undefined}
        aria-label={
          attention.length > 0
            ? `${label}: ${attention.join(", ")}. Show the server's status`
            : `${label}. Show the server's status`
        }
        className="hover:bg-state-hover focus-visible:ring-ring text-fg-muted flex h-7 items-center gap-1.5 rounded-[5px] px-2 text-[12px] outline-none focus-visible:ring-2"
      >
        <span
          aria-hidden
          className={twMerge("block h-[7px] w-[7px] rounded-full", dot)}
        />
        <span role="status">{label}</span>
        {attention.length > 0 && (
          <TriangleAlert
            aria-hidden
            data-testid="status-attention"
            className="text-status-warning size-3.5"
          />
        )}
      </PopoverTrigger>
      <PopoverContent
        align="end"
        className="flex max-h-[80vh] w-[480px] max-w-[calc(100vw-32px)] flex-col overflow-hidden p-0 text-[13px]"
      >
        {tab !== undefined && <ServerStatus tab={tab} onTab={onTab} />}
      </PopoverContent>
    </Popover>
  );
}
