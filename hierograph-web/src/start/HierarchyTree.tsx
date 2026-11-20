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
import { useRef, useState } from "react";
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
  ARC_AREA_WIDTH,
  type ArcFocus,
  ArcFocusMenu,
  TreeArcs,
} from "./TreeArcs";
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

// What every row needs from the tree: which nodes are open, and what is
// hovered (a row or a cycle bar), whose dependency arcs are drawn.
type TreeControls = {
  expanded: ReadonlySet<string>;
  toggle: (id: string) => void;
  hover: (focus: ArcFocus | null) => void;
};

// The hierarchy of the current hierarchy view (or the scanned hierarchy
// without one) as a tree, loaded level by level as nodes are expanded. Each
// level's siblings are in dependency order (those depending on the others on
// top), its dependency cycles marked. The tree ends at the types: their
// members' dependencies aren't resolved yet, so they would have no arcs.
// Beside the tree, arcs show the dependencies between the visible rows.
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
  const [hovered, setHovered] = useState<ArcFocus | null>(null);
  const clearHover = useRef<ReturnType<typeof setTimeout>>(undefined);
  const controls: TreeControls = {
    expanded,
    toggle(id) {
      const next = new Set(expanded);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      setExpanded(next);
    },
    // Leaving a row clears the hover a moment later, so the pointer can move
    // into the arc area (which keeps the arcs) without them vanishing.
    hover(focus) {
      if (clearHover.current) clearTimeout(clearHover.current);
      clearHover.current = undefined;
      if (focus !== null) setHovered(focus);
      else clearHover.current = setTimeout(() => setHovered(null), 150);
    },
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
    <TreeArcs scope={scope} hovered={hovered}>
      <TreeLevel
        role="tree"
        scope={scope}
        level={query.data}
        parentText=""
        depth={0}
        controls={controls}
      />
    </TreeArcs>
  );
}

type CycleBarProps = {
  mark: CycleMark;
  depth: number;
  // The row the bar segment is on, hovered again when the pointer leaves the
  // bar for the rest of the row.
  row: string;
  controls: TreeControls;
};

// One segment of the bar along a cycle's block, at the nodes' indent; the
// block's ends are inset, so two adjacent cycles read as two. Hovering it
// draws the arcs that close the cycle, and its context menu pins them; a hit
// area wider than the bar makes it easy to hit.
function CycleBar({ mark, depth, row, controls }: CycleBarProps) {
  return (
    <ArcFocusMenu focus={{ kind: "cycle", members: mark.ids }}>
      <span
        aria-hidden
        data-cycle-bar
        onMouseEnter={() =>
          controls.hover({ kind: "cycle", members: mark.ids })
        }
        onMouseLeave={() => controls.hover({ kind: "row", id: row })}
        // The bar's menu, not the row's.
        onContextMenu={(event) => event.stopPropagation()}
        className="absolute flex w-2 justify-center"
        style={{
          left: `${depth * 14 - 3}px`,
          top: mark.first ? "4px" : "0",
          bottom: mark.last ? "4px" : "0",
        }}
      >
        <span className="bg-status-warning w-0.5 rounded-full" />
      </span>
    </ArcFocusMenu>
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
  controls: TreeControls;
};

// Where a node sits in a cycle's contiguous block, to draw the block's bar.
type CycleMark = {
  first: boolean;
  last: boolean;
  // The other members, by label, for the row's description.
  others: string[];
  // All members, by id, for the arcs.
  ids: string[];
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
    const cycleLabels = cycle.map((id) => labels.get(id) ?? id);
    cycle.forEach((id, index) => {
      marks.set(id, {
        first: index === 0,
        last: index === cycle.length - 1,
        others: cycleLabels.filter((_, other) => other !== index),
        ids: cycle,
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
  controls,
}: TreeLevelProps) {
  const nodes = level.nodes;
  const marks = cycleMarks(level, parentText);
  // At the top level, External Types (the library types the code uses, not a
  // module or group of its own) goes last, below a separator: everything
  // depends on it, so the arcs to it run with the order.
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
      controls={controls}
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
  controls: TreeControls;
};

function TreeItem({
  scope,
  node,
  separated,
  cycle,
  parentText,
  depth,
  controls,
}: TreeItemProps) {
  const kind = treeNodeKind(node.type, node.text);
  // A type's members aren't shown: their dependencies aren't resolved yet.
  const expandable = node.hasChildren && kind !== "type";
  const open = expandable && controls.expanded.has(node.id);
  const children = useQuery({
    ...hierarchyTreeChildrenQueryOptions(
      scope,
      node.id,
      ordersChildren(node.type, node.text),
    ),
    enabled: open,
  });
  const Icon = KIND_ICONS[kind];
  const Chevron = open ? ChevronDown : ChevronRight;
  const label = treeNodeLabel(node.type, node.text, parentText);
  // A row with arcs: its children aren't shown (an open node still loading
  // them counts as closed, so the arcs don't flicker).
  const frontier = !(open && children.isSuccess);
  // A view group's children are named relative to the level above the group:
  // `app` below `locations` › `application`.
  const childParentText = kind === "group" ? parentText : node.text;
  return (
    <li
      role="treeitem"
      aria-expanded={expandable ? open : undefined}
      aria-label={label}
      // No tooltip: it would cover the arcs.
      aria-description={
        cycle
          ? `in a dependency cycle with ${cycle.others.join(", ")}`
          : undefined
      }
      data-cycle={cycle ? "true" : undefined}
      data-tree-node={node.id}
      className={twMerge(separated && "border-border mt-1 border-t pt-1")}
    >
      {/* Its context menu pins the row's arcs. */}
      <ArcFocusMenu focus={{ kind: "row", id: node.id }}>
        <button
          type="button"
          onClick={(event) => {
            // A click in the cycle bar's menu reaches the row too (the menu
            // is a portal), but isn't on it.
            if (!event.currentTarget.contains(event.target as Node)) return;
            if (expandable) controls.toggle(node.id);
          }}
          onMouseEnter={() => controls.hover({ kind: "row", id: node.id })}
          onMouseLeave={() => controls.hover(null)}
          data-node-id={node.id}
          data-label={label}
          data-frontier={frontier ? "true" : undefined}
          style={{ paddingLeft: `${depth * 14 + 4}px` }}
          // Not selectable: a right click (for the row's menu) would select
          // the word under the pointer.
          className={twMerge(
            "hover:bg-state-hover relative flex w-full items-center gap-1 rounded-[4px] py-[3px] text-left text-[13px] select-none",
            !expandable && "cursor-default",
          )}
        >
          {cycle && (
            <CycleBar
              mark={cycle}
              depth={depth}
              row={node.id}
              controls={controls}
            />
          )}
          <span className="text-fg-subtle flex size-3.5 shrink-0 items-center justify-center">
            {expandable && <Chevron className="size-3.5" />}
          </span>
          <Icon
            aria-hidden
            className={twMerge(
              "text-fg-subtle size-3.5 shrink-0",
              kind === "group" && "text-primary",
            )}
          />
          <span
            data-tree-label
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
              // Ends the label: the arcs start after it.
              data-tree-label
              className="text-status-warning size-3.5 shrink-0"
            />
          )}
          {/* The labels stop short of the arcs. */}
          <span
            aria-hidden
            className="shrink-0"
            style={{ width: `${ARC_AREA_WIDTH}px` }}
          />
        </button>
      </ArcFocusMenu>
      {open && (
        <TreeChildren
          scope={scope}
          query={children}
          childParentText={childParentText}
          depth={depth + 1}
          controls={controls}
        />
      )}
    </li>
  );
}

type TreeChildrenProps = {
  scope: GraphScope;
  query: { isPending: boolean; isError: boolean; data?: TreeLevelData };
  childParentText: string;
  depth: number;
  controls: TreeControls;
};

function TreeChildren({
  scope,
  query,
  childParentText,
  depth,
  controls,
}: TreeChildrenProps) {
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
  if (query.isPending || !query.data) {
    return (
      <p
        className="text-fg-muted py-[3px] text-[12px]"
        style={{ paddingLeft: `${depth * 14 + 22}px` }}
      >
        Loading…
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
      controls={controls}
    />
  );
}
