import { useQuery } from "@tanstack/react-query";
import { Link, useSearch } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { twMerge } from "tailwind-merge";

import { Pane } from "@/design-system/layout/Pane";
import { Message } from "@/design-system/ui/message";
import { useGraphScope } from "@/frame/GraphScope";
import type { StartScreenQuery } from "@/graphql/generated/graphql";

import {
  countLabel,
  formatCount,
  formatTime,
  lastSegment,
  sourceKindLabel,
  yesNo,
} from "./format";
import { startScreenQueryOptions } from "./queries";

type Server = StartScreenQuery["server"];
type HierarchyView = StartScreenQuery["views"][number];
type NotCompiled = NonNullable<Server["artifacts"]>["notCompiled"][number];

// The start screen: what the server serves (the workspace, the graph, the
// hierarchy views, the server itself), live. It needs no graph: without one it
// still shows the workspace and the server.
export function StartPage() {
  const scope = useGraphScope();
  const query = useQuery(startScreenQueryOptions(scope?.generation ?? null));

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
  return (
    <div className="text-fg flex flex-col gap-3">
      <p className="text-fg-muted text-[13px]">
        Screens are opened from your agent session. Ask it to show a part of the
        graph here.
      </p>
      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        <WorkspaceBlock server={server} />
        <GraphBlock graph={server.graph} />
        <HierarchyViewsBlock views={views} />
        <ServerBlock server={server} />
      </div>
    </div>
  );
}

type WorkspaceBlockProps = {
  server: Server;
};

function WorkspaceBlock({ server }: WorkspaceBlockProps) {
  const { source, artifacts } = server;
  const location =
    source.kind === "parquet" ? source.parquetDir : source.manifest;
  return (
    <Block title="Workspace">
      <h2 className="text-[16px] font-semibold">
        {lastSegment(server.workspace)}
      </h2>
      <Facts>
        <Fact label="Path" mono>
          {server.workspace}
        </Fact>
        <Fact label="Graph source">{sourceKindLabel(source.kind)}</Fact>
        {location && (
          <Fact
            label={source.kind === "parquet" ? "Snapshot" : "Manifest"}
            mono
          >
            {location}
          </Fact>
        )}
        {source.kind === "manifest" && (
          <>
            <Fact label="Tests included">{yesNo(source.includeTests)}</Fact>
            <Fact label="Build outputs">{source.watchMode ?? "—"}</Fact>
            <Fact label="Build files">{source.buildFilesMode ?? "—"}</Fact>
          </>
        )}
        {artifacts && (
          <>
            <Fact label="Artifacts declared">{artifacts.declared}</Fact>
            <Fact label="Artifacts scanned">{artifacts.scanned}</Fact>
            <Fact
              label="Not compiled"
              highlight={artifacts.notCompiled.length > 0}
            >
              {artifacts.notCompiled.length}
            </Fact>
          </>
        )}
      </Facts>
      {artifacts && artifacts.notCompiled.length > 0 && (
        <NotCompiledList artifacts={artifacts.notCompiled} />
      )}
    </Block>
  );
}

type NotCompiledListProps = {
  artifacts: NotCompiled[];
};

function NotCompiledList({ artifacts }: NotCompiledListProps) {
  const items = artifacts.map((artifact) => (
    <NotCompiledItem key={artifact.artifact} artifact={artifact} />
  ));
  return (
    <div className="text-[12px]">
      <p className="text-status-error">
        Not compiled yet, so missing from the graph. They are scanned as soon as
        the build writes them.
      </p>
      <ul className="mt-1 flex flex-col gap-0.5">{items}</ul>
    </div>
  );
}

type NotCompiledItemProps = {
  artifact: NotCompiled;
};

function NotCompiledItem({ artifact }: NotCompiledItemProps) {
  return (
    <li className="font-mono break-all" title={artifact.dirs.join("\n")}>
      {artifact.artifact}
    </li>
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

function GraphBlock({ graph }: GraphBlockProps) {
  const counts = graph.counts;
  return (
    <Block title="Graph">
      <Facts>
        <Fact label="Status" highlight={graph.state === "UNAVAILABLE"}>
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
    </Block>
  );
}

type HierarchyViewsBlockProps = {
  views: HierarchyView[];
};

function HierarchyViewsBlock({ views }: HierarchyViewsBlockProps) {
  const current: { view?: string } = useSearch({ strict: false });
  const rows = views.map((view) => (
    <HierarchyViewRow
      key={view.name}
      view={view}
      current={current.view === view.name}
    />
  ));
  return (
    <Block title="Hierarchy views">
      {views.length === 0 ? (
        <p className="text-fg-muted text-[13px]">
          No hierarchy views are defined. Ask the agent to define one, or add a
          document to <span className="font-mono">.hierograph-views/</span>.
        </p>
      ) : (
        <>
          <p className="text-fg-muted text-[12px]">
            Choose one to see every screen through it.
          </p>
          <ul className="flex flex-col gap-2">{rows}</ul>
          {current.view !== undefined && <ClearHierarchyView />}
        </>
      )}
    </Block>
  );
}

type HierarchyViewRowProps = {
  view: HierarchyView;
  current: boolean;
};

function HierarchyViewRow({ view, current }: HierarchyViewRowProps) {
  const details = [
    view.source,
    view.restructure ? "restructuring" : undefined,
    view.groupCount !== null && view.groupCount !== undefined
      ? countLabel(view.groupCount, "group", "groups")
      : undefined,
  ].filter((detail) => detail !== undefined);
  return (
    <li
      className={twMerge(
        "border-border rounded-[6px] border px-2.5 py-2",
        current && "border-primary bg-state-selected-bg",
      )}
    >
      <div className="flex items-baseline gap-2">
        {view.valid ? (
          <Link
            to="/"
            search={{ view: view.name }}
            className="text-primary font-mono text-[13px] font-medium underline-offset-2 hover:underline"
          >
            {view.name}
          </Link>
        ) : (
          <span className="font-mono text-[13px] font-medium">{view.name}</span>
        )}
        <span className="text-fg-muted text-[12px]">{details.join(" · ")}</span>
        <div className="flex-1" />
        {current && <span className="text-primary text-[12px]">current</span>}
        {!view.valid && (
          <span className="text-status-error text-[12px]">invalid</span>
        )}
      </div>
      {view.description && (
        <p className="text-fg-muted mt-0.5 text-[12px]">{view.description}</p>
      )}
      {!view.valid && <ProblemList problems={view.problems} />}
    </li>
  );
}

function ClearHierarchyView() {
  return (
    <Link
      to="/"
      search={{ view: undefined }}
      className="text-primary self-start text-[12px] underline-offset-2 hover:underline"
    >
      Show without a hierarchy view
    </Link>
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
    <Block title="Server">
      <Facts>
        <Fact label="Version" mono>
          {server.version ?? "—"}
        </Fact>
        {commit?.id && (
          <Fact label="Commit" mono highlight={commit.dirty === true}>
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
          {server.stateDir}
        </Fact>
      </Facts>
    </Block>
  );
}

type BlockProps = {
  title: string;
  children: ReactNode;
};

function Block({ title, children }: BlockProps) {
  return (
    <section aria-label={title}>
      <Pane title={title} bodyClassName="flex flex-col gap-2">
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
  highlight?: boolean;
};

function Fact({ label, children, mono, highlight }: FactProps) {
  return (
    <>
      <dt className="text-fg-muted">{label}</dt>
      <dd
        className={twMerge(
          "break-all",
          mono && "font-mono text-[12px]",
          highlight && "text-status-error font-semibold",
        )}
      >
        {children}
      </dd>
    </>
  );
}
