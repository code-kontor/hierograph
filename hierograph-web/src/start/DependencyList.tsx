import { useQuery } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { ChevronLeft, ChevronRight, X } from "lucide-react";
import { type ReactNode, useState } from "react";

import { treeNodeKind, treeNodeLabel } from "@/design-system/graph/treeLabels";
import { Pane } from "@/design-system/layout/Pane";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/design-system/ui/table";
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "@/design-system/ui/tabs";
import { type GraphScope, useGraphScope } from "@/frame/GraphScope";

import { PICK_PARAMS, useDependencyTreePicks } from "./DependencyTrees";
import {
  DEPENDENCY_PAGE_SIZE,
  type DependencyDirection,
  type DependencyEnd,
  type DependencyListEntry,
  type DependencyPicks,
  dependencySetPageQueryOptions,
} from "./queries";

// The start page's params the list reads (see the start route).
type DependencyListSearch = {
  selected_id?: unknown;
  dependency_list?: unknown;
};

// The list's tabs, in order. The id is the `dependency_list` URL parameter
// (validated by the route); without one the first tab shows.
export const DEPENDENCY_LIST_TABS = ["incoming", "outgoing"] as const;
type DependencyListTab = (typeof DEPENDENCY_LIST_TABS)[number];

const TAB_LABELS: Record<DependencyListTab, string> = {
  incoming: "Incoming dependencies",
  outgoing: "Outgoing dependencies",
};

function isListTab(value: unknown): value is DependencyListTab {
  return (DEPENDENCY_LIST_TABS as readonly unknown[]).includes(value);
}

// The pane below the dependency trees (#77): the selection's type-level
// dependencies, a tab each for the incoming and the outgoing ones, each
// narrowed by the picks in the trees on its side, a page at a time. The tab
// is navigation state (`dependency_list=`), so a link can open one;
// switching replaces the history entry, and the first tab leaves no param
// behind.
export function DependencyListPane() {
  const scope = useGraphScope();
  const search: DependencyListSearch = useSearch({ strict: false });
  const navigate = useNavigate();
  const picks = useDependencyTreePicks();
  const tab = isListTab(search.dependency_list)
    ? search.dependency_list
    : DEPENDENCY_LIST_TABS[0];
  const select = async (value: string) => {
    if (!isListTab(value)) return;
    await navigate({
      to: ".",
      search: (previous: Record<string, unknown>) => ({
        ...previous,
        dependency_list: value === DEPENDENCY_LIST_TABS[0] ? undefined : value,
      }),
      replace: true,
    } as never);
  };
  const selectedId =
    search.selected_id === undefined || search.selected_id === null
      ? undefined
      : String(search.selected_id);
  const incoming: DependencyPicks = {
    source: picks.usedBy,
    target: picks.part,
  };
  const outgoing: DependencyPicks = { source: picks.part, target: picks.uses };
  const triggers = DEPENDENCY_LIST_TABS.map((id) => (
    <ListTabTrigger key={id} id={id} />
  ));
  // The picks that narrow each tab, as chips over its list.
  const chips: Record<DependencyDirection, ScopeChip[]> = {
    incoming: [
      { role: "Used by", id: picks.usedBy, param: PICK_PARAMS.usedBy },
      { role: "Part", id: picks.part, param: PICK_PARAMS.part },
    ],
    outgoing: [
      { role: "Part", id: picks.part, param: PICK_PARAMS.part },
      { role: "Uses", id: picks.uses, param: PICK_PARAMS.uses },
    ],
  };
  const drop = async (param: string) => {
    await navigate({
      to: ".",
      search: (previous: Record<string, unknown>) => ({
        ...previous,
        [param]: undefined,
      }),
      replace: true,
    } as never);
  };
  const list = (direction: DependencyDirection, narrowing: DependencyPicks) =>
    selectedId === undefined || !scope ? (
      <Hint>Select a node in the hierarchy to list its dependencies.</Hint>
    ) : (
      <DependencyList
        // A new set or narrowing starts on its first page.
        key={`${selectedId}|${narrowing.source ?? ""}|${narrowing.target ?? ""}`}
        title={TAB_LABELS[direction]}
        scope={scope}
        selectionId={selectedId}
        direction={direction}
        picks={narrowing}
        chips={chips[direction].filter((chip) => chip.id !== undefined)}
        onDrop={drop}
      />
    );
  return (
    <Tabs value={tab} onValueChange={select} className="h-full">
      <Pane
        title="Dependency list"
        titleBar={<TabsList className="h-full">{triggers}</TabsList>}
      >
        <TabsContent value="incoming">{list("incoming", incoming)}</TabsContent>
        <TabsContent value="outgoing">{list("outgoing", outgoing)}</TabsContent>
      </Pane>
    </Tabs>
  );
}

type ListTabTriggerProps = {
  id: DependencyListTab;
};

function ListTabTrigger({ id }: ListTabTriggerProps) {
  return <TabsTrigger value={id}>{TAB_LABELS[id]}</TabsTrigger>;
}

type HintProps = {
  children: ReactNode;
};

function Hint({ children }: HintProps) {
  return <p className="text-fg-muted text-[13px]">{children}</p>;
}

// A pick that narrows a list: its role, the node, its URL param.
type ScopeChip = { role: string; id: string | undefined; param: string };

type DependencyListProps = {
  title: string;
  scope: GraphScope;
  selectionId: string;
  direction: DependencyDirection;
  picks: DependencyPicks;
  chips: ScopeChip[];
  onDrop: (param: string) => Promise<void>;
};

// One direction's list as a table: source, target, and the number of
// references; above it the count, the picks that narrow it (each removable)
// and the paging.
function DependencyList({
  title,
  scope,
  selectionId,
  direction,
  picks,
  chips,
  onDrop,
}: DependencyListProps) {
  const [page, setPage] = useState(1);
  const query = useQuery(
    dependencySetPageQueryOptions(scope, selectionId, direction, picks, page),
  );
  const data = query.data;
  const name = (id: string | undefined) => {
    if (id === undefined) return undefined;
    const node = data?.picked.get(id);
    if (!node) return undefined;
    return treeNodeKind(node.type, node.text) === "type"
      ? simpleName(node)
      : treeNodeLabel(node.type, node.text, undefined);
  };
  const first = (page - 1) * DEPENDENCY_PAGE_SIZE + 1;
  const last = data ? first + data.dependencies.length - 1 : first;
  const rows = (data?.dependencies ?? []).map((dependency) => (
    <DependencyRow key={dependency.id} dependency={dependency} />
  ));
  const chipButtons = chips.map((chip) => (
    <ScopeChipButton
      key={chip.param}
      chip={chip}
      name={name(chip.id) ?? "…"}
      onDrop={onDrop}
    />
  ));
  return (
    <section aria-label={title} className="flex flex-col">
      <div className="flex shrink-0 flex-wrap items-center gap-1.5 pb-1.5">
        <h3 className="text-fg-muted text-[12px]">
          {data
            ? `${data.total} ${data.total === 1 ? "dependency" : "dependencies"}`
            : "Loading the dependencies…"}
        </h3>
        {chipButtons}
        <span className="flex-1" />
        {data && data.pages > 1 && (
          <>
            <span className="text-fg-muted text-[12px] tabular-nums">
              {first}–{last} of {data.total}
            </span>
            <PageButton
              label={`Previous page of ${title.toLowerCase()} dependencies`}
              disabled={page <= 1}
              onClick={() => setPage(page - 1)}
            >
              <ChevronLeft className="size-3.5" />
            </PageButton>
            <PageButton
              label={`Next page of ${title.toLowerCase()} dependencies`}
              disabled={page >= data.pages}
              onClick={() => setPage(page + 1)}
            >
              <ChevronRight className="size-3.5" />
            </PageButton>
          </>
        )}
      </div>
      {data && data.total > 0 && (
        <Table aria-label={title} className="w-full table-fixed text-[12px]">
          <TableHeader>
            <TableRow>
              <TableHead>Source</TableHead>
              <TableHead>Target</TableHead>
              <TableHead
                // Fixed and narrow, so source and target share the width.
                className="w-24 text-right"
                title="Individual references from the source type to the target type"
              >
                References
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>{rows}</TableBody>
        </Table>
      )}
    </section>
  );
}

type PageButtonProps = {
  label: string;
  disabled: boolean;
  onClick: () => void;
  children: ReactNode;
};

function PageButton({ label, disabled, onClick, children }: PageButtonProps) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="hover:bg-state-hover focus-visible:ring-ring flex size-6 items-center justify-center rounded-[4px] outline-none focus-visible:ring-2 disabled:opacity-40"
    >
      {children}
    </button>
  );
}

type ScopeChipButtonProps = {
  chip: ScopeChip;
  name: string;
  onDrop: (param: string) => Promise<void>;
};

// A pick narrowing the list; clicking it drops the pick.
function ScopeChipButton({ chip, name, onDrop }: ScopeChipButtonProps) {
  return (
    <button
      type="button"
      aria-label={`Drop ${chip.role.toLowerCase()} ${name}`}
      title="Drop this pick"
      onClick={() => onDrop(chip.param)}
      className="border-border-strong bg-state-selected-secondary-bg text-state-selected-fg hover:bg-state-hover flex h-5 items-center gap-1 rounded-[4px] border px-1.5 text-[11px]"
    >
      <span className="text-fg-muted">{chip.role}:</span>
      <span className="font-mono">{name}</span>
      <X aria-hidden className="size-3" />
    </button>
  );
}

// A type's name without its package, a nested type after its enclosing one
// (`a.b.Outer$Inner` → `Outer.Inner`), and its package.
function simpleName(end: DependencyEnd): string {
  return end.text.slice(end.text.lastIndexOf(".") + 1).replaceAll("$", ".");
}

function packageOf(end: DependencyEnd): string {
  const dot = end.text.lastIndexOf(".");
  return dot < 0 ? "" : end.text.slice(0, dot);
}

type DependencyRowProps = {
  dependency: DependencyListEntry;
};

function DependencyRow({ dependency }: DependencyRowProps) {
  const { source, target, weight } = dependency;
  const label = `${source.text} → ${target.text}`;
  return (
    <TableRow
      aria-label={label}
      title={`${label}\n${weight === 1 ? "1 reference" : `${weight} references`}`}
    >
      <TypeCell end={source} />
      <TypeCell end={target} />
      <TableCell className="text-right tabular-nums">{weight}</TableCell>
    </TableRow>
  );
}

type TypeCellProps = {
  end: DependencyEnd;
};

// A type by its simple name, its package after it, subdued.
function TypeCell({ end }: TypeCellProps) {
  return (
    <TableCell className="truncate font-mono">
      {simpleName(end)}
      <span className="text-fg-subtle ml-1.5 text-[11px]">
        {packageOf(end)}
      </span>
    </TableCell>
  );
}
