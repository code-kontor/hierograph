import { useQuery } from "@tanstack/react-query";
import { type ReactNode, useEffect, useRef, useState } from "react";

import type { GraphScope } from "@/frame/GraphScope";

import {
  type Arc,
  type ArcDependency,
  cycleDependencies,
  layoutArcs,
} from "./arcs";
import { hierarchyArcsQueryOptions } from "./queries";

// The width the arcs take directly to the right of the tree's labels: the
// arcs against the order next to the labels, then the anchor column, then the
// arcs with the order, reaching away from the tree. The rows keep this much
// room free, so the area always fits.
export const ARC_AREA_WIDTH = 180;
const ANCHOR_X = 40;
// The space between the arcs up (left) and the arcs down (right).
const SIDE_GAP = 15;
// Every arc has the same thickness, whatever its weight.
const ARC_STROKE = 1.25;
// The space between the widest label and the arc area.
const LABEL_GAP = 4;
const LAYOUT = {
  anchorX: ANCHOR_X,
  gap: SIDE_GAP,
  maxDown: ARC_AREA_WIDTH - ANCHOR_X - SIDE_GAP / 2 - 8,
  maxUp: ANCHOR_X - SIDE_GAP / 2 - 6,
};

// What the arcs are drawn for: a hovered row, its dependencies and its
// dependents; or a hovered cycle bar, the dependencies between the cycle's
// members (by id) that close it.
export type ArcFocus =
  { kind: "row"; id: string } | { kind: "cycle"; members: string[] };

// A visible row with arcs, as measured in the tree: its node, its label, the
// y of its middle, and its lineage: its own id and its ancestors' ids, up to
// the top level.
type MeasuredRow = { id: string; label: string; y: number; lineage: string[] };

type TreeArcsProps = {
  scope: GraphScope;
  // Only the arcs of what is hovered are drawn.
  hovered: ArcFocus | null;
  children: ReactNode;
};

// Draws the dependency arcs of the hovered row (or the hovered cycle) between
// the visible rows, over the tree's right edge. The rows are the elements
// marked `data-frontier` (see `TreeItem`); they're measured whenever the tree
// changes, and their dependencies are loaded for exactly that set of rows.
export function TreeArcs({ scope, hovered, children }: TreeArcsProps) {
  const ref = useRef<HTMLDivElement>(null);
  const [rows, setRows] = useState<MeasuredRow[]>([]);
  // Where the arc area starts: just after the widest visible label.
  const [left, setLeft] = useState(0);
  // The row (or cycle) whose arcs stay while the pointer is on one of them, so
  // an arc's tooltip can be read after leaving the row.
  const [pinned, setPinned] = useState<ArcFocus | null>(null);
  const shown = hovered ?? pinned;

  useEffect(() => {
    const container = ref.current;
    if (!container) return;
    let last = "";
    const measure = () => {
      const bounds = container.getBoundingClientRect();
      const top = bounds.top;
      const measured = Array.from(
        container.querySelectorAll<HTMLElement>("[data-frontier]"),
      ).map((element) => {
        const box = element.getBoundingClientRect();
        return {
          id: element.dataset.nodeId ?? "",
          label: element.dataset.label ?? "",
          y: Math.round(box.top - top + box.height / 2),
          lineage: lineageOf(element),
        };
      });
      const labelEnd = Math.max(
        0,
        ...Array.from(
          container.querySelectorAll<HTMLElement>("[data-tree-label]"),
        ).map((label) => label.getBoundingClientRect().right - bounds.left),
      );
      const start = Math.round(labelEnd + LABEL_GAP);
      const key = JSON.stringify([start, measured]);
      if (key !== last) {
        last = key;
        setRows(measured);
        setLeft(start);
      }
    };
    const mutations = new MutationObserver(measure);
    mutations.observe(container, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ["data-frontier"],
    });
    const resizes = new ResizeObserver(measure);
    resizes.observe(container);
    measure();
    return () => {
      mutations.disconnect();
      resizes.disconnect();
    };
  }, []);

  const ids = rows.map((row) => row.id);
  const query = useQuery({
    ...hierarchyArcsQueryOptions(scope, ids),
    enabled: ids.length >= 2,
  });
  const dependencies =
    shown === null ? [] : focusedDependencies(query.data ?? [], shown, rows);
  const arcs =
    ids.length >= 2 && shown !== null
      ? layoutArcs(rows, dependencies, LAYOUT)
      : [];
  const labels = new Map(rows.map((row) => [row.id, row.label]));

  const paths = arcs.map((arc) => (
    <ArcPath
      key={`${arc.from}>${arc.to}`}
      arc={arc}
      relation={relationOf(arc, shown)}
      fromLabel={labels.get(arc.from) ?? arc.from}
      toLabel={labels.get(arc.to) ?? arc.to}
      onEnter={() => setPinned(shown)}
      onLeave={() => setPinned(null)}
    />
  ));

  return (
    <div ref={ref} className="relative">
      {children}
      <svg
        role="group"
        aria-label="Dependencies"
        className="pointer-events-none absolute top-0 h-full overflow-visible"
        style={{ left: `${left}px` }}
        width={ARC_AREA_WIDTH}
      >
        {paths}
      </svg>
    </div>
  );
}

// The ids of a row's tree item and its ancestors' items, innermost first.
function lineageOf(element: HTMLElement): string[] {
  const lineage: string[] = [];
  let item = element.closest<HTMLElement>("[data-tree-node]");
  while (item) {
    lineage.push(item.dataset.treeNode ?? "");
    item = item.parentElement?.closest<HTMLElement>("[data-tree-node]") ?? null;
  }
  return lineage;
}

// The dependencies to draw for the focus: a row's from it and to it; a
// cycle's between its members.
function focusedDependencies(
  dependencies: ArcDependency[],
  focus: ArcFocus,
  rows: MeasuredRow[],
): ArcDependency[] {
  return focus.kind === "row"
    ? dependencies.filter(
        (dependency) =>
          dependency.from === focus.id || dependency.to === focus.id,
      )
    : cycleDependencies(dependencies, focus.members, rows);
}

type ArcRelation = "outgoing" | "incoming" | "cycle";

function relationOf(arc: Arc, focus: ArcFocus | null): ArcRelation {
  if (focus?.kind !== "row") return "cycle";
  return arc.from === focus.id ? "outgoing" : "incoming";
}

type ArcPathProps = {
  arc: Arc;
  // Seen from the hovered row: a dependency of it, or one on it; or one of a
  // hovered cycle.
  relation: ArcRelation;
  fromLabel: string;
  toLabel: string;
  onEnter: () => void;
  onLeave: () => void;
};

// The colour of an arc and its arrowhead, by its relation to what is hovered.
const RELATION_COLOURS: Record<ArcRelation, { line: string; head: string }> = {
  outgoing: {
    line: "stroke-[var(--hg-accent)]",
    head: "fill-[var(--hg-accent)]",
  },
  incoming: {
    line: "stroke-[var(--hg-fg-subtle)]",
    head: "fill-[var(--hg-fg-subtle)]",
  },
  cycle: {
    line: "stroke-[var(--hg-status-warning)]",
    head: "fill-[var(--hg-status-warning)]",
  },
};

// One dependency of the hovered row, all of one thickness, with an arrowhead
// on the row depended on: what the row uses (outgoing) blue, what uses it
// (incoming) grey, whichever way they run; one of a hovered cycle in the cycle
// bar's colour. Against the tree's order, dotted (and on the left of the
// anchors). A wider invisible stroke makes it easy to hover for its tooltip.
function ArcPath({
  arc,
  relation,
  fromLabel,
  toLabel,
  onEnter,
  onLeave,
}: ArcPathProps) {
  return (
    <g
      data-from={arc.from}
      data-to={arc.to}
      data-direction={arc.direction}
      data-relation={relation}
      data-weight={arc.weight}
      onMouseEnter={onEnter}
      onMouseLeave={onLeave}
    >
      <title>{`${fromLabel} → ${toLabel}: weight ${arc.weight}`}</title>
      <path
        d={arc.path}
        fill="none"
        className={RELATION_COLOURS[relation].line}
        strokeWidth={ARC_STROKE}
        // Against the order: dotted.
        strokeDasharray={arc.direction === "up" ? "0.5 3" : undefined}
        strokeLinecap="round"
      />
      <path
        data-arrowhead
        d={arc.head}
        className={RELATION_COLOURS[relation].head}
      />
      <path
        d={arc.path}
        fill="none"
        stroke="transparent"
        strokeWidth={8}
        style={{ pointerEvents: "stroke" }}
      />
    </g>
  );
}
