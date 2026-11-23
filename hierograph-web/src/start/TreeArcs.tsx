import { useQuery } from "@tanstack/react-query";
import { MousePointerClick, X } from "lucide-react";
import {
  createContext,
  type ReactNode,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";

import {
  DependencyArrow,
  DependencyArrowHeads,
  dependencyArrowWidth,
  type DependencyRole,
  useArrowHeadsId,
} from "@/design-system/graph/DependencyArrow";
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuShortcut,
  ContextMenuTrigger,
} from "@/design-system/ui/context-menu";
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
// The space between the widest label and the arc area.
const LABEL_GAP = 4;
const LAYOUT = {
  anchorX: ANCHOR_X,
  gap: SIDE_GAP,
  maxDown: ARC_AREA_WIDTH - ANCHOR_X - SIDE_GAP / 2 - 8,
  maxUp: ANCHOR_X - SIDE_GAP / 2 - 6,
};

// What the arcs are drawn for: a selected row, its dependencies and its
// dependents; or a selected cycle bar, the dependencies between the cycle's
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

// The selection, and the way to change it, for the context menus of an arc,
// a row or a cycle bar (`ArcFocusMenu` is a row's or a bar's).
type TreeSelection = {
  selected: ArcFocus | null;
  select: (focus: ArcFocus | null) => void;
  // A context menu opened or closed: an open one takes Escape.
  menuOpen: (open: boolean) => void;
};

const TreeSelectionContext = createContext<TreeSelection>({
  selected: null,
  select: () => {},
  menuOpen: () => {},
});

export function sameFocus(a: ArcFocus, b: ArcFocus): boolean {
  if (a.kind === "row") return b.kind === "row" && a.id === b.id;
  return b.kind === "cycle" && a.members.join() === b.members.join();
}

type TreeArcsProps = {
  scope: GraphScope;
  // Only the arcs of what is selected are drawn; `null` clears it.
  selected: ArcFocus | null;
  onSelect: (focus: ArcFocus | null) => void;
  children: ReactNode;
};

// Draws the dependency arcs of the selected row (or the selected cycle)
// between the visible rows, over the tree's right edge; for an expanded row,
// those of the rows inside it that cross its border, so expanding a selected
// row drills its arcs down. The rows are the elements marked `data-frontier`
// (see `TreeItem`); they're measured whenever the tree changes, and their
// dependencies are loaded for exactly that set of rows. An arc's context menu
// selects one of its ends, or clears the selection. Escape clears it too.
export function TreeArcs({
  scope,
  selected,
  onSelect,
  children,
}: TreeArcsProps) {
  const ref = useRef<HTMLDivElement>(null);
  const heads = useArrowHeadsId();
  const [rows, setRows] = useState<MeasuredRow[]>([]);
  // Where the arc area starts: just after the widest visible label.
  const [left, setLeft] = useState(0);
  // The tree's height, in pixels: the arc area's. A change of it resizes the
  // drawing, so the browser repaints all of it (with a relative height, some
  // browsers left stale pieces of arcs behind when the tree grew).
  const [height, setHeight] = useState(0);
  // Whether a context menu is open.
  const [menu, setMenu] = useState(false);
  const shown = selected;

  // Escape clears the selection, unless an open menu takes it: the menu marks
  // the Escape that closes it as handled, which can still arrive here once
  // it's closed.
  useEffect(() => {
    if (selected === null || menu) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !event.defaultPrevented) onSelect(null);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [selected, menu, onSelect]);

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
  const maxWeight = Math.max(1, ...arcs.map((arc) => arc.weight));

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
            maxWeight={maxWeight}
            heads={heads}
          />
        ));

  return (
    <TreeSelectionContext
      value={{ selected, select: onSelect, menuOpen: setMenu }}
    >
      <div ref={ref} className="relative">
        {children}
        {/* Only the arcs take the pointer: a click beside them reaches the
          row beneath. */}
        <svg
          role="group"
          aria-label="Dependencies"
          // Positioned inline: it must never take room in the tree, whose
          // height it takes.
          style={{
            position: "absolute",
            top: 0,
            left: `${left}px`,
            pointerEvents: "none",
          }}
          width={ARC_AREA_WIDTH}
          height={height}
        >
          <DependencyArrowHeads id={heads} />
          {paths}
        </svg>
      </div>
    </TreeSelectionContext>
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
  // Seen from the selected row: a dependency of it, or one on it; or one of
  // a selected cycle.
  relation: ArcRelation;
  from: MeasuredRow | undefined;
  to: MeasuredRow | undefined;
  // What the arcs are drawn for.
  focus: ArcFocus;
  // The heaviest drawn arc's weight.
  maxWeight: number;
  // The id of the arc area's arrowheads.
  heads: string;
};

// The arrow's role, by the arc's relation to what is selected.
const RELATION_ROLES: Record<ArcRelation, DependencyRole> = {
  outgoing: "uses",
  incoming: "usedBy",
  cycle: "feedback",
};

// One dependency of the selected row, drawn as every screen draws them: what
// the row uses (outgoing) in the accent, what uses it (incoming) grey,
// whichever way they run; one of a selected cycle in the cycle bar's colour;
// by its weight, against the heaviest drawn. Against the tree's order, dashed
// (and on the left of the anchors).
function ArcPath({
  arc,
  relation,
  from,
  to,
  focus,
  maxWeight,
  heads,
}: ArcPathProps) {
  const fromLabel = from?.label ?? arc.from;
  const toLabel = to?.label ?? arc.to;
  const role = RELATION_ROLES[relation];
  return (
    <ArcMenu focus={focus} arc={arc} from={from} to={to}>
      <DependencyArrow
        d={arc.path}
        role={role}
        against={arc.direction === "up"}
        width={dependencyArrowWidth(role, arc.weight, maxWeight, true)}
        heads={heads}
        title={`${fromLabel} → ${toLabel}: weight ${arc.weight}`}
        data-from={arc.from}
        data-to={arc.to}
        data-direction={arc.direction}
        data-relation={relation}
        data-weight={arc.weight}
      />
    </ArcMenu>
  );
}

type ArcMenuProps = {
  focus: ArcFocus;
  arc: Arc;
  from: MeasuredRow | undefined;
  to: MeasuredRow | undefined;
  children: ReactNode;
};

// An arc's menu: what it joins and how heavily, the selection to clear, and
// its ends to select instead.
function ArcMenu({ focus, arc, from, to, children }: ArcMenuProps) {
  const { select, menuOpen } = useContext(TreeSelectionContext);
  const ends = [from, to]
    .filter((end) => end !== undefined)
    .filter((end) => !(focus.kind === "row" && focus.id === end.id));
  const endItems = ends.map((end) => (
    <ContextMenuItem
      key={end.id}
      onSelect={() => select({ kind: "row", id: end.id })}
    >
      <MousePointerClick />
      <span className="truncate">Select {end.label}</span>
    </ContextMenuItem>
  ));
  return (
    <ContextMenu onOpenChange={menuOpen}>
      <ContextMenuTrigger asChild>{children}</ContextMenuTrigger>
      <ContextMenuContent onCloseAutoFocus={(event) => event.preventDefault()}>
        <div className="text-fg-subtle px-2 py-1 font-mono text-[11.5px]">
          {from?.label ?? arc.from} → {to?.label ?? arc.to} · {arc.weight}
        </div>
        <ContextMenuSeparator />
        <SelectItem focus={focus} name="Select" />
        {endItems}
      </ContextMenuContent>
    </ContextMenu>
  );
}

type ArcFocusMenuProps = {
  focus: ArcFocus;
  // The items after selecting, if any (a row's expand or collapse).
  items?: ReactNode;
  // The menu's trigger: an element that takes its props and ref.
  children: ReactNode;
};

// The context menu of a row or a cycle bar: selects it (or clears the
// selection), and whatever else it offers, each item with its gesture.
export function ArcFocusMenu({ focus, items, children }: ArcFocusMenuProps) {
  const { menuOpen } = useContext(TreeSelectionContext);
  return (
    <ContextMenu onOpenChange={menuOpen}>
      <ContextMenuTrigger asChild>{children}</ContextMenuTrigger>
      <ContextMenuContent onCloseAutoFocus={(event) => event.preventDefault()}>
        <SelectItem
          focus={focus}
          name={focus.kind === "cycle" ? "Select the cycle" : "Select"}
        />
        {items && (
          <>
            <ContextMenuSeparator />
            {items}
          </>
        )}
      </ContextMenuContent>
    </ContextMenu>
  );
}

type SelectItemProps = {
  focus: ArcFocus;
  // The item's name while the focus isn't selected.
  name: string;
};

// Selects the focus (instead of anything selected before), or clears the
// selection if it is the selected one.
function SelectItem({ focus, name }: SelectItemProps) {
  const { selected, select } = useContext(TreeSelectionContext);
  const isSelected = selected !== null && sameFocus(selected, focus);
  return (
    <ContextMenuItem
      onSelect={() => select(isSelected ? null : focus)}
      aria-keyshortcuts={isSelected ? "Escape" : undefined}
    >
      {isSelected ? <X /> : <MousePointerClick />}
      {isSelected ? "Clear selection" : name}
      <ContextMenuShortcut>{isSelected ? "Esc" : "Click"}</ContextMenuShortcut>
    </ContextMenuItem>
  );
}
