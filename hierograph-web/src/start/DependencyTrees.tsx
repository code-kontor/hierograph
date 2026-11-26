import { useQueries, useQuery } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { ArrowRight, ChevronDown, ChevronRight } from "lucide-react";
import { type ReactNode, useEffect, useRef, useState } from "react";
import { twMerge } from "tailwind-merge";

import { KIND_ICONS } from "@/design-system/graph/treeIcons";
import {
  ordersChildren,
  treeNodeKind,
  treeNodeLabel,
} from "@/design-system/graph/treeLabels";
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

// The picks in the URL.
export function useDependencyTreePicks(): DependencyTreePicks {
  const search: DependencySearch = useSearch({ strict: false });
  return {
    usedBy: idParam(search.used_by_id),
    part: idParam(search.selection_part_id),
    uses: idParam(search.uses_id),
  };
}

// The dependencies of the tree's selection (#77) as three trees, read left
// to right from source to target: what uses the selection (the sources of
// its incoming dependencies), the selection, reduced to its parts with a
// dependency, and what it uses (the targets of its outgoing dependencies).
// All are folded and labelled as the hierarchy tree is (#63). Picking a node
// in a tree sets what it connects to in bold in the others, which stay
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
  // Picking a picked node again drops it. One direction at a time: a pick
  // on a side drops the other side's pick (a link may still name both) and
  // opens that side's tab of the dependency list, which it narrows
  // (`dependency_list`, the first tab without a param). The part stays: it
  // belongs to both directions.
  const pick = async (key: PickKey, id: string | undefined) => {
    const side =
      id === undefined || key === "part"
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
  // The selection, complete, as the hierarchy tree has it. In bold, its
  // parts connected to the picked user (incoming) or the picked used node
  // (outgoing); to both when a link picks both.
  const partEmphasis: LevelSource[] = [];
  if (incoming > 0 && picks.usedBy !== undefined)
    partEmphasis.push({
      direction: "incoming",
      picks: { source: picks.usedBy },
      side: "TARGET",
    });
  if (outgoing > 0 && picks.uses !== undefined)
    partEmphasis.push({
      direction: "outgoing",
      picks: { target: picks.uses },
      side: "SOURCE",
    });
  const base = { scope, selectionId: selection.id, picks, pick };
  const anyPick =
    picks.usedBy !== undefined ||
    picks.part !== undefined ||
    picks.uses !== undefined;
  return (
    <div className="flex h-full min-h-0 flex-col gap-2">
      {anyPick && (
        <p className="text-fg-muted shrink-0 text-[12px]">
          <span className="font-semibold">Bold</span>: connected to the node
          picked in another tree. Click a picked row again to drop it.
        </p>
      )}
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
  base: Pick<TreeContext, "scope" | "selectionId" | "picks" | "pick">;
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
  return (
    <div
      className="grid min-h-0 flex-1 gap-1.5"
      style={{
        gridTemplateColumns:
          "minmax(0,1fr) auto minmax(0,1fr) auto minmax(0,1fr)",
      }}
    >
      <TreeColumn
        tinted
        title="Used by"
        caption={`depends on ${name}`}
        explanation={`The nodes outside ${name} whose types depend on types in ${name}.`}
      >
        {incoming === 0 ? (
          <Hint>{`Nothing outside ${name} depends on it.`}</Hint>
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
      <DirectionArrow />
      <TreeColumn
        tinted={false}
        title="Selection"
        caption={`${name}, all of it`}
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
      <DirectionArrow />
      <TreeColumn
        tinted
        title="Uses"
        caption={`${name} depends on it`}
        explanation={`The nodes outside ${name} that types in ${name} depend on.`}
      >
        {outgoing === 0 ? (
          <Hint>{`${name} depends on nothing outside itself.`}</Hint>
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
    </div>
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
  caption: string;
  explanation: string;
  // A side: the nodes outside the selection, on a tint, so they aren't
  // taken for the hierarchy; the selection stays white, as the hierarchy.
  tinted: boolean;
  children: ReactNode;
};

// One tree under its title and caption, scrolling on its own; unframed, the
// pane is the frame.
function TreeColumn({
  title,
  caption,
  explanation,
  tinted,
  children,
}: TreeColumnProps) {
  return (
    <section
      aria-label={title}
      aria-description={explanation}
      data-tinted={tinted || undefined}
      className={twMerge(
        "flex h-full min-h-0 min-w-0 flex-col",
        tinted && "bg-panel-header rounded-[6px] px-1 pt-1",
      )}
    >
      <div title={explanation} className="shrink-0 px-1 pb-1.5">
        <h3 className="text-fg-muted truncate font-mono text-[11px] tracking-[0.06em] uppercase">
          {title}
        </h3>
        <p className="text-fg-subtle truncate text-[11px]">{caption}</p>
      </div>
      <div className="min-h-0 flex-1 overflow-auto">{children}</div>
    </section>
  );
}

// Between two columns, in the title row: the direction of the dependencies,
// from what depends to what it depends on.
function DirectionArrow() {
  return (
    <div aria-hidden className="text-fg-subtle flex justify-center pt-1.5">
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
  // The rows connected to the picks in the other trees, shown in bold rather
  // than filtered: the children every one of `emphasis` yields.
  emphasis: LevelSource[];
  picks: DependencyTreePicks;
  pickKey: PickKey;
  pick: (key: PickKey, id: string | undefined) => Promise<void>;
  open: Set<string>;
  toggle: (id: string) => void;
};

type DependencyTreeProps = Omit<TreeContext, "open" | "toggle"> & {
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
  const [toggled, setToggled] = useState<Set<string> | null>(null);
  // Until the first toggle, the rows down to the linked pick are open too;
  // the first toggle takes them over.
  const open =
    toggled ??
    new Set([top.id, ...(linked !== undefined ? (path.data ?? []) : [])]);
  const toggle = (id: string) => {
    const next = new Set(open);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setToggled(next);
  };
  const context: TreeContext = { ...rest, open, toggle };
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
          separated={false}
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
  // As in the hierarchy tree, External Types goes last at the top level,
  // below a separator.
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
      separated={depth === 0 && isExternal(node) && children.length > 1}
    />
  ));
  return depth === 0 ? <>{rows}</> : <ul role="group">{rows}</ul>;
}

type RowProps = {
  context: TreeContext;
  node: DependencyNode;
  label: string;
  depth: number;
  // Connected to the pick in another tree: bold.
  emphasized: boolean;
  // Below a separator: External Types at the top level.
  separated: boolean;
};

// A row: clicking it picks it (again: drops the pick); the chevron opens it.
function Row({ context, node, label, depth, emphasized, separated }: RowProps) {
  const canOpen = expandable(node);
  const isOpen = canOpen && context.open.has(node.id);
  const picked = context.picks[context.pickKey] === node.id;
  const kind = treeNodeKind(node.type, node.text);
  const Icon = KIND_ICONS[kind];
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
      className={twMerge(separated && "border-border mt-1 border-t pt-1")}
      aria-description={
        [counted, skipped.length > 0 && `folded from ${skipped.join(", ")}`]
          .filter(Boolean)
          .join("; ") || undefined
      }
    >
      <div
        ref={rowRef}
        // A pick is a filter, not the hierarchy's selection: marked with a
        // bar rather than filled.
        className={twMerge(
          "hover:bg-state-hover flex h-6 cursor-default items-center gap-1 pr-2 text-[13px]",
          picked &&
            "bg-state-selected-secondary-bg hover:bg-state-selected-secondary-bg text-state-selected-fg shadow-[inset_3px_0_0_0_var(--hg-state-selected-secondary-bar)]",
        )}
        style={{ paddingLeft: `${depth * 14 + 4}px` }}
        onClick={pick}
      >
        {canOpen ? (
          <button
            type="button"
            aria-label={isOpen ? `Collapse ${label}` : `Expand ${label}`}
            onClick={(event) => {
              event.stopPropagation();
              toggle();
            }}
            className="text-fg-subtle flex size-4 shrink-0 items-center justify-center"
          >
            <Chevron className="size-3.5" />
          </button>
        ) : (
          <span className="size-4 shrink-0" />
        )}
        <Icon aria-hidden className="text-fg-subtle size-3.5 shrink-0" />
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
            (kind === "type" || kind === "package") && "font-mono text-[12px]",
            emphasized && "font-semibold",
          )}
        >
          {label}
        </span>
        {count !== undefined && (
          <span
            title={counted}
            className="text-fg-subtle ml-auto shrink-0 pl-2 text-[11px] tabular-nums"
          >
            {count}
          </span>
        )}
      </div>
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
