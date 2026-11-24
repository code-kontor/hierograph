import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, EyeOff } from "lucide-react";
import { twMerge } from "tailwind-merge";

import type { GraphScope } from "@/frame/GraphScope";

import { countLabel } from "./format";
import {
  type ExcludedNode,
  type ViewExclusions,
  viewExclusionsQueryOptions,
} from "./queries";
import { KIND_ICONS } from "./treeIcons";
import { type TreeNodeKind, treeNodeKind, treeNodeLabel } from "./treeLabels";

type ExcludedNodesProps = {
  scope: GraphScope;
  open: boolean;
  onToggle: () => Promise<void>;
};

// What the current view leaves out, below the tree: one line with the counts,
// opened to the outermost excluded nodes, named and sorted as the tree names
// its rows. They aren't in the view, so they aren't rows of the tree: no
// selection, no arcs, no menu. Nothing without a view, or for a view without
// exclusions.
export function ExcludedNodes({ scope, open, onToggle }: ExcludedNodesProps) {
  const query = useQuery(viewExclusionsQueryOptions(scope));
  if (!query.data) return null;
  const exclusions = query.data;
  const Chevron = open ? ChevronDown : ChevronRight;
  return (
    <section
      aria-label="Left out by this view"
      className="border-border mt-1.5 shrink-0 border-t pt-1"
    >
      <button
        type="button"
        aria-expanded={open}
        onClick={onToggle}
        className="text-fg-muted hover:bg-state-hover hover:text-fg flex w-full items-center gap-1 rounded-[4px] px-1 py-[3px] text-left text-[12px]"
      >
        <Chevron aria-hidden className="size-3.5 shrink-0" />
        <EyeOff aria-hidden className="size-3.5 shrink-0" />
        <span>Left out by this view: {summary(exclusions)}</span>
      </button>
      {open && <ExcludedList nodes={exclusions.nodes} />}
    </section>
  );
}

// The excluded nodes' kind in the summary, when they all have one of these.
const KIND_NOUNS: Partial<Record<TreeNodeKind, [string, string]>> = {
  module: ["module", "modules"],
  package: ["package", "packages"],
  type: ["type", "types"],
};

// `3 modules, 1,234 nodes in all`: what the exclusions select, and everything
// that went with them.
function summary({ count, nodes }: ViewExclusions): string {
  const kinds = new Set(
    nodes.map((node) => treeNodeKind(node.type, node.text)),
  );
  const [singular, plural] =
    (kinds.size === 1 ? KIND_NOUNS[[...kinds][0]] : undefined) ??
    (["node", "nodes"] as const);
  const selected = countLabel(nodes.length, singular, plural);
  return `${selected}, ${countLabel(count, "node", "nodes")} in all`;
}

type ExcludedListProps = {
  nodes: ExcludedNode[];
};

function ExcludedList({ nodes }: ExcludedListProps) {
  const items = labelled(nodes).map(({ node, label }) => (
    <ExcludedItem key={node.id} node={node} label={label} />
  ));
  return <ul className="py-0.5">{items}</ul>;
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
      title={node.text}
      className="text-fg-muted flex items-center gap-1 py-[3px] pl-[22px] text-[13px]"
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
