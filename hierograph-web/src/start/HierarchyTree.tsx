import { useQuery } from "@tanstack/react-query";
import {
  ChevronDown,
  ChevronRight,
  CircleDot,
  FileCode,
  Folder,
  Globe,
  Layers,
  type LucideIcon,
  Package,
  Repeat2,
} from "lucide-react";
import { useState } from "react";
import { twMerge } from "tailwind-merge";

import { Pane } from "@/design-system/layout/Pane";
import { Message } from "@/design-system/ui/message";
import { type GraphScope, useGraphScope } from "@/frame/GraphScope";

import {
  hierarchyTreeChildrenQueryOptions,
  hierarchyTreeRootQueryOptions,
  type TreeLevelData,
  type TreeNode,
} from "./queries";
import {
  ordersChildren,
  type TreeNodeKind,
  treeNodeKind,
  treeNodeLabel,
} from "./treeLabels";

const KIND_ICONS: Record<TreeNodeKind, LucideIcon> = {
  group: Layers,
  module: Package,
  external: Globe,
  package: Folder,
  type: FileCode,
  member: CircleDot,
  other: CircleDot,
};

// The hierarchy of the current hierarchy view (or the scanned hierarchy
// without one) as a tree, loaded level by level as nodes are expanded. Each
// level's siblings are in dependency order (those depending on the others on
// top), its dependency cycles marked; a type's members are in source order.
export function HierarchyTree() {
  const scope = useGraphScope();
  return (
    // The view is named on its card and in the header already.
    <Pane title="Hierarchy" bodyClassName="p-1.5">
      {scope ? (
        // A new view starts collapsed.
        <TreeRoot key={scope.view ?? ""} scope={scope} />
      ) : (
        <p className="text-fg-muted p-1.5 text-[13px]">
          No graph is loaded, so there is no hierarchy to show yet.
        </p>
      )}
    </Pane>
  );
}

type TreeRootProps = {
  scope: GraphScope;
};

function TreeRoot({ scope }: TreeRootProps) {
  const query = useQuery(hierarchyTreeRootQueryOptions(scope));
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  const toggle = (id: string) => {
    const next = new Set(expanded);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setExpanded(next);
  };

  if (query.isPending) {
    return <Message variant="loading" title="Loading the hierarchy…" />;
  }
  if (query.isError) {
    return (
      <Message variant="error" title="The hierarchy can't be loaded">
        {query.error.message}
      </Message>
    );
  }
  return (
    <TreeLevel
      role="tree"
      scope={scope}
      level={query.data}
      parentText=""
      depth={0}
      expanded={expanded}
      toggle={toggle}
    />
  );
}

type CycleBarProps = {
  mark: CycleMark;
  depth: number;
};

// One segment of the bar along a cycle's block, at the nodes' indent; the
// block's ends are inset, so two adjacent cycles read as two.
function CycleBar({ mark, depth }: CycleBarProps) {
  return (
    <span
      aria-hidden
      className="bg-status-warning absolute w-0.5 rounded-full"
      style={{
        left: `${depth * 14}px`,
        top: mark.first ? "4px" : "0",
        bottom: mark.last ? "4px" : "0",
      }}
    />
  );
}

function isExternal(node: TreeNode): boolean {
  return treeNodeKind(node.type, node.text) === "external";
}

type TreeLevelProps = {
  role: "tree" | "group";
  scope: GraphScope;
  level: TreeLevelData;
  parentText: string;
  depth: number;
  expanded: ReadonlySet<string>;
  toggle: (id: string) => void;
};

// Where a node sits in a cycle's contiguous block, to draw the block's bar.
type CycleMark = {
  first: boolean;
  last: boolean;
  // The other members, by label, for the tooltip.
  others: string[];
};

function cycleMarks(
  level: TreeLevelData,
  parentText: string,
): Map<string, CycleMark> {
  const labels = new Map(
    level.nodes.map((node) => [
      node.id,
      treeNodeLabel(node.type, node.text, parentText),
    ]),
  );
  const marks = new Map<string, CycleMark>();
  for (const cycle of level.cycles) {
    cycle.forEach((id, index) => {
      marks.set(id, {
        first: index === 0,
        last: index === cycle.length - 1,
        others: cycle
          .filter((other) => other !== id)
          .map((other) => labels.get(other) ?? other),
      });
    });
  }
  return marks;
}

function TreeLevel({
  role,
  scope,
  level,
  parentText,
  depth,
  expanded,
  toggle,
}: TreeLevelProps) {
  const nodes = level.nodes;
  const marks = cycleMarks(level, parentText);
  // At the top level, External Types (the library types the code uses, not a
  // module or group of its own) goes last, below a separator.
  const ordered =
    depth === 0
      ? [
          ...nodes.filter((node) => !isExternal(node)),
          ...nodes.filter(isExternal),
        ]
      : nodes;
  const items = ordered.map((node) => (
    <TreeItem
      key={node.id}
      scope={scope}
      node={node}
      separated={depth === 0 && isExternal(node) && ordered.length > 1}
      cycle={marks.get(node.id)}
      parentText={parentText}
      depth={depth}
      expanded={expanded}
      toggle={toggle}
    />
  ));
  return (
    <ul
      role={role}
      aria-label={role === "tree" ? "Hierarchy" : undefined}
      className="flex flex-col"
    >
      {items}
    </ul>
  );
}

type TreeItemProps = {
  scope: GraphScope;
  node: TreeNode;
  separated: boolean;
  cycle: CycleMark | undefined;
  parentText: string;
  depth: number;
  expanded: ReadonlySet<string>;
  toggle: (id: string) => void;
};

function TreeItem({
  scope,
  node,
  separated,
  cycle,
  parentText,
  depth,
  expanded,
  toggle,
}: TreeItemProps) {
  const label = treeNodeLabel(node.type, node.text, parentText);
  const title = cycle
    ? `${node.text}\nIn a dependency cycle with ${cycle.others.join(", ")}`
    : node.text;
  const open = node.hasChildren && expanded.has(node.id);
  const kind = treeNodeKind(node.type, node.text);
  const Icon = KIND_ICONS[kind];
  const Chevron = open ? ChevronDown : ChevronRight;
  return (
    <li
      role="treeitem"
      aria-expanded={node.hasChildren ? open : undefined}
      aria-label={label}
      aria-description={cycle ? "in a dependency cycle" : undefined}
      data-cycle={cycle ? "true" : undefined}
      className={twMerge(separated && "border-border mt-1 border-t pt-1")}
    >
      <button
        type="button"
        onClick={() => {
          if (node.hasChildren) toggle(node.id);
        }}
        title={title}
        style={{ paddingLeft: `${depth * 14 + 4}px` }}
        className={twMerge(
          "hover:bg-state-hover relative flex w-full items-center gap-1 rounded-[4px] py-[3px] pr-1.5 text-left text-[13px]",
          !node.hasChildren && "cursor-default",
        )}
      >
        {cycle && <CycleBar mark={cycle} depth={depth} />}
        <span className="text-fg-subtle flex size-3.5 shrink-0 items-center justify-center">
          {node.hasChildren && <Chevron className="size-3.5" />}
        </span>
        <Icon
          aria-hidden
          className={twMerge(
            "text-fg-subtle size-3.5 shrink-0",
            kind === "group" && "text-primary",
          )}
        />
        <span
          className={twMerge(
            "truncate",
            (kind === "type" || kind === "member" || kind === "package") &&
              "font-mono text-[12px]",
          )}
        >
          {label}
        </span>
        {cycle && (
          <Repeat2
            aria-hidden
            className="text-status-warning size-3.5 shrink-0"
          />
        )}
      </button>
      {open && (
        <TreeChildren
          scope={scope}
          node={node}
          // A view group's children are named relative to the level above
          // the group: `app` below `locations` › `application`.
          childParentText={kind === "group" ? parentText : node.text}
          depth={depth + 1}
          expanded={expanded}
          toggle={toggle}
        />
      )}
    </li>
  );
}

type TreeChildrenProps = {
  scope: GraphScope;
  node: TreeNode;
  childParentText: string;
  depth: number;
  expanded: ReadonlySet<string>;
  toggle: (id: string) => void;
};

function TreeChildren({
  scope,
  node,
  childParentText,
  depth,
  expanded,
  toggle,
}: TreeChildrenProps) {
  const query = useQuery(
    hierarchyTreeChildrenQueryOptions(
      scope,
      node.id,
      ordersChildren(node.type, node.text),
    ),
  );
  if (query.isPending) {
    return (
      <p
        className="text-fg-muted py-[3px] text-[12px]"
        style={{ paddingLeft: `${depth * 14 + 22}px` }}
      >
        Loading…
      </p>
    );
  }
  if (query.isError) {
    return (
      <p
        className="text-status-error py-[3px] text-[12px]"
        style={{ paddingLeft: `${depth * 14 + 22}px` }}
      >
        The children can't be loaded.
      </p>
    );
  }
  return (
    <TreeLevel
      role="group"
      scope={scope}
      level={query.data}
      parentText={childParentText}
      depth={depth}
      expanded={expanded}
      toggle={toggle}
    />
  );
}
