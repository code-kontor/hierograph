import { useQuery } from "@tanstack/react-query";
import { MousePointerClick, X } from "lucide-react";
import {
  createContext,
  type CSSProperties,
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
import { CopyDependencySubmenu } from "@/frame/CopyMenu";
import type { GraphScope } from "@/frame/GraphScope";

import {
  type Arc,
  type ArcDependency,
  arcExtent,
  type ArcRow,
  cycleDependencies,
  layoutArcs,
} from "./arcs";
import { useDirectionMode } from "./DependencyTrees";
import { hierarchyArcsQueryOptions } from "./queries";

// The arcs sit directly to the right of the tree's labels: the arcs against
// the order next to the labels, then the anchor column, then the arcs with
// the order, reaching away from the tree. The area is as wide as the arcs
// drawn need (none without a selection), and the rows keep that much room
// free (`--arc-gutter`), but never so much that a label gets narrower than
// `MIN_LABEL`: then the arcs reach out less.
// The space between the arcs up (left) and the arcs down (right).
const SIDE_GAP = 15;
// How far the arcs up and down reach out at most.
const MAX_UP = 26;
const MAX_DOWN = 124;
// Room beside the outermost arcs: for the arcs' strokes on the left, for
// the arrowheads on the right.
const UP_ROOM = 6;
const DOWN_ROOM = 8;
// The space between the widest label and the arc area.
const LABEL_GAP = 4;
// The narrowest a label gets for the arcs.
const MIN_LABEL = 120;
// The arc area's width, however little room there is.
const MIN_AREA = 24;

// Where the anchor column goes, and the area's width, for arcs that reach
// out `extent` (as `arcExtent` has it).
function arcArea(extent: { up: number; down: number }) {
  const anchorX = (extent.up > 0 ? extent.up + UP_ROOM : 2) + SIDE_GAP / 2;
  const width =
    anchorX + SIDE_GAP / 2 + (extent.down > 0 ? extent.down + DOWN_ROOM : 4);
  return { anchorX, width: Math.ceil(width) };
}

// The arcs laid out in at most `room` pixels: as far out as they like, or,
// when that is too wide, reaching out proportionally less.
// What the selected row uses and what uses it get lanes of their own
// (`groupOf`).
function fitArcs(
  rows: ArcRow[],
  dependencies: ArcDependency[],
  room: number,
  groupOf: (dependency: ArcDependency) => string,
): { arcs: Arc[]; width: number } {
  const lay = (scale: number, anchorX: number) =>
    layoutArcs(
      rows,
      dependencies,
      {
        anchorX,
        gap: SIDE_GAP,
        maxDown: MAX_DOWN * scale,
        maxUp: MAX_UP * scale,
      },
      groupOf,
    );
  const natural = arcArea(arcExtent(lay(1, 0)));
  const scale = natural.width > room ? Math.max(0.1, room / natural.width) : 1;
  const area = scale === 1 ? natural : arcArea(arcExtent(lay(scale, 0)));
  return { arcs: lay(scale, area.anchorX), width: area.width };
}

// What the arcs are drawn for: the selected rows, one or a group taken
// together, the dependencies crossing the group's border; or a selected
// cycle bar, the dependencies between the cycle's members (by id) that close
// it.
export type ArcFocus =
  { kind: "rows"; ids: string[] } | { kind: "cycle"; members: string[] };

// One row, selected alone.
export function rowFocus(id: string): ArcFocus {
  return { kind: "rows", ids: [id] };
}

// Whether the row is one of the selected rows.
export function focusHolds(focus: ArcFocus | null, id: string): boolean {
  return focus?.kind === "rows" && focus.ids.includes(id);
}

// A visible row with arcs, as measured in the tree: its node, its label, the
// y of its middle, and its lineage: its own id and its
// ancestors' ids, up to the top level.
type MeasuredRow = {
  id: string;
  label: string;
  // The node's qualified name and kind, to copy.
  text: string;
  type: string;
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

// The same rows, in whatever order they were selected; the same cycle.
export function sameFocus(a: ArcFocus, b: ArcFocus): boolean {
  if (a.kind === "rows") {
    return (
      b.kind === "rows" && [...a.ids].sort().join() === [...b.ids].sort().join()
    );
  }
  return b.kind === "cycle" && a.members.join() === b.members.join();
}

type TreeArcsProps = {
  scope: GraphScope;
  // Only the arcs of what is selected are drawn; `null` clears it.
  selected: ArcFocus | null;
  onSelect: (focus: ArcFocus | null) => void;
  children: ReactNode;
};

// Draws the dependency arcs of the selected rows (or the selected cycle)
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
  // The most the arc area may take, so that every label keeps `MIN_LABEL`.
  const [room, setRoom] = useState(MIN_AREA);
  // Whether a context menu is open.
  const [menu, setMenu] = useState(false);
  // The row under the pointer: its arcs are drawn at full strength.
  const [hovered, setHovered] = useState<string | null>(null);
  const hover = (event: { target: EventTarget }) => {
    const row =
      event.target instanceof Element
        ? event.target.closest<HTMLElement>("[data-node-id]")
        : null;
    setHovered(row?.dataset.nodeId ?? null);
  };
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
          text: element.dataset.text ?? "",
          type: element.dataset.type ?? "",
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
      // Where the deepest label starts.
      const labelStart = Math.max(
        0,
        ...Array.from(
          container.querySelectorAll<HTMLElement>(
            "[data-node-id] > [data-tree-label]",
          ),
        ).map((label) => label.getBoundingClientRect().left - bounds.left),
      );
      const free = Math.max(
        MIN_AREA,
        Math.floor(bounds.width - labelStart - MIN_LABEL - LABEL_GAP),
      );
      const key = JSON.stringify([start, tall, free, measured]);
      if (key !== last) {
        last = key;
        setRows(measured);
        setLeft(start);
        setHeight(tall);
        setRoom(free);
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
  const fitted =
    ids.length >= 2 && shown !== null
      ? fitArcs(rows, dependencies, room, (dependency) =>
          // Outgoing and incoming apart; a cycle's arcs are one set.
          shown.kind === "rows" &&
          rowsInside(shown.ids, rows).has(dependency.from)
            ? "outgoing"
            : shown.kind === "rows"
              ? "incoming"
              : "",
        )
      : { arcs: [], width: 0 };
  const arcs = fitted.arcs;
  // The room the rows keep free: none without arcs.
  const gutter = arcs.length > 0 ? fitted.width : 0;
  const byId = new Map(rows.map((row) => [row.id, row]));
  const maxWeight = Math.max(1, ...arcs.map((arc) => arc.weight));

  // Arcs are only drawn for a focus. With one direction shown in the
  // dependencies, the other's arcs stay, dimmed: still the hint that there
  // is more that way.
  const focus = shown;
  const mode = useDirectionMode();
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
            // At full strength while one of its ends is under the pointer.
            quiet={hovered !== arc.from && hovered !== arc.to}
            dimmed={
              mode !== "both" &&
              relationOf(arc, shown, rows) !== "cycle" &&
              relationOf(arc, shown, rows) !== mode
            }
          />
        ));

  return (
    <TreeSelectionContext
      value={{ selected, select: onSelect, menuOpen: setMenu }}
    >
      <div
        ref={ref}
        className="relative"
        style={{ "--arc-gutter": `${gutter}px` } as CSSProperties}
        onPointerOver={hover}
        onPointerLeave={() => setHovered(null)}
      >
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
          width={gutter}
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

// The visible rows the focused rows stand for: each row itself, or, once it
// is expanded, the visible rows inside it.
function rowsInside(ids: string[], rows: MeasuredRow[]): Set<string> {
  return new Set(
    rows
      .filter((row) => ids.some((id) => row.lineage.includes(id)))
      .map((row) => row.id),
  );
}

// The dependencies to draw for the focus: the rows' from them and to them,
// drilled down to their visible rows once they are expanded (those crossing
// the border around all of them, not those between them); a cycle's between
// its members.
function focusedDependencies(
  dependencies: ArcDependency[],
  focus: ArcFocus,
  rows: MeasuredRow[],
): ArcDependency[] {
  if (focus.kind === "cycle") {
    return cycleDependencies(dependencies, focus.members, rows);
  }
  const inside = rowsInside(focus.ids, rows);
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
  if (focus?.kind !== "rows") return "cycle";
  return rowsInside(focus.ids, rows).has(arc.from) ? "outgoing" : "incoming";
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
  // Drawn as context (thin, light), or at full strength.
  quiet: boolean;
  // In the direction the dependencies don't show: faded.
  dimmed: boolean;
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
  quiet,
  dimmed,
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
        quiet={quiet}
        title={`${fromLabel} → ${toLabel}: weight ${arc.weight}`}
        data-from={arc.from}
        data-to={arc.to}
        data-direction={arc.direction}
        data-relation={relation}
        data-weight={arc.weight}
        opacity={dimmed ? 0.3 : undefined}
        data-dimmed={dimmed || undefined}
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

// An arc's menu: what it joins and how heavily, the selection to clear, its
// ends to select instead, and the dependency to copy.
function ArcMenu({ focus, arc, from, to, children }: ArcMenuProps) {
  const { select, menuOpen } = useContext(TreeSelectionContext);
  const ends = [from, to]
    .filter((end) => end !== undefined)
    .filter((end) => !focusHolds(focus, end.id));
  const endItems = ends.map((end) => (
    <ContextMenuItem key={end.id} onSelect={() => select(rowFocus(end.id))}>
      <MousePointerClick />
      <span className="truncate">Select {end.label}</span>
    </ContextMenuItem>
  ));
  return (
    <ContextMenu onOpenChange={menuOpen}>
      <ContextMenuTrigger asChild>{children}</ContextMenuTrigger>
      <ContextMenuContent onCloseAutoFocus={(event) => event.preventDefault()}>
        <div className="text-fg-muted px-2 py-1 font-mono text-[12px]">
          {from?.label ?? arc.from} → {to?.label ?? arc.to} · {arc.weight}
        </div>
        <ContextMenuSeparator />
        <SelectItem focus={focus} name="Select" />
        {endItems}
        {from && to && (
          <>
            <ContextMenuSeparator />
            <CopyDependencySubmenu source={from} target={to} />
          </>
        )}
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
