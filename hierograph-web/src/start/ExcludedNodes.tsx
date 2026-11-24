import { useQuery } from "@tanstack/react-query";
import { EyeOff } from "lucide-react";
import type { ReactNode } from "react";
import { twMerge } from "tailwind-merge";

import type { GraphScope } from "@/frame/GraphScope";

import { countLabel, formatCount } from "./format";
import { type ExcludedNode, viewExclusionsQueryOptions } from "./queries";
import { KIND_ICONS } from "./treeIcons";
import { type TreeNodeKind, treeNodeKind, treeNodeLabel } from "./treeLabels";

type ExcludedNodesProps = {
  scope: GraphScope;
};

// What the current hierarchy view excludes, below the graph's facts in the
// Graph tab: a box of its own, marked as excluded (the crossed-out eye, the
// info colours: a choice of the view, not a problem), with the outermost
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
    <>
      {/* The same thin line as above the workspace's note on what isn't compiled. */}
      <hr className="border-border" />
      <ExcludedBox count={count} nodes={nodes} view={scope.view}>
        {items}
      </ExcludedBox>
    </>
  );
}

type ExcludedBoxProps = {
  count: number;
  nodes: ExcludedNode[];
  view: string;
  children: ReactNode;
};

function ExcludedBox({ count, nodes, view, children }: ExcludedBoxProps) {
  return (
    <section
      aria-label="Excluded by the view"
      className="border-status-info bg-status-info-bg flex flex-col gap-1.5 rounded-[6px] border px-2.5 py-2"
    >
      <h3 className="flex items-center gap-1.5">
        <EyeOff aria-hidden className="text-status-info size-4 shrink-0" />
        <span className="text-status-info text-[13px] font-semibold tracking-[0.06em] uppercase">
          Excluded
        </span>
        <span className="border-status-info text-status-info rounded-[4px] border px-1 text-[11px] font-medium">
          {selectedLabel(nodes)}
        </span>
      </h3>
      <p className="text-fg-muted text-[12px]">
        The view <span className="text-fg font-mono">{view}</span> leaves these
        out, with everything inside them ({formatCount(count)}{" "}
        {count === 1 ? "node" : "nodes"} in all) and every dependency to and
        from them.
      </p>
      <ul className="flex flex-col">{children}</ul>
    </section>
  );
}

// The excluded nodes' kind in the count, when they all have one of these.
const KIND_NOUNS: Partial<Record<TreeNodeKind, [string, string]>> = {
  module: ["module", "modules"],
  package: ["package", "packages"],
  type: ["type", "types"],
};

// `74 modules`: what the exclusions select, by kind when they share one.
function selectedLabel(nodes: ExcludedNode[]): string {
  const kinds = new Set(
    nodes.map((node) => treeNodeKind(node.type, node.text)),
  );
  const [singular, plural] =
    (kinds.size === 1 ? KIND_NOUNS[[...kinds][0]] : undefined) ??
    (["node", "nodes"] as const);
  return countLabel(nodes.length, singular, plural);
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
      className="flex items-center gap-1 py-[2px] text-[13px]"
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
