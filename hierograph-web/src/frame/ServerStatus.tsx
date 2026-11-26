import {
  useMutation,
  useQuery,
  type UseQueryResult,
} from "@tanstack/react-query";
import { RotateCcw } from "lucide-react";
import type { ReactNode } from "react";
import { twMerge } from "tailwind-merge";

import { Notice } from "@/design-system/ui/notice";
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "@/design-system/ui/tabs";
import { ExcludedNodes } from "@/frame/ExcludedNodes";
import { type GraphScope, useGraphScope } from "@/frame/GraphScope";
import {
  runGarbageCollectionMutationOptions,
  serverStatusQueryOptions,
  viewExclusionsQueryOptions,
} from "@/frame/queries";
import type { ServerStatusQuery } from "@/graphql/generated/graphql";

import {
  buildFilesModeLabel,
  countLabel,
  formatBytes,
  formatCount,
  formatDuration,
  formatMillis,
  formatPercent,
  formatTime,
  relativeTo,
  sourceKindLabel,
  testsIncludedLabel,
  watchModeLabel,
  yesNo,
} from "./format";

type Server = ServerStatusQuery["server"];
type NotCompiled = NonNullable<Server["artifacts"]>["notCompiled"][number];
type Jvm = Server["jvm"];
type MemoryUsage = Jvm["heap"];
type GarbageCollector = Jvm["garbageCollectors"][number];

// The panel's tabs, in order. The id is the frame's `status` URL parameter
// (validated by the root route): the panel is open while it is set.
export const STATUS_TABS = ["graph", "workspace", "server", "jvm"] as const;
export type StatusTab = (typeof STATUS_TABS)[number];

const TAB_LABELS: Record<StatusTab, string> = {
  graph: "Graph",
  workspace: "Workspace",
  server: "Server",
  jvm: "JVM",
};

export function isStatusTab(value: unknown): value is StatusTab {
  return (STATUS_TABS as readonly unknown[]).includes(value);
}

type ServerStatusProps = {
  tab: StatusTab;
  onTab: (tab: StatusTab) => void;
};

// The workspace, the graph, the server and its JVM, one tab each, live: the
// content of the header's status panel. Its query runs only while the panel
// is open, as nothing else uses it.
export function ServerStatus({ tab, onTab }: ServerStatusProps) {
  const scope = useGraphScope();
  const query = useQuery(serverStatusQueryOptions(scope?.generation ?? null));
  const select = (value: string) => {
    if (isStatusTab(value)) onTab(value);
  };
  const triggers = STATUS_TABS.map((id) => (
    <StatusTabTrigger key={id} id={id} />
  ));
  return (
    <Tabs value={tab} onValueChange={select} className="flex min-h-0 flex-col">
      <div className="bg-panel-header border-border flex h-[34px] shrink-0 items-stretch border-b">
        <TabsList className="h-full">{triggers}</TabsList>
      </div>
      <div className="min-h-0 flex-1 overflow-auto p-3">
        <StatusBody query={query} />
      </div>
    </Tabs>
  );
}

type StatusBodyProps = {
  query: UseQueryResult<ServerStatusQuery>;
};

function StatusBody({ query }: StatusBodyProps) {
  if (query.isPending) {
    return (
      <p className="text-fg-muted text-[13px]">Loading the server's state…</p>
    );
  }
  if (query.isError) {
    return (
      <p className="text-status-error text-[13px]">
        The Hierograph server can't be reached. The panel keeps checking.
      </p>
    );
  }
  const { server } = query.data;
  return (
    <>
      <TabsContent value="workspace">
        <WorkspaceBlock server={server} />
      </TabsContent>
      <TabsContent value="graph">
        <GraphBlock graph={server.graph} />
      </TabsContent>
      <TabsContent value="server">
        <ServerBlock server={server} />
      </TabsContent>
      <TabsContent value="jvm">
        <JvmBlock jvm={server.jvm} />
      </TabsContent>
    </>
  );
}

type StatusTabTriggerProps = {
  id: StatusTab;
};

function StatusTabTrigger({ id }: StatusTabTriggerProps) {
  return <TabsTrigger value={id}>{TAB_LABELS[id]}</TabsTrigger>;
}

type TabBodyProps = {
  children: ReactNode;
};

function TabBody({ children }: TabBodyProps) {
  return <div className="flex flex-col gap-2">{children}</div>;
}

type WorkspaceBlockProps = {
  server: Server;
};

function WorkspaceBlock({ server }: WorkspaceBlockProps) {
  const { source, artifacts, workspace } = server;
  const scope = useGraphScope();
  const location =
    source.kind === "parquet" ? source.parquetDir : source.manifest;
  return (
    <TabBody>
      <Facts>
        <Fact label="Path" mono>
          {workspace}
        </Fact>
        <Fact label="Graph source">{sourceKindLabel(source.kind)}</Fact>
        {location && (
          <Fact
            label={source.kind === "parquet" ? "Snapshot" : "Manifest"}
            mono
          >
            <WorkspacePath workspace={workspace} path={location} />
          </Fact>
        )}
        {source.kind === "manifest" && (
          <Fact label="Tests included">
            {scope ? (
              <TestsIncluded scope={scope} includeTests={source.includeTests} />
            ) : (
              yesNo(source.includeTests)
            )}
          </Fact>
        )}
        {artifacts && (
          // Neutral: the note below carries the warning.
          <Fact label="Build outputs">{buildOutputsLabel(artifacts)}</Fact>
        )}
        {source.kind === "manifest" && (
          <FileWatching
            watchMode={source.watchMode}
            buildFilesMode={source.buildFilesMode}
          />
        )}
      </Facts>
      {artifacts && artifacts.notCompiled.length > 0 && (
        <NotCompiledList
          workspace={workspace}
          artifacts={artifacts.notCompiled}
        />
      )}
    </TabBody>
  );
}

type TestsIncludedProps = {
  scope: GraphScope;
  includeTests: boolean | null | undefined;
};

// Whether the graph has the tests, and whether the current view leaves them
// out (#85): "yes" alone would say they are in the trees when they aren't.
function TestsIncluded({ scope, includeTests }: TestsIncludedProps) {
  const exclusions = useQuery(viewExclusionsQueryOptions(scope));
  return testsIncludedLabel(includeTests, exclusions.data?.nodes);
}

// One line instead of three counts to reconcile.
function buildOutputsLabel(artifacts: NonNullable<Server["artifacts"]>) {
  const notCompiled = artifacts.notCompiled.length;
  const scanned = `${artifacts.scanned} of ${artifacts.declared} scanned`;
  return notCompiled > 0
    ? `${scanned}, ${notCompiled} not compiled yet`
    : scanned;
}

type WorkspacePathProps = {
  workspace: string;
  path: string;
};

// A path below the workspace, shown relative to it; the full path on hover.
function WorkspacePath({ workspace, path }: WorkspacePathProps) {
  return <span title={path}>{relativeTo(workspace, path)}</span>;
}

type FileWatchingProps = {
  watchMode: string | null | undefined;
  buildFilesMode: string | null | undefined;
};

// How the server follows the build: what happens, not the watcher's name.
// Rows of the workspace's facts under a section heading, so their values line
// up with the rest.
function FileWatching({ watchMode, buildFilesMode }: FileWatchingProps) {
  return (
    <>
      <div className="text-fg-muted col-span-2 mt-2 font-mono text-[11px] tracking-[0.06em] uppercase">
        File watching
      </div>
      <Fact label="Build outputs">{watchModeLabel(watchMode)}</Fact>
      <Fact label="Build files">{buildFilesModeLabel(buildFilesMode)}</Fact>
    </>
  );
}

type NotCompiledListProps = {
  workspace: string;
  artifacts: NotCompiled[];
};

// Expected while the build hasn't run yet, not an error: amber, not red.
function NotCompiledList({ workspace, artifacts }: NotCompiledListProps) {
  const items = artifacts.map((artifact) => (
    <NotCompiledItem
      key={artifact.artifact}
      workspace={workspace}
      artifact={artifact}
    />
  ));
  return (
    <Notice variant="warning" label="Not compiled yet" title="Not compiled yet">
      <p className="text-fg-muted">
        Not in the graph: they are scanned as soon as the build writes them.
      </p>
      <ul className="flex flex-col gap-0.5">{items}</ul>
    </Notice>
  );
}

type NotCompiledItemProps = {
  workspace: string;
  artifact: NotCompiled;
};

function NotCompiledItem({ workspace, artifact }: NotCompiledItemProps) {
  const dirs = artifact.dirs.map((dir) => relativeTo(workspace, dir));
  return (
    <li className="font-mono" title={artifact.dirs.join("\n")}>
      <Breakable text={artifact.artifact} at=":" />
      <span className="text-fg-muted">
        {" → "}
        <Breakable text={dirs.join(", ")} at="/" />
      </span>
    </li>
  );
}

type BreakableProps = {
  text: string;
  at: string;
};

// A path or coordinate that wraps only after `at` (a `/` or `:`), never
// mid-word or at a hyphen.
function Breakable({ text, at }: BreakableProps) {
  const parts = text.split(at);
  const pieces = parts.map((part, index) => (
    <BreakablePiece
      // The pieces of one text never reorder.
      key={index}
      text={index < parts.length - 1 ? part + at : part}
    />
  ));
  return <span className="break-normal">{pieces}</span>;
}

type BreakablePieceProps = {
  text: string;
};

// Kept together: a hyphen inside a piece is no place to wrap.
function BreakablePiece({ text }: BreakablePieceProps) {
  return (
    <>
      <span className="whitespace-nowrap">{text}</span>
      <wbr />
    </>
  );
}

type GraphBlockProps = {
  graph: Server["graph"];
};

function graphStateLabel(graph: Server["graph"]): string {
  switch (graph.state) {
    case "LOADED":
      return "Loaded";
    case "UPDATING":
      return graph.update ? `Updating (${graph.update})` : "Updating";
    case "UNAVAILABLE":
      return "No graph loaded";
  }
}

// The same colours as the status dot in the frame's header.
const GRAPH_STATE_HIGHLIGHT: Record<
  Server["graph"]["state"],
  NonNullable<FactProps["highlight"]>
> = {
  LOADED: "success",
  UPDATING: "warning",
  UNAVAILABLE: "error",
};

// The scanned graph's facts, and below them what the current hierarchy view
// excludes.
function GraphBlock({ graph }: GraphBlockProps) {
  const scope = useGraphScope();
  const counts = graph.counts;
  return (
    <TabBody>
      <Facts>
        <Fact label="Status" highlight={GRAPH_STATE_HIGHLIGHT[graph.state]}>
          {graphStateLabel(graph)}
        </Fact>
        {graph.generation && (
          <Fact label="Generation" mono>
            {graph.generation}
          </Fact>
        )}
        {graph.loadedAt && (
          <Fact label="Loaded at">{formatTime(graph.loadedAt)}</Fact>
        )}
        {counts && (
          <>
            <Fact label="Modules">{formatCount(counts.modules)}</Fact>
            <Fact label="Packages">{formatCount(counts.packages)}</Fact>
            <Fact label="Types">{formatCount(counts.types)}</Fact>
          </>
        )}
      </Facts>
      {scope && <ExcludedNodes scope={scope} />}
    </TabBody>
  );
}

type ServerBlockProps = {
  server: Server;
};

function ServerBlock({ server }: ServerBlockProps) {
  const commit = server.commit;
  return (
    <TabBody>
      <Facts>
        <Fact label="Version" mono>
          {server.version ?? "—"}
        </Fact>
        {commit?.id && (
          <Fact
            label="Commit"
            mono
            highlight={commit.dirty === true ? "warning" : undefined}
          >
            {commit.dirty ? `${commit.id} (uncommitted changes)` : commit.id}
          </Fact>
        )}
        <Fact label="Started at">{formatTime(server.startedAt)}</Fact>
        {server.url && (
          <Fact label="MCP URL" mono>
            {server.url}
          </Fact>
        )}
        {server.uiUrl && (
          <Fact label="UI URL" mono>
            {server.uiUrl}
          </Fact>
        )}
        <Fact label="State" mono>
          <WorkspacePath workspace={server.workspace} path={server.stateDir} />
        </Fact>
      </Facts>
    </TabBody>
  );
}

type JvmBlockProps = {
  jvm: Jvm;
};

// The server JVM's current metrics. The graph is held in memory, so the heap
// against its limit comes first, with the collection that shows what of it is
// really kept.
function JvmBlock({ jvm }: JvmBlockProps) {
  const { threads } = jvm;
  return (
    <TabBody>
      <div className="flex items-center gap-3">
        <div className="min-w-0 flex-1">
          <MemoryMeter label="Heap" usage={jvm.heap} />
        </div>
        <RunGarbageCollection />
      </div>
      <Facts>
        <Fact label="Heap">{memoryLabel(jvm.heap)}</Fact>
        <Fact label="Kept after GC">
          {jvm.heap.usedAfterGcBytes === null ||
          jvm.heap.usedAfterGcBytes === undefined
            ? "no collection yet"
            : formatBytes(jvm.heap.usedAfterGcBytes)}
        </Fact>
        <Fact label="Non-heap">{memoryLabel(jvm.nonHeap)}</Fact>
        <Fact label="CPU load">
          {jvm.processCpuLoad === null || jvm.processCpuLoad === undefined
            ? "—"
            : formatPercent(jvm.processCpuLoad)}
        </Fact>
        <Fact label="Threads">
          {`${threads.live} live, ${threads.peak} peak, ${threads.daemon} daemon`}
        </Fact>
        <Fact label="Uptime">{formatDuration(jvm.uptimeMs)}</Fact>
        <Fact label="Processors">{jvm.availableProcessors}</Fact>
        <Fact label="Java">{`${jvm.javaVersion} (${jvm.javaVendor})`}</Fact>
        <Fact label="VM">{jvm.vmName}</Fact>
      </Facts>
      <GarbageCollectorList collectors={jvm.garbageCollectors} />
    </TabBody>
  );
}

function memoryLabel(usage: MemoryUsage): string {
  const used = `${formatBytes(usage.usedBytes)} used`;
  const committed = `${formatBytes(usage.committedBytes)} committed`;
  return usage.maxBytes === null || usage.maxBytes === undefined
    ? `${used}, ${committed}, no limit`
    : `${used} of ${formatBytes(usage.maxBytes)}, ${committed}`;
}

type MemoryMeterProps = {
  label: string;
  usage: MemoryUsage;
};

// What the last collection kept, used (kept plus garbage since) and committed
// memory against the limit, or against the committed memory when the JVM sets
// no limit.
function MemoryMeter({ label, usage }: MemoryMeterProps) {
  const limit = usage.maxBytes ?? usage.committedBytes;
  const share = (bytes: number) =>
    limit > 0 ? Math.min(100, (bytes / limit) * 100) : 0;
  return (
    <div
      role="meter"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={limit}
      aria-valuenow={usage.usedBytes}
      aria-valuetext={memoryLabel(usage)}
      className="bg-zebra border-border relative h-2 overflow-hidden rounded-full border"
    >
      <div
        className="bg-state-selected-bg absolute inset-y-0 left-0"
        style={{ width: `${share(usage.committedBytes)}%` }}
      />
      <div
        className="bg-primary/40 absolute inset-y-0 left-0"
        style={{ width: `${share(usage.usedBytes)}%` }}
      />
      {usage.usedAfterGcBytes !== null &&
        usage.usedAfterGcBytes !== undefined && (
          <div
            data-testid="kept-after-gc"
            className="bg-primary absolute inset-y-0 left-0"
            style={{ width: `${share(usage.usedAfterGcBytes)}%` }}
          />
        )}
    </div>
  );
}

type GarbageCollectorListProps = {
  collectors: GarbageCollector[];
};

function GarbageCollectorList({ collectors }: GarbageCollectorListProps) {
  const rows = collectors.map((collector) => (
    <GarbageCollectorRow key={collector.name} collector={collector} />
  ));
  return (
    <div className="text-[13px]">
      <div className="text-fg-muted">Garbage collection</div>
      <dl className="mt-0.5 grid grid-cols-[max-content_minmax(0,1fr)] gap-x-4 gap-y-0.5 pl-3">
        {rows}
      </dl>
    </div>
  );
}

// Asks the server for a collection, so "Kept after GC" shows what is really
// kept right now; the JVM figures are reloaded once it is done.
function RunGarbageCollection() {
  const mutation = useMutation(runGarbageCollectionMutationOptions());
  return (
    <div className="flex shrink-0 items-center gap-2">
      {mutation.isError && (
        <span className="text-status-error text-[12px]">
          The collection failed: {mutation.error.message}
        </span>
      )}
      <button
        type="button"
        onClick={() => mutation.mutate()}
        disabled={mutation.isPending}
        title="Run a garbage collection on the server"
        className="border-border hover:bg-state-hover focus-visible:ring-ring flex h-6 items-center gap-1 rounded-[4px] border px-2 text-[12px] outline-none focus-visible:ring-2 disabled:cursor-default disabled:opacity-60"
      >
        <RotateCcw aria-hidden className="text-fg-subtle size-3.5" />
        {mutation.isPending ? "Collecting…" : "Run GC"}
      </button>
    </div>
  );
}

type GarbageCollectorRowProps = {
  collector: GarbageCollector;
};

function GarbageCollectorRow({ collector }: GarbageCollectorRowProps) {
  return (
    <>
      <dt className="text-fg-muted">{collector.name}</dt>
      <dd>
        {`${countLabel(collector.count, "collection", "collections")}, ${formatMillis(collector.timeMs)}`}
      </dd>
    </>
  );
}

type FactsProps = {
  children: ReactNode;
};

function Facts({ children }: FactsProps) {
  return (
    <dl className="grid grid-cols-[max-content_minmax(0,1fr)] gap-x-4 gap-y-1 text-[13px]">
      {children}
    </dl>
  );
}

type FactProps = {
  label: string;
  children: ReactNode;
  mono?: boolean;
  // `success` for a good state, `warning` for an expected, passing one,
  // `error` for a real problem.
  highlight?: "success" | "warning" | "error";
};

function Fact({ label, children, mono, highlight }: FactProps) {
  return (
    <>
      <dt className="text-fg-muted">{label}</dt>
      <dd
        className={twMerge(
          "break-all",
          mono && "font-mono text-[12px]",
          highlight === "success" && "text-status-success font-semibold",
          highlight === "warning" && "text-status-warning font-semibold",
          highlight === "error" && "text-status-error font-semibold",
        )}
      >
        {children}
      </dd>
    </>
  );
}
