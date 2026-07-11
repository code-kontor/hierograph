import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronRight, Trash2 } from "lucide-react";
import { type ReactNode, useState, useSyncExternalStore } from "react";

import { cn } from "@/design-system/cn";
import { executeRaw } from "@/graphql/client";
import {
  clearQueryLog,
  getSnapshot,
  type QueryLogEntry,
  subscribe,
} from "@/graphql/devQueryLog";
import { buildGraphiqlDeepLink } from "@/graphql/graphiqlDeepLink";

const TOGGLE_BUTTON_CLASSNAME =
  "text-fg-subtle hover:text-fg flex w-fit items-center gap-1 font-mono text-[11px]";
const SECTION_BODY_CLASSNAME =
  "bg-panel-header border-border max-h-32 overflow-auto rounded-[6px] border px-2.5 py-2 font-mono text-[11px]";

type CollapsibleSectionProps = {
  label: string;
  children: ReactNode;
};

function CollapsibleSection({ label, children }: CollapsibleSectionProps) {
  const [isExpanded, setIsExpanded] = useState(false);
  return (
    <>
      <button
        type="button"
        onClick={() => setIsExpanded((v) => !v)}
        className={TOGGLE_BUTTON_CLASSNAME}
      >
        <ChevronRight
          className={cn(
            "size-[13px] transition-transform duration-[120ms]",
            isExpanded && "rotate-90",
          )}
        />
        {label}
      </button>
      {isExpanded && children}
    </>
  );
}

type ResultSectionProps = {
  entry: QueryLogEntry;
};

function ResultSection({ entry }: ResultSectionProps) {
  const [isExpanded, setIsExpanded] = useState(false);
  const result = useQuery({
    queryKey: ["devQueryLogResult", entry.id],
    enabled: isExpanded,
    async queryFn() {
      return executeRaw(entry.queryText, entry.variables);
    },
  });

  return (
    <>
      <button
        type="button"
        onClick={() => setIsExpanded((v) => !v)}
        className={TOGGLE_BUTTON_CLASSNAME}
      >
        <ChevronRight
          className={cn(
            "size-[13px] transition-transform duration-[120ms]",
            isExpanded && "rotate-90",
          )}
        />
        result
      </button>
      {isExpanded && (
        <pre className={SECTION_BODY_CLASSNAME}>
          {result.isPending
            ? "Loading…"
            : result.isError
              ? `Error: ${result.error.message}`
              : JSON.stringify(result.data, null, 2)}
        </pre>
      )}
    </>
  );
}

type QueryLogRowProps = { entry: QueryLogEntry };

function QueryLogRow({ entry }: QueryLogRowProps) {
  const { variables } = entry;
  const hasVariables =
    !!variables &&
    typeof variables === "object" &&
    Object.keys(variables as Record<string, unknown>).length > 0;

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
        <CollapsibleSection label="variables">
          <pre className={SECTION_BODY_CLASSNAME}>
            {JSON.stringify(variables, null, 2)}
          </pre>
        </CollapsibleSection>
      )}
      <CollapsibleSection label="query">
        <pre className={SECTION_BODY_CLASSNAME}>{entry.queryText}</pre>
      </CollapsibleSection>
      <ResultSection entry={entry} />
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
  const queryClient = useQueryClient();
  const isEmpty = entries.length === 0;

  // Clear the log and drop the whole react-query cache. The log entries do not
  // carry their react-query queryKey, so scoping the eviction to just the shown
  // queries is not possible — but the log records every query anyway, so a full
  // clear() matches "all of them". Mounted queries refetch and repopulate.
  function handleClear() {
    clearQueryLog();
    queryClient.clear();
  }

  return (
    <div className="flex flex-col">
      <div className="border-border flex shrink-0 items-center justify-between border-b px-4 py-2">
        <span className="text-fg-subtle font-mono text-[11px]">
          {entries.length} {entries.length === 1 ? "query" : "queries"}
        </span>
        <button
          type="button"
          onClick={handleClear}
          disabled={isEmpty}
          className="text-fg-subtle hover:text-fg flex items-center gap-1 text-xs disabled:cursor-not-allowed disabled:opacity-40"
        >
          <Trash2 className="size-[13px]" />
          Clear
        </button>
      </div>
      {isEmpty ? (
        <p className="text-fg-muted px-4 py-3.5 text-xs">
          No queries recorded yet.
        </p>
      ) : (
        entries
          .slice()
          .reverse()
          .map((entry) => <QueryLogRow key={entry.id} entry={entry} />)
      )}
    </div>
  );
}
