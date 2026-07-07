import { ChevronRight } from "lucide-react";
import { useState, useSyncExternalStore } from "react";

import { cn } from "@/design-system/cn";
import {
  getSnapshot,
  type QueryLogEntry,
  subscribe,
} from "@/graphql/devQueryLog";
import { buildGraphiqlDeepLink } from "@/graphql/queryTriggerLabels";

type QueryLogRowProps = { entry: QueryLogEntry };

function QueryLogRow({ entry }: QueryLogRowProps) {
  const { variables } = entry;
  const hasVariables =
    !!variables &&
    typeof variables === "object" &&
    Object.keys(variables as Record<string, unknown>).length > 0;

  const [isExpanded, setIsExpanded] = useState(false);

  return (
    <div className="border-border flex flex-col gap-1.5 border-b px-4 py-3 text-sm last:border-b-0">
      <div className="flex items-center gap-[9px]">
        <span className="text-fg min-w-0 flex-1 overflow-hidden font-mono text-[13px] font-semibold text-ellipsis whitespace-nowrap">
          {entry.operationName}
        </span>
        <span className="border-border text-fg-subtle shrink-0 rounded-[20px] border px-[9px] py-px font-mono text-[11px] font-normal">
          {entry.trigger}
        </span>
        <span className="text-fg-subtle shrink-0 font-mono text-[10.5px]">
          {new Date(entry.timestamp).toLocaleTimeString()}
        </span>
      </div>
      {!hasVariables ? (
        <span className="text-fg-subtle font-mono text-[11px]">
          no variables
        </span>
      ) : (
        <>
          <button
            type="button"
            onClick={() => setIsExpanded((v) => !v)}
            className="text-fg-subtle hover:text-fg flex w-fit items-center gap-1 font-mono text-[11px]"
          >
            <ChevronRight
              className={cn(
                "size-[13px] transition-transform duration-[120ms]",
                isExpanded && "rotate-90",
              )}
            />
            variables
          </button>
          {isExpanded && (
            <pre className="bg-panel-header border-border max-h-32 overflow-auto rounded-[6px] border px-2.5 py-2 font-mono text-[11px]">
              {JSON.stringify(variables, null, 2)}
            </pre>
          )}
        </>
      )}
      <a
        href={buildGraphiqlDeepLink(entry.queryText, entry.variables)}
        target="_blank"
        rel="noreferrer"
        className="text-fg-subtle hover:text-fg w-fit text-xs underline"
      >
        Open in GraphiQL
      </a>
    </div>
  );
}

export function QueryLogPanel() {
  const entries = useSyncExternalStore(subscribe, getSnapshot);

  if (entries.length === 0) {
    return (
      <div className="flex flex-col gap-3 px-4 py-3.5 text-sm">
        <p className="text-fg-muted text-xs">No queries recorded yet.</p>
      </div>
    );
  }

  const rows = entries
    .slice()
    .reverse()
    .map((entry) => <QueryLogRow key={entry.id} entry={entry} />);

  return <div className="flex flex-col">{rows}</div>;
}
