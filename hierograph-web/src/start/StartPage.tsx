import { useMutation, useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import {
  Info,
  LayoutGrid,
  List,
  type LucideIcon,
  RotateCcw,
} from "lucide-react";
import type { ReactNode } from "react";
import { twMerge } from "tailwind-merge";

import { Pane } from "@/design-system/layout/Pane";
import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";
import { Message } from "@/design-system/ui/message";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/design-system/ui/resizable";
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "@/design-system/ui/tabs";
import { useLocalStorage } from "@/design-system/useLocalStorage";
import { useMediaQuery } from "@/design-system/useMediaQuery";
import { useGraphScope } from "@/frame/GraphScope";
import type { StartScreenQuery } from "@/graphql/generated/graphql";

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
  watchModeLabel,
  yesNo,
} from "./format";
import { HierarchyTree } from "./HierarchyTree";
import {
  runGarbageCollectionMutationOptions,
  startScreenQueryOptions,
} from "./queries";

type Server = StartScreenQuery["server"];
type HierarchyView = StartScreenQuery["views"][number];
type NotCompiled = NonNullable<Server["artifacts"]>["notCompiled"][number];
type Jvm = Server["jvm"];
type MemoryUsage = Jvm["heap"];
type GarbageCollector = Jvm["garbageCollectors"][number];

// The start screen: what the server serves (the workspace, the graph, the
// hierarchy views, the server itself and its JVM), live. It needs no graph: without one it
// still shows the workspace and the server.
export function StartPage() {
  const scope = useGraphScope();
  const query = useQuery(startScreenQueryOptions(scope?.generation ?? null));
  // Tailwind's `lg`.
  const wide = useMediaQuery("(min-width: 64rem)");

  if (query.isPending) {
    return <Message variant="loading" title="Loading the server's state…" />;
  }
  if (query.isError) {
    return (
      <Message variant="error" title="The Hierograph server can't be reached">
        The start screen keeps checking and fills in as soon as the server
        answers.
      </Message>
    );
  }

  const { server, views } = query.data;
  const viewsBlock = (
    <HierarchyViewsBlock modules={server.graph.counts?.modules} views={views} />
  );
  const tabs = <ServerTabs server={server} />;
  // As tall as the space it is given, scrolling on its own.
  const tree = (
    <div className="relative h-full min-h-[480px] min-w-0">
      <div className="absolute inset-0">
        <HierarchyTree />
      </div>
    </div>
  );
  return wide ? (
    <WideLayout tree={tree} views={viewsBlock} tabs={tabs} />
  ) : (
    <div className="text-fg grid w-full grid-cols-1 gap-3">
      {viewsBlock}
      {tabs}
      {tree}
    </div>
  );
}

type WideLayoutProps = {
  tree: ReactNode;
  views: ReactNode;
  tabs: ReactNode;
};

// On a wide screen the tree on the left, the hierarchy views above the
// server's tabs on the right, all as tall as the window. Both splits are moved
// by the user (and remembered by the browser); each box scrolls on its own.
function WideLayout({ tree, views, tabs }: WideLayoutProps) {
  return (
    <ResizablePanelGroup
      direction="horizontal"
      autoSaveId="start-columns"
      className="text-fg"
    >
      <ResizablePanel id="tree" order={1} defaultSize={40} minSize={20}>
        {tree}
      </ResizablePanel>
      <ResizableHandle
        withHandle
        aria-label="Resize the hierarchy"
        className="mx-0.5 rounded-[4px]"
      />
      <ResizablePanel id="status" order={2} defaultSize={60} minSize={25}>
        <ResizablePanelGroup direction="vertical" autoSaveId="start-rows">
          <ResizablePanel id="views" order={1} defaultSize={45} minSize={15}>
            {views}
          </ResizablePanel>
          <ResizableHandle
            withHandle
            aria-label="Resize the hierarchy views"
            className="my-0.5 rounded-[4px]"
          />
          <ResizablePanel id="tabs" order={2} defaultSize={55} minSize={15}>
            {tabs}
          </ResizablePanel>
        </ResizablePanelGroup>
      </ResizablePanel>
    </ResizablePanelGroup>
  );
}

// The tabs of the lower pane, in order. The id is the `tab` URL parameter
// (validated by the route); without one the first tab shows.
export const START_TABS = ["workspace", "graph", "server", "jvm"] as const;
export type StartTab = (typeof START_TABS)[number];

const TAB_LABELS: Record<StartTab, string> = {
  workspace: "Workspace",
  graph: "Graph",
  server: "Server",
  jvm: "JVM",
};

function isStartTab(value: string): value is StartTab {
  return (START_TABS as readonly string[]).includes(value);
}

type ServerTabsProps = {
  server: Server;
};

// The workspace, the graph, the server and its JVM, one tab each. The tab is
// navigation state (`tab=`), so a link can open one; switching replaces the
// history entry instead of adding one.
function ServerTabs({ server }: ServerTabsProps) {
  const search: { tab?: string } = useSearch({ strict: false });
  const navigate = useNavigate();
  const tab =
    search.tab !== undefined && isStartTab(search.tab)
      ? search.tab
      : START_TABS[0];
  const select = async (value: string) => {
    if (!isStartTab(value)) return;
    await navigate({
      to: "/",
      search: (previous) => ({
        ...previous,
        tab: value === START_TABS[0] ? undefined : value,
      }),
      replace: true,
    });
  };
  const triggers = START_TABS.map((id) => <ServerTab key={id} id={id} />);
  return (
    <Tabs value={tab} onValueChange={select} className="h-full">
      <Pane
        title="Server"
        titleBar={<TabsList className="h-full">{triggers}</TabsList>}
        bodyClassName="flex flex-col"
      >
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
      </Pane>
    </Tabs>
  );
}

type ServerTabProps = {
  id: StartTab;
};

function ServerTab({ id }: ServerTabProps) {
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
          <Fact label="Tests included">{yesNo(source.includeTests)}</Fact>
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
        <>
          {/* The same thin line as above External Types in the tree. */}
          <hr className="border-border" />
          <NotCompiledList
            workspace={workspace}
            artifacts={artifacts.notCompiled}
          />
        </>
      )}
    </TabBody>
  );
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
    <div className="border-status-warning bg-status-warning-bg flex gap-2 rounded-[6px] border px-2.5 py-2 text-[12px]">
      <Info
        aria-hidden
        className="text-status-warning mt-px size-3.5 shrink-0"
      />
      <div>
        <p>
          Not compiled yet, so not in the graph. They are scanned as soon as the
          build writes them.
        </p>
        <ul className="mt-1 flex flex-col gap-0.5">{items}</ul>
      </div>
    </div>
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

function GraphBlock({ graph }: GraphBlockProps) {
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
    </TabBody>
  );
}

type HierarchyViewsBlockProps = {
  // The scanned modules, `undefined` without a graph.
  modules: number | undefined;
  views: HierarchyView[];
};

// How the views are laid out: as cards side by side, in as many rows as
// needed (the default), or one per line. Chosen in the block's corner, kept
// by the browser.
type ViewsLayout = "list" | "cards";

function HierarchyViewsBlock({ modules, views }: HierarchyViewsBlockProps) {
  const current: { view?: string } = useSearch({ strict: false });
  const [stored, setLayout] = useLocalStorage<string>(
    "start-views-layout",
    "cards",
  );
  const layout: ViewsLayout = stored === "list" ? "list" : "cards";
  const rows = views.map((view) => (
    <HierarchyViewRow
      key={view.name}
      view={view}
      current={current.view === view.name}
    />
  ));
  return (
    <Block
      title="Hierarchy views"
      toolbar={<ViewsLayoutToggle layout={layout} onChange={setLayout} />}
    >
      <ul
        className={twMerge(
          "flex flex-col gap-2",
          layout === "cards" &&
            // Every card the same size: rows as tall as the tallest card.
            "grid auto-rows-fr grid-cols-[repeat(auto-fill,minmax(220px,1fr))]",
        )}
      >
        <DefaultViewRow
          modules={modules}
          current={current.view === undefined}
        />
        {rows}
      </ul>
      {views.length === 0 && (
        <p className="text-fg-muted text-[12px]">
          No hierarchy views are defined. Ask the agent to define one, or add a
          document to <span className="font-mono">.hierograph-views/</span>.
        </p>
      )}
    </Block>
  );
}

type ViewsLayoutToggleProps = {
  layout: ViewsLayout;
  onChange: (layout: ViewsLayout) => void;
};

function ViewsLayoutToggle({ layout, onChange }: ViewsLayoutToggleProps) {
  return (
    <div role="group" aria-label="Layout" className="flex items-center gap-0.5">
      <LayoutButton
        icon={List}
        label="Show as a list"
        pressed={layout === "list"}
        onPress={() => onChange("list")}
      />
      <LayoutButton
        icon={LayoutGrid}
        label="Show as cards"
        pressed={layout === "cards"}
        onPress={() => onChange("cards")}
      />
    </div>
  );
}

type LayoutButtonProps = {
  icon: LucideIcon;
  label: string;
  pressed: boolean;
  onPress: () => void;
};

function LayoutButton({
  icon: Icon,
  label,
  pressed,
  onPress,
}: LayoutButtonProps) {
  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed={pressed}
      title={label}
      onClick={onPress}
      className={twMerge(
        ghostIconTriggerClassName,
        "size-5",
        pressed && "bg-state-selected-bg text-primary",
      )}
    >
      <Icon className="size-3.5" />
    </button>
  );
}

// What choosing a view does, on hover of every choosable row.
export const CHOOSE_HINT =
  "Show this UI in this hierarchy view. The agent session isn't affected.";

type DefaultViewRowProps = {
  modules: number | undefined;
  current: boolean;
};

// The hierarchy as scanned: no `view=` at all. It isn't a view name, so
// choosing it drops the parameter.
function DefaultViewRow({ modules, current }: DefaultViewRowProps) {
  const meta = [
    "scanned",
    modules === undefined
      ? undefined
      : countLabel(modules, "module", "modules"),
  ];
  return (
    <ChoosableViewRow
      name="default"
      view={undefined}
      current={current}
      meta={meta}
      description="The hierarchy as scanned from the build."
    />
  );
}

type HierarchyViewRowProps = {
  view: HierarchyView;
  current: boolean;
};

function HierarchyViewRow({ view, current }: HierarchyViewRowProps) {
  // The same scheme for every row: source · count.
  const meta = [
    view.source,
    view.groupCount === null || view.groupCount === undefined
      ? undefined
      : countLabel(view.groupCount, "group", "groups"),
  ];
  if (!view.valid) {
    return (
      <li className="border-border h-full rounded-[6px] border px-2.5 py-2">
        <ViewRowHead
          name={view.name}
          meta={meta}
          badge={<span className="text-status-error text-[12px]">invalid</span>}
        />
        {view.description && <ViewDescription text={view.description} />}
        <ProblemList problems={view.problems} />
      </li>
    );
  }
  return (
    <ChoosableViewRow
      name={view.name}
      view={view.name}
      current={current}
      meta={meta}
      restructure={view.restructure}
      description={view.description ?? undefined}
    />
  );
}

type ChoosableViewRowProps = {
  name: string;
  // The `view=` value; `undefined` for the scanned hierarchy.
  view: string | undefined;
  current: boolean;
  meta: (string | undefined)[];
  restructure?: boolean;
  description: string | undefined;
};

// A row that is a link as a whole: choosing it sets (or drops) `view=`.
function ChoosableViewRow({
  name,
  view,
  current,
  meta,
  restructure,
  description,
}: ChoosableViewRowProps) {
  const badges = <>{restructure && <Badge>restructuring</Badge>}</>;
  return (
    <li>
      <Link
        to="/"
        // The tree of another view starts collapsed, nothing selected.
        search={(previous) => ({ tab: previous.tab, view })}
        title={CHOOSE_HINT}
        // `view: undefined` (the scanned hierarchy) must not match a set view.
        activeOptions={{ explicitUndefined: true }}
        className={twMerge(
          "border-border hover:bg-state-hover hover:border-border-strong block h-full rounded-[6px] border px-2.5 py-2 transition-colors",
          current &&
            "border-primary bg-state-selected-bg hover:border-primary hover:bg-state-selected-bg",
        )}
      >
        <ViewRowHead name={name} meta={meta} badge={badges} />
        {description && <ViewDescription text={description} />}
      </Link>
    </li>
  );
}

type ViewRowHeadProps = {
  name: string;
  meta: (string | undefined)[];
  badge: ReactNode;
};

function ViewRowHead({ name, meta, badge }: ViewRowHeadProps) {
  const text = meta.filter((part) => part !== undefined).join(" · ");
  return (
    <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
      <span className="text-fg font-mono text-[13px] font-medium">{name}</span>
      {badge}
      <span className="text-fg-muted text-[12px]">{text}</span>
    </div>
  );
}

type ViewDescriptionProps = {
  text: string;
};

function ViewDescription({ text }: ViewDescriptionProps) {
  return <p className="text-fg-muted mt-0.5 text-[12px]">{text}</p>;
}

type BadgeProps = {
  children: ReactNode;
};

function Badge({ children }: BadgeProps) {
  return (
    <span className="border-border text-fg-muted rounded-[4px] border px-1 text-[11px]">
      {children}
    </span>
  );
}

type ProblemListProps = {
  problems: string[];
};

function ProblemList({ problems }: ProblemListProps) {
  const items = problems.map((problem) => (
    <ProblemItem key={problem} problem={problem} />
  ));
  return (
    <ul className="text-status-error mt-1 list-disc pl-4 text-[12px]">
      {items}
    </ul>
  );
}

type ProblemItemProps = {
  problem: string;
};

function ProblemItem({ problem }: ProblemItemProps) {
  return <li className="font-mono break-words">{problem}</li>;
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

type BlockProps = {
  title: string;
  toolbar?: ReactNode;
  children: ReactNode;
};

function Block({ title, toolbar, children }: BlockProps) {
  return (
    <section aria-label={title} className="h-full">
      <Pane title={title} toolbar={toolbar} bodyClassName="flex flex-col gap-2">
        {children}
      </Pane>
    </section>
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
