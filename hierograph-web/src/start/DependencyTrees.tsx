import { useQuery } from "@tanstack/react-query";
import { useSearch } from "@tanstack/react-router";
import { ChevronDown, ChevronRight } from "lucide-react";
import { type ReactNode, useState } from "react";
import { twMerge } from "tailwind-merge";

import { KIND_ICONS } from "@/design-system/graph/treeIcons";
import { treeNodeKind, treeNodeLabel } from "@/design-system/graph/treeLabels";
import { type GraphScope, useGraphScope } from "@/frame/GraphScope";

import {
  type DependencyDirection,
  dependencyEndsQueryOptions,
  type DependencyNode,
  dependencyTreeChildrenQueryOptions,
  type DependencyTreeMode,
} from "./queries";

type DependencyTreesProps = {
  direction: DependencyDirection;
};

// The dependencies of the tree's selection in one direction (#77), as two
// trees side by side: the selection, reduced to the nodes with a dependency
// in that direction, and the nodes on the other end of those dependencies,
// in their hierarchy. Both are folded and labelled as the hierarchy tree is
// (#63): the server folds, a level at a time.
export function DependencyTrees({ direction }: DependencyTreesProps) {
  const scope = useGraphScope();
  const search: { selected_id?: unknown; selected_cycle_ids?: unknown } =
    useSearch({ strict: false });
  const what =
    direction === "incoming" ? "what depends on it" : "what it depends on";
  if (search.selected_id === undefined || search.selected_id === null) {
    return (
      <Hint>
        {search.selected_cycle_ids === undefined
          ? `Select a node in the hierarchy to see ${what}.`
          : `Select a single node in the hierarchy to see ${what}.`}
      </Hint>
    );
  }
  if (!scope) return null;
  return (
    <DependencyTreesFor
      scope={scope}
      selectedId={String(search.selected_id)}
      direction={direction}
    />
  );
}

type DependencyTreesForProps = {
  scope: GraphScope;
  selectedId: string;
  direction: DependencyDirection;
};

function DependencyTreesFor({
  scope,
  selectedId,
  direction,
}: DependencyTreesForProps) {
  const query = useQuery(
    dependencyEndsQueryOptions(scope, selectedId, direction),
  );
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
  const { selection, root, ends } = query.data;
  const name = treeNodeLabel(selection.type, selection.text, undefined);
  if (ends.length === 0) {
    return (
      <Hint>
        {direction === "incoming"
          ? `Nothing outside ${name} depends on it.`
          : `${name} depends on nothing outside itself.`}
      </Hint>
    );
  }
  const base = { scope, selectionId: selection.id, direction, ends };
  const selectionTree = (
    <TreeColumn title="Selection">
      <DependencyTree
        {...base}
        mode={direction === "incoming" ? "used" : "using"}
        top={selection}
        showTop
      />
    </TreeColumn>
  );
  const endsTree = (
    <TreeColumn title={direction === "incoming" ? "Used by" : "Uses"}>
      <DependencyTree {...base} mode="holding" top={root} showTop={false} />
    </TreeColumn>
  );
  // The dependencies read left to right: what uses the selection, then the
  // selection (incoming); the selection, then what it uses (outgoing).
  return (
    <div className="grid h-full min-h-0 grid-cols-2 gap-4">
      {direction === "incoming" ? endsTree : selectionTree}
      {direction === "incoming" ? selectionTree : endsTree}
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
  children: ReactNode;
};

// One of the two trees, under its title, scrolling on its own; unframed, the
// pane is the frame.
function TreeColumn({ title, children }: TreeColumnProps) {
  return (
    <section aria-label={title} className="flex min-h-0 min-w-0 flex-col">
      <h3 className="text-fg-muted shrink-0 px-1 pb-1 font-mono text-[11px] tracking-[0.06em] uppercase">
        {title}
      </h3>
      <div className="min-h-0 flex-1 overflow-auto">{children}</div>
    </section>
  );
}

// Whether the trees go below a node: not below a type (#77 stops at types).
function expandable(node: DependencyNode): boolean {
  const kind = treeNodeKind(node.type, node.text);
  return node.hasChildren && kind !== "type" && kind !== "member";
}

type TreeContext = {
  scope: GraphScope;
  selectionId: string;
  direction: DependencyDirection;
  ends: string[];
  mode: DependencyTreeMode;
  open: Set<string>;
  toggle: (id: string) => void;
};

type DependencyTreeProps = Omit<TreeContext, "open" | "toggle"> & {
  // Where the tree starts: the selection, shown and open, or the root, whose
  // children are the top rows.
  top: DependencyNode;
  showTop: boolean;
};

// One dependency tree, loaded a level at a time as rows are opened.
function DependencyTree({ top, showTop, ...rest }: DependencyTreeProps) {
  const [open, setOpen] = useState<Set<string>>(() => new Set([top.id]));
  const toggle = (id: string) => {
    const next = new Set(open);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setOpen(next);
  };
  const context: TreeContext = { ...rest, open, toggle };
  const label = showTop
    ? "Selection"
    : rest.direction === "incoming"
      ? "Used by"
      : "Uses";
  return (
    <ul role="tree" aria-label={label}>
      {showTop ? (
        <Row
          context={context}
          node={top}
          label={treeNodeLabel(top.type, top.text, undefined)}
          depth={0}
        />
      ) : (
        <Level context={context} parent={top} depth={0} />
      )}
    </ul>
  );
}

type LevelProps = {
  context: TreeContext;
  parent: DependencyNode;
  depth: number;
};

// The children of an open row, in the tree's mode, sorted by name; labelled
// relative to the row as the hierarchy tree labels them.
function Level({ context, parent, depth }: LevelProps) {
  const query = useQuery(
    dependencyTreeChildrenQueryOptions(
      context.scope,
      context.selectionId,
      context.direction,
      context.mode,
      parent.id,
      context.ends,
    ),
  );
  if (!query.data) return null;
  const rows = query.data
    .map((node) => ({
      node,
      label: treeNodeLabel(node.type, node.text, parent.text),
    }))
    .sort((a, b) => a.label.localeCompare(b.label))
    .map(({ node, label }) => (
      <Row
        key={node.id}
        context={context}
        node={node}
        label={label}
        depth={depth}
      />
    ));
  return depth === 0 ? <>{rows}</> : <ul role="group">{rows}</ul>;
}

type RowProps = {
  context: TreeContext;
  node: DependencyNode;
  label: string;
  depth: number;
};

function Row({ context, node, label, depth }: RowProps) {
  const canOpen = expandable(node);
  const isOpen = canOpen && context.open.has(node.id);
  const kind = treeNodeKind(node.type, node.text);
  const Icon = KIND_ICONS[kind];
  const Chevron = isOpen ? ChevronDown : ChevronRight;
  const toggle = () => context.toggle(node.id);
  const skipped = node.compactedFrom.map((package_) => package_.text);
  return (
    <li
      role="treeitem"
      aria-level={depth + 1}
      aria-expanded={canOpen ? isOpen : undefined}
      aria-label={label}
      aria-description={
        skipped.length > 0 ? `folded from ${skipped.join(", ")}` : undefined
      }
    >
      <div
        className="hover:bg-state-hover flex h-6 cursor-default items-center gap-1 pr-2 text-[13px]"
        style={{ paddingLeft: `${depth * 14 + 4}px` }}
        onClick={canOpen ? toggle : undefined}
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
          title={
            skipped.length > 0
              ? `${node.text}\nfolded: ${skipped.join(", ")}`
              : node.text
          }
          className={twMerge(
            "truncate",
            (kind === "type" || kind === "package") && "font-mono text-[12px]",
          )}
        >
          {label}
        </span>
      </div>
      {isOpen && <Level context={context} parent={node} depth={depth + 1} />}
    </li>
  );
}
