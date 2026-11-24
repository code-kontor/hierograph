import { useQuery } from "@tanstack/react-query";
import { EyeOff } from "lucide-react";
import { type ReactNode, useId, useState } from "react";
import { twMerge } from "tailwind-merge";

import { KIND_ICONS } from "@/design-system/graph/treeIcons";
import { treeNodeKind, treeNodeLabel } from "@/design-system/graph/treeLabels";
import { Notice } from "@/design-system/ui/notice";
import type { GraphScope } from "@/frame/GraphScope";
import { type ExcludedNode, viewExclusionsQueryOptions } from "@/frame/queries";

type ExcludedNodesProps = {
  scope: GraphScope;
};

// What the current hierarchy view excludes, below the graph's facts in the
// status panel's Graph tab: an info `Notice` (a choice of the view, not a
// problem) marked by the crossed-out eye, its list of the outermost excluded
// nodes, named and sorted as the tree names its rows, folded away until asked
// for. Nothing without a view, or for a view without exclusions.
export function ExcludedNodes({ scope }: ExcludedNodesProps) {
  const query = useQuery(viewExclusionsQueryOptions(scope));
  if (!query.data || scope.view === undefined) return null;
  const { count, nodes } = query.data;
  const items = labelled(nodes).map(({ node, label }) => (
    <ExcludedItem key={node.id} node={node} label={label} />
  ));
  return (
    <ExcludedBox count={count} outermost={nodes.length} view={scope.view}>
      {items}
    </ExcludedBox>
  );
}

type ExcludedBoxProps = {
  count: number;
  // How many nodes the list names: the outermost excluded ones.
  outermost: number;
  view: string;
  children: ReactNode;
};

// One line; the list, which can name hundreds of nodes, only on demand,
// scrolling on its own.
function ExcludedBox({ count, outermost, view, children }: ExcludedBoxProps) {
  const [open, setOpen] = useState(false);
  const listId = useId();
  return (
    <Notice
      variant="info"
      icon={EyeOff}
      label="Excluded by the view"
      title="Excluded"
      className="shrink-0"
    >
      <div className="flex flex-wrap items-baseline gap-x-2">
        <p className="text-fg-muted">
          The view <span className="text-fg font-mono">{view}</span> leaves out{" "}
          {plural(outermost, "node", "nodes")} with everything inside them (
          {plural(count, "node", "nodes")} in all) and every dependency to and
          from them.
        </p>
        <button
          type="button"
          aria-expanded={open}
          aria-controls={listId}
          onClick={() => setOpen(!open)}
          className="text-primary underline-offset-2 hover:underline"
        >
          {open ? "Hide them" : "Show them"}
        </button>
      </div>
      {open && (
        <ul id={listId} className="flex max-h-[40vh] flex-col overflow-y-auto">
          {children}
        </ul>
      )}
    </Notice>
  );
}

function plural(count: number, one: string, many: string): string {
  return `${count.toLocaleString("en")} ${count === 1 ? one : many}`;
}

// The nodes with the tree's names for them, sorted by name.
function labelled(nodes: ExcludedNode[]) {
  return nodes
    .map((node) => ({ node, label: treeNodeLabel(node.type, node.text, "") }))
    .sort((a, b) => a.label.localeCompare(b.label));
}

type ExcludedItemProps = {
  node: ExcludedNode;
  label: string;
};

function ExcludedItem({ node, label }: ExcludedItemProps) {
  const kind = treeNodeKind(node.type, node.text);
  const Icon = KIND_ICONS[kind];
  return (
    <li
      // The full name, where the label shortens it (a module's coordinates).
      title={`${node.text} (excluded)`}
      className="flex items-center gap-1 py-px"
    >
      <Icon aria-hidden className="text-fg-subtle size-3.5 shrink-0" />
      <span
        className={twMerge(
          "truncate",
          (kind === "type" || kind === "member" || kind === "package") &&
            "font-mono text-[12px]",
        )}
      >
        {label}
      </span>
    </li>
  );
}
