import { useQuery } from "@tanstack/react-query";
import { Pin, PinOff } from "lucide-react";
import {
  type ComponentProps,
  createContext,
  type ReactNode,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import { twMerge } from "tailwind-merge";

import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuTrigger,
} from "@/design-system/ui/context-menu";
import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";
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
// y of its middle, and its lineage: its own id and its
// ancestors' ids, up to the top level.
type MeasuredRow = {
  id: string;
  label: string;
  y: number;
  lineage: string[];
};

// Pinned arcs stay whatever is hovered, until unpinned. Pinning works from the
// context menu of an arc, a row or a cycle bar; `ArcFocusMenu` is the menu of
// a row or bar.
type ArcPinning = {
  pinned: ArcFocus | null;
  pin: (focus: ArcFocus | null) => void;
  // A context menu opened (with the focus its arcs belong to) or closed.
  menuFor: (focus: ArcFocus | null) => void;
};

const ArcPinningContext = createContext<ArcPinning>({
  pinned: null,
  pin: () => {},
  menuFor: () => {},
});

function sameFocus(a: ArcFocus, b: ArcFocus): boolean {
  if (a.kind === "row") return b.kind === "row" && a.id === b.id;
  return b.kind === "cycle" && a.members.join() === b.members.join();
}

type TreeArcsProps = {
  scope: GraphScope;
  // Only the arcs of what is hovered are drawn.
  hovered: ArcFocus | null;
  children: ReactNode;
};

// Draws the dependency arcs of the hovered row (or the hovered cycle) between
// the visible rows, over the tree's right edge; for an expanded row, those of
// the rows inside it that cross its border, so expanding a row with pinned
// arcs drills them down. The rows are the elements
// marked `data-frontier` (see `TreeItem`); they're measured whenever the tree
// changes, and their dependencies are loaded for exactly that set of rows.
// An arc's context menu pins the arcs.
export function TreeArcs({ scope, hovered, children }: TreeArcsProps) {
  const ref = useRef<HTMLDivElement>(null);
  const area = useRef<SVGSVGElement>(null);
  const [rows, setRows] = useState<MeasuredRow[]>([]);
  // Where the arc area starts: just after the widest visible label.
  const [left, setLeft] = useState(0);
  // The tree's height, in pixels: the arc area's. A change of it resizes the
  // drawing, so the browser repaints all of it (with a relative height, some
  // browsers left stale pieces of arcs behind when the tree grew).
  const [height, setHeight] = useState(0);
  // The row (or cycle) whose arcs stay while the pointer is in the arc area,
  // so an arc's tooltip can be read after leaving the row.
  const [held, setHeld] = useState<ArcFocus | null>(null);
  // The arcs an open context menu belongs to.
  const [menu, setMenu] = useState<ArcFocus | null>(null);
  // The arcs pinned from the context menu, until unpinned.
  const [pinned, setPinned] = useState<ArcFocus | null>(null);
  const shown = pinned ?? menu ?? hovered ?? held;

  // Escape unpins, unless an open menu takes it: the menu marks the Escape
  // that closes it as handled, which can still arrive here once it's closed.
  useEffect(() => {
    if (pinned === null || menu !== null) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !event.defaultPrevented) setPinned(null);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [pinned, menu]);

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
      const tall = Math.ceil(bounds.height);
      const key = JSON.stringify([start, tall, measured]);
      if (key !== last) {
        last = key;
        setRows(measured);
        setLeft(start);
        setHeight(tall);
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
  const byId = new Map(rows.map((row) => [row.id, row]));

  // While a menu is open its arcs stay; once it closes, they stay as long as
  // the pointer is in the arc area, which the next move tells.
  const menuFor = (focus: ArcFocus | null) => {
    if (focus !== null) {
      setMenu(focus);
      return;
    }
    setHeld(menu);
    setMenu(null);
    document.addEventListener(
      "pointermove",
      (event) => {
        if (!area.current?.contains(event.target as Node)) setHeld(null);
      },
      { once: true },
    );
  };

  // Arcs are only drawn for a focus.
  const focus = shown;
  const paths =
    focus === null
      ? []
      : arcs.map((arc) => (
          <ArcPath
            key={`${arc.from}>${arc.to}`}
            arc={arc}
            relation={relationOf(arc, shown, rows)}
            from={byId.get(arc.from)}
            to={byId.get(arc.to)}
            focus={focus}
          />
        ));

  return (
    <ArcPinningContext value={{ pinned, pin: setPinned, menuFor }}>
      <div ref={ref} className="relative">
        {children}
        {/* The arc area takes the pointer over its whole height, so moving
          along the arcs never hovers the rows beneath them. */}
        <svg
          ref={area}
          role="group"
          aria-label="Dependencies"
          // Positioned inline: it must never take room in the tree, whose
          // height it takes.
          style={{ position: "absolute", top: 0, left: `${left}px` }}
          width={ARC_AREA_WIDTH}
          height={height}
          onMouseEnter={() => setHeld(shown)}
          onMouseLeave={() => setHeld(null)}
        >
          {paths}
        </svg>
        {pinned !== null && (
          <button
            type="button"
            aria-label="Unpin the arcs"
            title="Unpin the arcs (Esc)"
            onClick={() => setPinned(null)}
            className={twMerge(
              ghostIconTriggerClassName,
              "absolute top-0 size-5",
            )}
            style={{ left: `${left + ARC_AREA_WIDTH - 20}px` }}
          >
            <PinOff className="size-3.5" />
          </button>
        )}
      </div>
    </ArcPinningContext>
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

// The visible rows a focused row stands for: the row itself, or, once it is
// expanded, the visible rows inside it.
function rowsInside(id: string, rows: MeasuredRow[]): Set<string> {
  return new Set(
    rows.filter((row) => row.lineage.includes(id)).map((row) => row.id),
  );
}

// The dependencies to draw for the focus: a row's from it and to it, drilled
// down to its visible rows once it is expanded (those crossing its border);
// a cycle's between its members.
function focusedDependencies(
  dependencies: ArcDependency[],
  focus: ArcFocus,
  rows: MeasuredRow[],
): ArcDependency[] {
  if (focus.kind === "cycle") {
    return cycleDependencies(dependencies, focus.members, rows);
  }
  const inside = rowsInside(focus.id, rows);
  return dependencies.filter(
    (dependency) => inside.has(dependency.from) !== inside.has(dependency.to),
  );
}

type ArcRelation = "outgoing" | "incoming" | "cycle";

function relationOf(
  arc: Arc,
  focus: ArcFocus | null,
  rows: MeasuredRow[],
): ArcRelation {
  if (focus?.kind !== "row") return "cycle";
  return rowsInside(focus.id, rows).has(arc.from) ? "outgoing" : "incoming";
}

type ArcPathProps = {
  arc: Arc;
  // Seen from the hovered row: a dependency of it, or one on it; or one of a
  // hovered cycle.
  relation: ArcRelation;
  from: MeasuredRow | undefined;
  to: MeasuredRow | undefined;
  // What the arcs are drawn for.
  focus: ArcFocus;
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
// anchors). A wider invisible stroke makes it easy to hover for its tooltip
// and to right-click for its menu.
function ArcPath({ arc, relation, from, to, focus }: ArcPathProps) {
  const fromLabel = from?.label ?? arc.from;
  const toLabel = to?.label ?? arc.to;
  return (
    <ArcFocusMenu focus={focus}>
      <ArcShape
        arc={arc}
        relation={relation}
        title={`${fromLabel} → ${toLabel}: weight ${arc.weight}`}
      />
    </ArcFocusMenu>
  );
}

type ArcFocusMenuProps = {
  focus: ArcFocus;
  // The menu's trigger: an element that takes its props and ref.
  children: ReactNode;
};

// The context menu of an arc, a row or a cycle bar: pins (or unpins) the
// arcs.
export function ArcFocusMenu({ focus, children }: ArcFocusMenuProps) {
  const { menuFor } = useContext(ArcPinningContext);
  return (
    <ContextMenu onOpenChange={(open) => menuFor(open ? focus : null)}>
      <ContextMenuTrigger asChild>{children}</ContextMenuTrigger>
      <ContextMenuContent onCloseAutoFocus={(event) => event.preventDefault()}>
        <PinItem focus={focus} />
      </ContextMenuContent>
    </ContextMenu>
  );
}

type PinItemProps = {
  focus: ArcFocus;
};

// Pins the focus's arcs (instead of any pinned before), or unpins them if
// they are the pinned ones.
function PinItem({ focus }: PinItemProps) {
  const { pinned, pin } = useContext(ArcPinningContext);
  const isPinned = pinned !== null && sameFocus(pinned, focus);
  return (
    <ContextMenuItem onSelect={() => pin(isPinned ? null : focus)}>
      {isPinned ? <PinOff /> : <Pin />}
      {isPinned ? "Unpin the arcs" : "Pin these arcs"}
    </ContextMenuItem>
  );
}

type ArcShapeProps = ComponentProps<"g"> & {
  arc: Arc;
  relation: ArcRelation;
  title: string;
};

// The drawn arc, with its arrowhead and its tooltip. It passes the menu
// trigger's props and ref on to its group.
function ArcShape({ arc, relation, title, ...props }: ArcShapeProps) {
  return (
    <g
      {...props}
      data-from={arc.from}
      data-to={arc.to}
      data-direction={arc.direction}
      data-relation={relation}
      data-weight={arc.weight}
    >
      <title>{title}</title>
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
