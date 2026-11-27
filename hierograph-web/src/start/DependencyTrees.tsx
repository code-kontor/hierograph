import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import {
  ArrowRight,
  ChevronDown,
  ChevronRight,
  Crosshair,
  ListChevronsDownUp,
  ListChevronsUpDown,
} from "lucide-react";
import {
  type KeyboardEvent,
  type ReactNode,
  useEffect,
  useRef,
  useState,
} from "react";
import { twMerge } from "tailwind-merge";

import type { Gesture } from "@/design-system/graph/GesturesHelp";
import { NodeIcon } from "@/design-system/graph/treeIcons";
import {
  ordersChildren,
  treeNodeKind,
  treeNodeLabel,
} from "@/design-system/graph/treeLabels";
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from "@/design-system/ui/context-menu";
import { type GraphScope, useGraphScope } from "@/frame/GraphScope";

import {
  type DependencyDirection,
  type DependencyNode,
  dependencyPickPathQueryOptions,
  type DependencyPicks,
  dependencySetChildrenQueryOptions,
  dependencySetQueryOptions,
  type DependencySide,
  hierarchyTreeChildrenQueryOptions,
  hierarchyTreeRootQueryOptions,
} from "./queries";

// The nodes picked in the three trees (start route): one that uses the
// selection (`used_by_id`), a part of the selection (`selection_part_id`),
// and one the selection uses (`uses_id`).
export type DependencyTreePicks = {
  usedBy?: string;
  part?: string;
  uses?: string;
};

export const PICK_PARAMS = {
  usedBy: "used_by_id",
  part: "selection_part_id",
  uses: "uses_id",
} as const;

type PickKey = keyof typeof PICK_PARAMS;

// Each tree's open rows, also in the URL, so a link restores them (#85): the
// rows below the top. The top is open; only the Selection tree shows its
// top, which can close (see `SELECTION_COLLAPSED_PARAM`).
export const OPEN_PARAMS = {
  usedBy: "used_by_expanded_ids",
  part: "selection_expanded_ids",
  uses: "uses_expanded_ids",
} as const satisfies Record<PickKey, string>;

// The Selection tree's top, the selection, is open unless closed
// (`selection_collapsed`): open is what a new selection starts with.
export const SELECTION_COLLAPSED_PARAM = "selection_collapsed";

// Everything that keeps the trees' rows: they belong to one selection.
export const TREE_ROW_PARAMS = [
  ...Object.values(OPEN_PARAMS),
  SELECTION_COLLAPSED_PARAM,
];

// The start page's params the dependency trees read (see the start route).
type DependencySearch = {
  selected_id?: unknown;
  selected_cycle_ids?: unknown;
  used_by_id?: unknown;
  selection_part_id?: unknown;
  uses_id?: unknown;
};

function idParam(value: unknown): string | undefined {
  return value === undefined || value === null ? undefined : String(value);
}

// Which directions the page shows: both (the default), or only the
// incoming or only the outgoing dependencies (`dependency_directions`; the
// route validates it). One direction hides the other side's tree and the
// list's other tab.
export const DIRECTION_MODES = ["incoming", "outgoing"] as const;
export type DirectionMode = "both" | (typeof DIRECTION_MODES)[number];

export function useDirectionMode(): DirectionMode {
  const search: { dependency_directions?: unknown } = useSearch({
    strict: false,
  });
  return (DIRECTION_MODES as readonly unknown[]).includes(
    search.dependency_directions,
  )
    ? (search.dependency_directions as DirectionMode)
    : "both";
}

const MODE_LABELS: Record<DirectionMode, string> = {
  incoming: "Incoming",
  both: "Both",
  outgoing: "Outgoing",
};

const MODE_TITLES: Record<DirectionMode, string> = {
  incoming: "Only what uses the selection",
  both: "What uses the selection and what it uses",
  outgoing: "Only what the selection uses",
};

// Switches the directions shown. The picks stay: one on a side that goes
// is ignored while it is hidden and back with its side. "Both" leaves no
// param behind, and the list shows the tab of the direction.
export function useChooseDirection(): (next: DirectionMode) => Promise<void> {
  const navigate = useNavigate();
  return async (next) => {
    await navigate({
      to: ".",
      search: (previous: Record<string, unknown>) => ({
        ...previous,
        dependency_directions: next === "both" ? undefined : next,
        dependency_list: undefined,
      }),
      replace: true,
    } as never);
  };
}

const MODE_ORDER = ["incoming", "both", "outgoing"] as const;

// The switch between the directions, in the pane's title bar: three small
// icons in one outline, the chosen one on the secondary selection's fill. A
// radio group: one tab stop, the arrow keys move between the options (and
// choose, as radios do), Home and End to the ends.
export function DirectionModeSwitch() {
  const mode = useDirectionMode();
  const choose = useChooseDirection();
  const group = useRef<HTMLDivElement>(null);
  const move = async (event: KeyboardEvent) => {
    const at = MODE_ORDER.indexOf(mode);
    const step: Record<string, number> = {
      ArrowLeft: at - 1,
      ArrowUp: at - 1,
      ArrowRight: at + 1,
      ArrowDown: at + 1,
      Home: 0,
      End: MODE_ORDER.length - 1,
    };
    if (!(event.key in step)) return;
    event.preventDefault();
    const index = (step[event.key] + MODE_ORDER.length) % MODE_ORDER.length;
    const radios =
      group.current?.querySelectorAll<HTMLButtonElement>('[role="radio"]');
    radios?.[index]?.focus();
    await choose(MODE_ORDER[index]);
  };
  const segments = MODE_ORDER.map((id) => (
    <ModeSegment
      key={id}
      id={id}
      checked={id === mode}
      onChoose={() => (id === mode ? Promise.resolve() : choose(id))}
    />
  ));
  return (
    <div
      ref={group}
      role="radiogroup"
      aria-label="Directions"
      onKeyDown={move}
      // Apart from the pane's ?, which isn't one of them.
      className="border-border-strong mr-2 flex items-center gap-px rounded-[5px] border p-px normal-case"
    >
      {segments}
    </div>
  );
}

type ModeSegmentProps = {
  id: DirectionMode;
  checked: boolean;
  onChoose: () => Promise<void>;
};

function ModeSegment({ id, checked, onChoose }: ModeSegmentProps) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={checked}
      aria-label={MODE_LABELS[id]}
      // Roving: only the chosen one is a tab stop.
      tabIndex={checked ? 0 : -1}
      title={`${MODE_LABELS[id]}: ${MODE_TITLES[id].toLowerCase()}`}
      onClick={onChoose}
      className={twMerge(
        "text-fg-subtle hover:bg-state-hover hover:text-fg-muted focus-visible:ring-ring flex h-5 w-6 items-center justify-center rounded-[3px] outline-none focus-visible:ring-2",
        // Its own state, not the hover's: the secondary selection's fill,
        // edged, and the icon dark.
        checked &&
          "bg-state-selected-secondary-bg hover:bg-state-selected-secondary-bg text-fg hover:text-fg shadow-[inset_0_0_0_1px_var(--hg-border-strong)]",
      )}
    >
      <ModeIcon id={id} />
    </button>
  );
}

type ModeIconProps = {
  id: DirectionMode;
};

// The selection as a bar, the dependencies as arrows read left to right, as
// the trees are: into it (incoming), through it (both), out of it
// (outgoing). Drawn like a Lucide icon.
function ModeIcon({ id }: ModeIconProps) {
  const into = id !== "outgoing";
  const out = id !== "incoming";
  return (
    <svg
      aria-hidden
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className="size-4"
    >
      <rect x="10" y="5" width="4" height="14" rx="1" />
      {into && <path d="M2 12h6M5.5 9.5 8 12l-2.5 2.5" />}
      {out && <path d="M16 12h6M19.5 9.5 22 12l-2.5 2.5" />}
    </svg>
  );
}

// The picks in the URL.
export function useDependencyTreePicks(): DependencyTreePicks {
  const search: DependencySearch = useSearch({ strict: false });
  return {
    usedBy: idParam(search.used_by_id),
    part: idParam(search.selection_part_id),
    uses: idParam(search.uses_id),
  };
}

// What the trees' marks mean, in the pane's `?` (above the gestures).
export const DEPENDENCY_LEGEND =
  "With a node picked in one tree, the rows of the other trees that aren't connected to it are faded.";

// The gestures of the dependency trees, in the pane's `?`.
export const DEPENDENCY_GESTURES: Gesture[] = [
  { keys: "Click", action: "Pick: what it connects to stays dark elsewhere" },
  { keys: "Click again", action: "Drop the pick" },
  { keys: "Chevron", action: "Expand or collapse" },
  {
    keys: "Right-click",
    action: "Select (in the Selection tree), Expand all, Collapse all",
  },
  { keys: "Clear button, Esc", action: "Clear the selection" },
];

// The dependencies of the tree's selection (#77) as three trees, read left
// to right from source to target: what uses the selection (the sources of
// its incoming dependencies), the selection, reduced to its parts with a
// dependency, and what it uses (the targets of its outgoing dependencies).
// All are folded and labelled as the hierarchy tree is (#63). Picking a node
// in a tree keeps what it connects to dark in the others, the rest greyed, which stay
// complete: a user or a used node marks the parts of the selection connected
// to it (to both, when both are picked); a part marks its users and what it
// uses.
export function DependencyTrees() {
  const scope = useGraphScope();
  const search: DependencySearch = useSearch({ strict: false });
  const picks = useDependencyTreePicks();
  const selectedId = idParam(search.selected_id);
  if (selectedId === undefined) {
    return (
      <Hint>
        {search.selected_cycle_ids === undefined
          ? "Select a node in the hierarchy to see its dependencies."
          : "Select a single node in the hierarchy to see its dependencies."}
      </Hint>
    );
  }
  if (!scope) return null;
  return (
    <DependencyTreesFor scope={scope} selectedId={selectedId} picks={picks} />
  );
}

type DependencyTreesForProps = {
  scope: GraphScope;
  selectedId: string;
  picks: DependencyTreePicks;
};

function DependencyTreesFor({
  scope,
  selectedId,
  picks,
}: DependencyTreesForProps) {
  const query = useQuery(dependencySetQueryOptions(scope, selectedId));
  const navigate = useNavigate();
  const mode = useDirectionMode();
  const queryClient = useQueryClient();
  if (query.isPending) return <Hint>Loading the dependencies…</Hint>;
  if (query.isError) {
    return (
      <p className="text-status-error text-[13px]">
        The dependencies can't be loaded.
      </p>
    );
  }
  if (!query.data) {
    return <Hint>The selected node isn't in this hierarchy view.</Hint>;
  }
  const { selection, root, incoming, outgoing } = query.data;
  const name = treeNodeLabel(selection.type, selection.text, undefined);
  if (incoming === 0 && outgoing === 0) {
    return (
      <Hint>{`${name} has no dependencies with anything outside itself.`}</Hint>
    );
  }
  // Picking a picked node again drops it. One direction at a time: with both
  // shown, a pick on a side drops the other side's pick (a link may still
  // name both) and opens that side's tab of the dependency list, which it
  // narrows (`dependency_list`, the first tab without a param). With one
  // shown, the hidden side's pick stays for its return. The part stays: it
  // belongs to both directions.
  const pick = async (key: PickKey, id: string | undefined) => {
    const side =
      id === undefined || key === "part" || mode !== "both"
        ? {}
        : key === "usedBy"
          ? { [PICK_PARAMS.uses]: undefined, dependency_list: undefined }
          : { [PICK_PARAMS.usedBy]: undefined, dependency_list: "outgoing" };
    await navigate({
      to: ".",
      search: (previous: Record<string, unknown>) => ({
        ...previous,
        [PICK_PARAMS[key]]: id,
        ...side,
      }),
      replace: true,
    } as never);
  };
  // The Selection tree's menu makes a part the selection, as selecting it in
  // the hierarchy tree would: the picks and the trees' open rows go with the
  // old one, and the hierarchy tree opens down to the new one (its display
  // ancestors), so it shows there.
  const select = async (id: string) => {
    const path = await queryClient.fetchQuery(
      dependencyPickPathQueryOptions(scope, id),
    );
    await navigate({
      to: ".",
      search: (previous: Record<string, unknown>) => {
        const before = Array.isArray(previous.expanded_ids)
          ? (previous.expanded_ids as unknown[]).map(String)
          : [];
        return {
          ...previous,
          selected_id: id,
          selected_cycle_ids: undefined,
          expanded_ids: [...new Set([...before, ...path])],
          ...Object.fromEntries(
            [...Object.values(PICK_PARAMS), ...TREE_ROW_PARAMS].map((param) => [
              param,
              undefined,
            ]),
          ),
        };
      },
      replace: true,
    } as never);
  };
  // The selection, complete, as the hierarchy tree has it. Dark (the rest greyed), its
  // parts connected to the picked user (incoming) or the picked used node
  // (outgoing); to both when a link picks both.
  const partEmphasis: LevelSource[] = [];
  // Only from the sides shown.
  if (incoming > 0 && mode !== "outgoing" && picks.usedBy !== undefined)
    partEmphasis.push({
      direction: "incoming",
      picks: { source: picks.usedBy },
      side: "TARGET",
    });
  if (outgoing > 0 && mode !== "incoming" && picks.uses !== undefined)
    partEmphasis.push({
      direction: "outgoing",
      picks: { target: picks.uses },
      side: "SOURCE",
    });
  const base = { scope, selectionId: selection.id, picks, pick, select };
  return (
    <div className="flex h-full min-h-0 flex-col gap-2">
      <DependencyColumns
        // Another selection opens its trees afresh.
        key={selection.id}
        name={name}
        incoming={incoming}
        outgoing={outgoing}
        selection={selection}
        root={root}
        base={base}
        partEmphasis={partEmphasis}
      />
    </div>
  );
}

type DependencyColumnsProps = {
  name: string;
  incoming: number;
  outgoing: number;
  selection: DependencyNode;
  root: DependencyNode;
  base: Pick<
    TreeContext,
    "scope" | "selectionId" | "picks" | "pick" | "select"
  >;
  partEmphasis: LevelSource[];
};

// The three trees side by side.
function DependencyColumns({
  name,
  incoming,
  outgoing,
  selection,
  root,
  base,
  partEmphasis,
}: DependencyColumnsProps) {
  const { picks } = base;
  const usedBy = (
    <TreeColumn
      title="Used by"
      explanation={`The nodes outside ${name} whose types depend on types in ${name}.`}
    >
      {incoming === 0 ? (
        <Hint>
          {`Nothing outside ${name} depends on it.`}
          <OtherDirectionOffer
            counts={{ incoming, outgoing }}
            className="block pt-1"
          />
        </Hint>
      ) : (
        <DependencyTree
          {...base}
          pickKey="usedBy"
          sources={[{ direction: "incoming", picks: {}, side: "SOURCE" }]}
          emphasis={
            picks.part === undefined
              ? []
              : [
                  {
                    direction: "incoming",
                    picks: { target: picks.part },
                    side: "SOURCE",
                  },
                ]
          }
          top={root}
          showTop={false}
          label="Used by"
        />
      )}
    </TreeColumn>
  );
  const selectionColumn = (
    <TreeColumn
      title="Selection"
      explanation={`${name} and everything in it, as in the hierarchy.`}
    >
      <DependencyTree
        {...base}
        pickKey="part"
        sources="all"
        emphasis={partEmphasis}
        top={selection}
        showTop
        label="Selection"
      />
    </TreeColumn>
  );
  const uses = (
    <TreeColumn
      title="Uses"
      explanation={`The nodes outside ${name} that types in ${name} depend on.`}
    >
      {outgoing === 0 ? (
        <Hint>
          {`${name} depends on nothing outside itself.`}
          <OtherDirectionOffer
            counts={{ incoming, outgoing }}
            className="block pt-1"
          />
        </Hint>
      ) : (
        <DependencyTree
          {...base}
          pickKey="uses"
          sources={[{ direction: "outgoing", picks: {}, side: "TARGET" }]}
          emphasis={
            picks.part === undefined
              ? []
              : [
                  {
                    direction: "outgoing",
                    picks: { source: picks.part },
                    side: "TARGET",
                  },
                ]
          }
          top={root}
          showTop={false}
          label="Uses"
        />
      )}
    </TreeColumn>
  );
  // One direction: its side and the selection, half the pane each.
  const mode = useDirectionMode();
  return (
    <div
      className="grid min-h-0 flex-1 gap-1.5"
      style={{
        gridTemplateColumns:
          mode === "both"
            ? "minmax(0,1fr) auto minmax(0,1fr) auto minmax(0,1fr)"
            : "minmax(0,1fr) auto minmax(0,1fr)",
      }}
    >
      {mode !== "outgoing" && usedBy}
      {mode !== "outgoing" && <DirectionArrow />}
      {selectionColumn}
      {mode !== "incoming" && <DirectionArrow />}
      {mode !== "incoming" && uses}
    </div>
  );
}

type OtherDirectionOfferProps = {
  // The selection's dependencies in each direction.
  counts: Record<DependencyDirection, number>;
  className?: string;
};

// With one direction shown that has none, the way to the other one if it
// has some ("14 outgoing dependencies · Show"), so a focused page is never a
// dead end. Otherwise nothing.
export function OtherDirectionOffer({
  counts,
  className,
}: OtherDirectionOfferProps) {
  const mode = useDirectionMode();
  const choose = useChooseDirection();
  if (mode === "both" || counts[mode] > 0) return null;
  const other = mode === "incoming" ? "outgoing" : "incoming";
  const count = counts[other];
  if (count === 0) return null;
  return (
    <span className={className}>
      {`${count} ${other} ${count === 1 ? "dependency" : "dependencies"} · `}
      <button
        type="button"
        onClick={() => choose(other)}
        aria-label={`Show the ${other} dependencies`}
        className="focus-visible:ring-ring rounded-[3px] text-[var(--hg-accent)] outline-none hover:underline focus-visible:ring-2"
      >
        Show
      </button>
    </span>
  );
}

type HintProps = {
  children: ReactNode;
};

function Hint({ children }: HintProps) {
  return <p className="text-fg-muted text-[13px]">{children}</p>;
}

type TreeColumnProps = {
  title: string;
  // What the column holds, in a line under the title, and at length in the
  // title's tooltip.
  explanation: string;
  children: ReactNode;
};

// One tree under its title (what it holds, at length, as the title's
// tooltip), scrolling on its own; unframed, the
// pane is the frame.
function TreeColumn({ title, explanation, children }: TreeColumnProps) {
  return (
    <section
      aria-label={title}
      aria-description={explanation}
      className="flex h-full min-h-0 min-w-0 flex-col"
    >
      <div title={explanation} className="shrink-0 px-1 pb-1.5">
        <h3 className="text-fg-muted truncate text-[12px] font-medium">
          {title}
        </h3>
      </div>
      <div className="min-h-0 flex-1 overflow-auto">{children}</div>
    </section>
  );
}

// Between two columns, in the title row: the direction of the dependencies,
// from what depends to what it depends on.
function DirectionArrow() {
  return (
    // One line of the titles' text, the arrow centred on it.
    <div
      aria-hidden
      className="text-fg-subtle flex h-lh items-center justify-center text-[12px]"
    >
      <ArrowRight className="size-4" />
    </div>
  );
}

function isExternal(node: DependencyNode): boolean {
  return treeNodeKind(node.type, node.text) === "external";
}

// Whether the trees go below a node: not below a type (#77 stops at types).
function expandable(node: DependencyNode): boolean {
  const kind = treeNodeKind(node.type, node.text);
  return node.hasChildren && kind !== "type" && kind !== "member";
}

// Where a tree's levels come from: the children holding an end on `side` of
// the dependencies in the set of `direction`, narrowed by `picks`.
type LevelSource = {
  direction: DependencyDirection;
  picks: DependencyPicks;
  side: DependencySide;
};

type TreeContext = {
  scope: GraphScope;
  selectionId: string;
  // A level is the union of its sources' children, or with "all" every
  // child, as the hierarchy tree has them.
  sources: LevelSource[] | "all";
  // The rows connected to the picks in the other trees, the others greyed rather
  // than filtered: the children every one of `emphasis` yields.
  emphasis: LevelSource[];
  picks: DependencyTreePicks;
  pickKey: PickKey;
  pick: (key: PickKey, id: string | undefined) => Promise<void>;
  // Makes a node the selection (the Selection tree's menu).
  select: (id: string) => Promise<void>;
  open: Set<string>;
  toggle: (id: string) => Promise<void>;
  expandAll: (node: DependencyNode) => Promise<void>;
  collapseAll: (node: DependencyNode) => Promise<void>;
};

type DependencyTreeProps = Omit<
  TreeContext,
  "open" | "toggle" | "expandAll" | "collapseAll"
> & {
  // Where the tree starts: the selection, shown and open, or the root, whose
  // children are the top rows.
  top: DependencyNode;
  showTop: boolean;
  label: string;
};

// One dependency tree, loaded a level at a time as rows are opened. Its pick
// in the URL when it is shown (a link) is opened down to.
function DependencyTree({ top, showTop, label, ...rest }: DependencyTreeProps) {
  const [linked] = useState(() => rest.picks[rest.pickKey]);
  const path = useQuery({
    ...dependencyPickPathQueryOptions(rest.scope, linked ?? ""),
    enabled: linked !== undefined,
  });
  const search: Record<string, unknown> = useSearch({ strict: false });
  const navigate = useNavigate();
  const param = OPEN_PARAMS[rest.pickKey];
  const stored = Array.isArray(search[param])
    ? (search[param] as unknown[]).map(String)
    : undefined;
  // Without open rows in the URL, the rows down to the linked pick are open,
  // until the first toggle, which takes them over (closing every row leaves
  // no param, and must not open them again).
  const [toggled, setToggled] = useState(false);
  // The top is open, but for a shown one (the selection) that was closed.
  const topClosed = showTop && search[SELECTION_COLLAPSED_PARAM] === true;
  const open = new Set([
    ...(topClosed ? [] : [top.id]),
    ...(stored ?? (toggled || linked === undefined ? [] : (path.data ?? []))),
  ]);
  const queryClient = useQueryClient();
  // A row's children, as its level shows them: the hierarchy's, or the
  // union of the sources' (cached, the same queries as the levels').
  const childrenOf = async (node: DependencyNode) => {
    if (rest.sources === "all") {
      const level = await queryClient.fetchQuery(
        hierarchyTreeChildrenQueryOptions(
          rest.scope,
          node.id,
          ordersChildren(node.type, node.text),
        ),
      );
      return level.nodes;
    }
    const levels = await Promise.all(
      rest.sources.map((source) =>
        queryClient.fetchQuery(
          dependencySetChildrenQueryOptions(
            rest.scope,
            rest.selectionId,
            source.direction,
            source.picks,
            node.id,
            source.side,
          ),
        ),
      ),
    );
    return [
      ...new Map(levels.flat().map((child) => [child.id, child])).values(),
    ];
  };
  // The rows below `node` that can open, down to the types; with `onlyOpen`,
  // only through the open ones.
  const openableBelow = async (
    node: DependencyNode,
    onlyOpen: boolean,
  ): Promise<string[]> => {
    const inner = (await childrenOf(node)).filter(
      (child) => expandable(child) && (!onlyOpen || open.has(child.id)),
    );
    const deeper = await Promise.all(
      inner.map((child) => openableBelow(child, onlyOpen)),
    );
    return [...inner.map((child) => child.id), ...deeper.flat()];
  };
  const toggle = async (id: string) => {
    const next = new Set(open);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    await store(next);
  };
  // The row and everything in it, opened (loaded level by level, then
  // opened at once) or closed.
  const expandAll = async (node: DependencyNode) => {
    const below = await openableBelow(node, false);
    await store(new Set([...open, node.id, ...below]));
  };
  const collapseAll = async (node: DependencyNode) => {
    const closed = new Set([node.id, ...(await openableBelow(node, true))]);
    await store(new Set([...open].filter((id) => !closed.has(id))));
  };
  // The rows below the top in the URL; the shown top's state on its own.
  const store = async (next: Set<string>) => {
    const closed = showTop && !next.has(top.id);
    next.delete(top.id);
    setToggled(true);
    await navigate({
      to: ".",
      search: (previous: Record<string, unknown>) => ({
        ...previous,
        [param]: next.size > 0 ? [...next] : undefined,
        ...(showTop
          ? { [SELECTION_COLLAPSED_PARAM]: closed ? true : undefined }
          : {}),
      }),
      replace: true,
    } as never);
  };
  const context: TreeContext = {
    ...rest,
    open,
    toggle,
    expandAll,
    collapseAll,
  };
  return (
    <ul role="tree" aria-label={label}>
      {showTop ? (
        <Row
          context={context}
          node={top}
          label={treeNodeLabel(top.type, top.text, undefined)}
          depth={0}
          // The picks elsewhere connect to the selection.
          emphasized={rest.emphasis.length > 0}
        />
      ) : (
        <Level context={context} parent={top} depth={0} emphasized />
      )}
    </ul>
  );
}

type LevelProps = {
  context: TreeContext;
  parent: DependencyNode;
  depth: number;
  // Whether the parent is connected to the pick: only then can its children
  // be.
  emphasized: boolean;
};

// The children of an open row, from the tree's sources, in the hierarchy
// tree's order; labelled relative to the row as the hierarchy tree labels
// them.
function Level({ context, parent, depth, emphasized }: LevelProps) {
  const emphasis = emphasized ? context.emphasis : [];
  // The order of the hierarchy tree's level under the same parent (the same,
  // cached query): dependency order, a type's members in source order. The
  // top level is the root's.
  const order = useQuery(
    depth === 0
      ? hierarchyTreeRootQueryOptions(context.scope)
      : hierarchyTreeChildrenQueryOptions(
          context.scope,
          parent.id,
          ordersChildren(parent.type, parent.text),
        ),
  );
  const sources = context.sources === "all" ? [] : context.sources;
  const results = useQueries({
    queries: [...sources, ...emphasis].map((source) =>
      dependencySetChildrenQueryOptions(
        context.scope,
        context.selectionId,
        source.direction,
        source.picks,
        parent.id,
        source.side,
      ),
    ),
  });
  if (results.some((result) => !result.data) || !order.data) return null;
  const all = results.map((result) => result.data ?? []);
  const levels =
    context.sources === "all"
      ? [order.data.nodes]
      : all.slice(0, sources.length);
  const marks = all.slice(sources.length);
  const connected = new Set(
    (marks[0] ?? [])
      .filter((node) =>
        marks.every((level) => level.some((other) => other.id === node.id)),
      )
      .map((node) => node.id),
  );
  const position = new Map(
    order.data.nodes.map((node, index) => [node.id, index]),
  );
  const at = (node: DependencyNode) =>
    position.get(node.id) ?? Number.MAX_SAFE_INTEGER;
  const sorted = [
    ...new Map(levels.flat().map((node) => [node.id, node])).values(),
  ].sort((a, b) => at(a) - at(b));
  // As in the hierarchy tree, External Types goes last at the top level.
  const children =
    depth === 0
      ? [
          ...sorted.filter((node) => !isExternal(node)),
          ...sorted.filter(isExternal),
        ]
      : sorted;
  const rows = children.map((node) => (
    <Row
      key={node.id}
      context={context}
      node={node}
      label={treeNodeLabel(node.type, node.text, parent.text)}
      depth={depth}
      emphasized={connected.has(node.id)}
    />
  ));
  return depth === 0 ? <>{rows}</> : <ul role="group">{rows}</ul>;
}

type RowProps = {
  context: TreeContext;
  node: DependencyNode;
  label: string;
  depth: number;
  // Connected to the pick in another tree: not greyed.
  emphasized: boolean;
};

// A row: clicking it picks it (again: drops the pick); the chevron opens it.
function Row({ context, node, label, depth, emphasized }: RowProps) {
  const canOpen = expandable(node);
  const isOpen = canOpen && context.open.has(node.id);
  const picked = context.picks[context.pickKey] === node.id;
  const kind = treeNodeKind(node.type, node.text);
  const Chevron = isOpen ? ChevronDown : ChevronRight;
  const toggle = () => context.toggle(node.id);
  const pick = () =>
    context.pick(context.pickKey, picked ? undefined : node.id);
  const skipped = node.compactedFrom.map((package_) => package_.text);
  // In "Used by" and "Uses": how many of the selection's dependencies the
  // row holds (the hierarchy never shows a count, so the sides can't be
  // taken for it).
  const count = context.sources === "all" ? undefined : node.dependencies;
  const counted =
    count === undefined
      ? undefined
      : `${count} ${count === 1 ? "dependency" : "dependencies"}, ${node.references ?? 0} ${node.references === 1 ? "reference" : "references"}`;
  // A picked row is scrolled into view, e.g. when a link opened the tree
  // down to it.
  const rowRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (picked) rowRef.current?.scrollIntoView?.({ block: "nearest" });
  }, [picked]);
  return (
    <li
      role="treeitem"
      aria-level={depth + 1}
      aria-expanded={canOpen ? isOpen : undefined}
      aria-selected={picked}
      aria-label={label}
      data-connected={emphasized || undefined}
      aria-description={
        [counted, skipped.length > 0 && `folded from ${skipped.join(", ")}`]
          .filter(Boolean)
          .join("; ") || undefined
      }
    >
      <RowMenu
        label={label}
        canOpen={canOpen}
        // Only the Selection tree's rows can become the selection; the
        // selection itself can't.
        onSelect={
          context.pickKey === "part"
            ? node.id === context.selectionId
              ? null
              : () => context.select(node.id)
            : undefined
        }
        onExpandAll={() => context.expandAll(node)}
        onCollapseAll={() => context.collapseAll(node)}
      >
        <div
          ref={rowRef}
          // A pick is a filter, not the hierarchy's selection: marked with a
          // bar rather than filled.
          className={twMerge(
            "hover:bg-state-hover flex h-6 cursor-default items-center gap-1 pr-2 text-[13px]",
            picked &&
              "bg-state-selected-secondary-bg hover:bg-state-selected-secondary-bg shadow-[inset_3px_0_0_0_var(--hg-state-selected-secondary-bar)]",
            // With a pick in another tree, what isn't connected to it
            // fades, name, icon and count alike, so the connected rows
            // stand out; weight carries no state.
            context.emphasis.length > 0 && !emphasized && "opacity-35",
          )}
          style={{ paddingLeft: `${depth * 14 + 4}px` }}
          onClick={pick}
        >
          {canOpen ? (
            <button
              type="button"
              aria-label={isOpen ? `Collapse ${label}` : `Expand ${label}`}
              onClick={async (event) => {
                event.stopPropagation();
                await toggle();
              }}
              // Drawn 16 px; the pointer finds it in 24 px.
              className="text-fg-subtle relative flex size-4 shrink-0 items-center justify-center after:absolute after:-inset-1"
            >
              <Chevron className="size-3.5" />
            </button>
          ) : (
            <span className="size-4 shrink-0" />
          )}
          <NodeIcon
            type={node.type}
            text={node.text}
            aria-hidden
            className="text-fg-subtle size-3.5 shrink-0"
          />
          <span
            // As the hierarchy tree: the full name, and what a folded row
            // stands for.
            title={[
              node.text,
              skipped.length > 0 && `folded: ${skipped.join(", ")}`,
              emphasized && "connected to the node picked in another tree",
            ]
              .filter(Boolean)
              .join("\n")}
            className={twMerge(
              "truncate",
              (kind === "type" || kind === "package") &&
                "font-mono text-[12px]",
            )}
          >
            {label}
          </span>
          {count !== undefined && (
            <span
              title={counted}
              className="text-fg-muted ml-auto shrink-0 pl-2 text-[12px] tabular-nums"
            >
              {count}
            </span>
          )}
        </div>
      </RowMenu>
      {isOpen && (
        <Level
          context={context}
          parent={node}
          depth={depth + 1}
          emphasized={emphasized}
        />
      )}
    </li>
  );
}

type RowMenuProps = {
  label: string;
  canOpen: boolean;
  // Select, in the Selection tree; `null` disabled (the selection itself),
  // without it none.
  onSelect?: (() => Promise<void>) | null;
  onExpandAll: () => Promise<void>;
  onCollapseAll: () => Promise<void>;
  children: ReactNode;
};

// A row's context menu (right-click): in the Selection tree, make it the
// selection; open or close it with everything in it, down to the types. A
// row with nothing to offer has none.
function RowMenu({
  label,
  canOpen,
  onSelect,
  onExpandAll,
  onCollapseAll,
  children,
}: RowMenuProps) {
  if (!canOpen && onSelect === undefined) return children;
  return (
    <ContextMenu>
      <ContextMenuTrigger asChild>{children}</ContextMenuTrigger>
      <ContextMenuContent
        aria-label={`${label} commands`}
        onCloseAutoFocus={(event) => event.preventDefault()}
      >
        {onSelect !== undefined && (
          <ContextMenuItem
            disabled={onSelect === null}
            onSelect={onSelect ?? undefined}
          >
            <Crosshair />
            Select
          </ContextMenuItem>
        )}
        {onSelect !== undefined && canOpen && <ContextMenuSeparator />}
        {canOpen && (
          <ContextMenuItem onSelect={onExpandAll}>
            <ListChevronsUpDown />
            Expand all
          </ContextMenuItem>
        )}
        {canOpen && (
          <ContextMenuItem onSelect={onCollapseAll}>
            <ListChevronsDownUp />
            Collapse all
          </ContextMenuItem>
        )}
      </ContextMenuContent>
    </ContextMenu>
  );
}
