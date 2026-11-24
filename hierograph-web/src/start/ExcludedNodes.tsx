import { useQuery } from "@tanstack/react-query";
import { EyeOff } from "lucide-react";
import type { ReactNode } from "react";
import { twMerge } from "tailwind-merge";

import { Notice } from "@/design-system/ui/notice";
import type { GraphScope } from "@/frame/GraphScope";

import { formatCount } from "./format";
import { type ExcludedNode, viewExclusionsQueryOptions } from "./queries";
import { KIND_ICONS } from "./treeIcons";
import { treeNodeKind, treeNodeLabel } from "./treeLabels";

type ExcludedNodesProps = {
  scope: GraphScope;
};

// What the current hierarchy view excludes, below the graph's facts in the
// Graph tab: an info `Notice` (a choice of the view, not a problem) marked by
// the crossed-out eye, with the outermost
// excluded nodes named and sorted as the tree names its rows. The graph's
// counts above it stay the scanned graph's. Nothing without a view, or for a
// view without exclusions.
export function ExcludedNodes({ scope }: ExcludedNodesProps) {
  const query = useQuery(viewExclusionsQueryOptions(scope));
  if (!query.data || scope.view === undefined) return null;
  const { count, nodes } = query.data;
  const items = labelled(nodes).map(({ node, label }) => (
    <ExcludedItem key={node.id} node={node} label={label} />
  ));
  return (
    <ExcludedBox count={count} view={scope.view}>
      {items}
    </ExcludedBox>
  );
}

type ExcludedBoxProps = {
  count: number;
  view: string;
  children: ReactNode;
};

function ExcludedBox({ count, view, children }: ExcludedBoxProps) {
  return (
    <Notice
      variant="info"
      icon={EyeOff}
      label="Excluded by the view"
      title="Excluded"
    >
      <p className="text-fg-muted">
        The view <span className="text-fg font-mono">{view}</span> leaves these
        out, with everything inside them ({formatCount(count)}{" "}
        {count === 1 ? "node" : "nodes"} in all) and every dependency to and
        from them.
      </p>
      <ul className="flex flex-col">{children}</ul>
    </Notice>
  );
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
