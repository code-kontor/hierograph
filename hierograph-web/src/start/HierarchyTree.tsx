import { useQuery } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import {
  ChevronDown,
  ChevronRight,
  Repeat2,
  SquareMinus,
  SquarePlus,
} from "lucide-react";
import {
  type KeyboardEvent,
  type MouseEvent,
  useEffect,
  useId,
  useRef,
  useState,
} from "react";
import { twMerge } from "tailwind-merge";

import {
  type Gesture,
  GesturesHelp,
  KEYBOARD_GESTURES,
  SELECTION_GESTURES,
} from "@/design-system/graph/GesturesHelp";
import { PinSelectionButton } from "@/design-system/graph/PinSelectionButton";
import { SelectionStatus } from "@/design-system/graph/SelectionStatus";
import { Pane } from "@/design-system/layout/Pane";
import {
  ContextMenuItem,
  ContextMenuShortcut,
} from "@/design-system/ui/context-menu";
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
  sameFocus,
  TreeArcs,
} from "./TreeArcs";
import { KIND_ICONS } from "./treeIcons";
import { ordersChildren, treeNodeKind, treeNodeLabel } from "./treeLabels";

// What every row needs from the tree: which nodes are open, what is
// selected (a row or a cycle bar), whose dependency arcs are drawn, and where
// the keyboard is.
type TreeControls = {
  expanded: ReadonlySet<string>;
  toggle: (id: string) => Promise<void>;
  selected: ArcFocus | null;
  // The row the keyboard is on, and whether the tree is used by keyboard:
  // only then the row is marked.
  cursorId: string | undefined;
  keyboard: boolean;
  // The DOM id of a row's tree item, for the tree's active descendant.
  domId: (id: string) => string;
};

// The tree's gestures: every graph's; it neither zooms nor pans.
const TREE_GESTURES: Gesture[] = [
  ...SELECTION_GESTURES,
  { keys: "↑ ↓", action: "Move between rows" },
  { keys: "→ ←", action: "Expand, collapse; the first child, the parent" },
  ...KEYBOARD_GESTURES,
];

// The hierarchy of the current hierarchy view (or the scanned hierarchy
// without one) as a tree, loaded level by level as nodes are expanded. Each
// level's siblings are in dependency order (those depending on the others on
// top), its dependency cycles marked. The tree ends at the types: their
// members' dependencies aren't resolved yet, so they would have no arcs.
// Beside the tree, arcs show the dependencies of the selected row or cycle
// between the visible rows. It follows the design system's interaction model
// (docs/design-system.md, "Interaction"), without zoom and pan. The open rows
// and the selection are in the URL, so the screen comes back as it was left.
export function HierarchyTree() {
  const scope = useGraphScope();
  const view = scope?.view ?? "";
  const search: TreeSearch = useSearch({ strict: false });
  const setSearch = useSetTreeSearch();
  const selected = selectedFocus(search);
  // A pinned selection stays whatever is clicked; only a menu changes it,
  // and clearing it unpins it. Only a selection can be pinned.
  const pinned = search.selection_pinned === true && selected !== null;
  // Selecting changes the view, not the place: it replaces the history
  // entry, so Back doesn't step through every click.
  const onSelect = async (focus: ArcFocus | null) => {
    const same =
      focus === null
        ? selected === null
        : selected !== null && sameFocus(focus, selected);
    if (same) return;
    await setSearch(
      {
        selected_id: focus?.kind === "row" ? focus.id : undefined,
        selected_cycle_ids: focus?.kind === "cycle" ? focus.members : undefined,
        selection_pinned: focus === null ? undefined : search.selection_pinned,
      },
      true,
    );
  };
  const setPinned = async (pin: boolean) => {
    await setSearch({ selection_pinned: pin || undefined }, true);
  };
  // The selection's name, as the tree draws it.
  const [selectedLabel, setSelectedLabel] = useState("");
  const toolbar = (
    <>
      {selected !== null && (
        <SelectionStatus label={selectedLabel} onClear={() => onSelect(null)} />
      )}
      <PinSelectionButton
        pressed={pinned}
        disabled={selected === null}
        onPress={() => setPinned(!pinned)}
      />
      <GesturesHelp gestures={TREE_GESTURES} />
    </>
  );
  return (
    // The view is named on its card and in the header already.
    <Pane
      title="Hierarchy"
      toolbar={toolbar}
      bodyClassName="flex flex-col p-1.5"
    >
      {scope ? (
        // A new view starts collapsed.
        <TreeRoot
          key={view}
          scope={scope}
          selected={selected}
          pinned={pinned}
          onSelect={onSelect}
          onSelectedLabel={setSelectedLabel}
        />
      ) : (
        <p className="text-fg-muted p-1.5 text-[13px]">
          No graph is loaded, so there is no hierarchy to show yet.
        </p>
      )}
    </Pane>
  );
}

// A row's label as drawn, or its id while it isn't.
function rowLabel(id: string): string {
  return (
    document.querySelector<HTMLElement>(`[data-node-id="${CSS.escape(id)}"]`)
      ?.dataset.label ?? id
  );
}

function selectionLabel(focus: ArcFocus): string {
  return focus.kind === "row"
    ? rowLabel(focus.id)
    : `cycle of ${focus.members.map(rowLabel).join(", ")}`;
}

// The tree's params (see the start route). A lone numeric id may come in as
// a number; ids are compared as strings.
type TreeSearch = {
  expanded_ids?: (string | number)[];
  selected_id?: string | number;
  selected_cycle_ids?: (string | number)[];
  selection_pinned?: boolean;
};

function useSetTreeSearch() {
  const navigate = useNavigate();
  return async (change: Partial<TreeSearch>, replace: boolean) => {
    await navigate({
      to: ".",
      search: (previous: Record<string, unknown>) => ({
        ...previous,
        ...change,
      }),
      replace,
    } as never);
  };
}

function selectedFocus(search: TreeSearch): ArcFocus | null {
  if (search.selected_id !== undefined) {
    return { kind: "row", id: String(search.selected_id) };
  }
  if (search.selected_cycle_ids !== undefined) {
    return { kind: "cycle", members: search.selected_cycle_ids.map(String) };
  }
  return null;
}

type TreeRootProps = {
  scope: GraphScope;
  selected: ArcFocus | null;
  // Whether the selection is pinned: clicks and Enter don't change it.
  pinned: boolean;
  onSelect: (focus: ArcFocus | null) => Promise<void>;
  // Tells the selection's name, again whenever the rows change.
  onSelectedLabel: (label: string) => void;
};

// What a click in the tree is on: a cycle bar, a row, an arc, or the empty
// space around them.
function targetOf(target: EventTarget | null): ArcFocus | "arc" | null {
  if (!(target instanceof Element)) return null;
  const bar = target.closest<HTMLElement>("[data-cycle-bar]");
  if (bar) {
    return { kind: "cycle", members: (bar.dataset.members ?? "").split(",") };
  }
  const row = target.closest<HTMLElement>("[data-node-id]");
  if (row) return { kind: "row", id: row.dataset.nodeId ?? "" };
  if (target.closest("[data-arrow-hit]")) return "arc";
  return null;
}

// Whether a click on a row is left of its name: on its indent, its chevron
// or its icon, which expand it.
function inExpander(event: MouseEvent<HTMLElement>): boolean {
  const target = event.target;
  if (!(target instanceof Element)) return false;
  if (target.closest("[data-expander]")) return true;
  // On the row itself, not on what is in it: its indent, or a gap.
  if (!target.hasAttribute("data-node-id")) return false;
  const label = target.querySelector("[data-tree-label]");
  return label ? event.clientX < label.getBoundingClientRect().left : false;
}

// The rows' elements, top to bottom, and how they nest.
function rowElements(tree: HTMLElement): HTMLElement[] {
  return Array.from(tree.querySelectorAll<HTMLElement>("[data-node-id]"));
}

function itemOf(row: HTMLElement): HTMLElement | null {
  return row.closest<HTMLElement>("[data-tree-node]");
}

function parentRowOf(row: HTMLElement): HTMLElement | null {
  const parent =
    itemOf(row)?.parentElement?.closest<HTMLElement>("[data-tree-node]");
  return parent?.querySelector<HTMLElement>("[data-node-id]") ?? null;
}

function TreeRoot({
  scope,
  selected,
  pinned,
  onSelect,
  onSelectedLabel,
}: TreeRootProps) {
  const query = useQuery(hierarchyTreeRootQueryOptions(scope));
  const search: TreeSearch = useSearch({ strict: false });
  const setSearch = useSetTreeSearch();
  const expandedIds = (search.expanded_ids ?? []).map(String);
  const expanded = new Set(expandedIds);
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [keyboard, setKeyboard] = useState(false);
  const domPrefix = useId();
  const domId = (id: string) => `${domPrefix}row-${id}`;
  const tree = useRef<HTMLDivElement>(null);
  // The rows load level by level: the selection's name is read from them
  // whenever they change.
  useEffect(() => {
    const element = tree.current;
    const update = () =>
      onSelectedLabel(selected === null ? "" : selectionLabel(selected));
    update();
    if (!element) return;
    const mutations = new MutationObserver(update);
    mutations.observe(element, { subtree: true, childList: true });
    return () => mutations.disconnect();
  });
  // Expanding is a view toggle (replace).
  const toggle = async (id: string) => {
    const next = expanded.has(id)
      ? expandedIds.filter((other) => other !== id)
      : [...expandedIds, id];
    await setSearch({ expanded_ids: next.length > 0 ? next : undefined }, true);
  };
  // The row the keyboard is on; it may have been collapsed away meanwhile.
  const cursorId = cursor;
  const controls: TreeControls = {
    expanded,
    toggle,
    selected,
    cursorId,
    keyboard,
    domId,
  };

  // Whether a row can be expanded: its tree item says whether it is.
  const expandable = (id: string) =>
    document.getElementById(domId(id))?.hasAttribute("aria-expanded") ?? false;

  // A click left of a row's name (on its indent, its chevron or its icon)
  // expands or collapses it, so the row's expander is easy to hit; anywhere
  // else, a click selects the row or cycle bar under the pointer, or clears
  // the selection if it is the selected one or the empty space; on an arc it
  // does nothing. The second click of a double-click is left to the
  // double-click. Clicks in a context menu (a portal) reach the tree too, but
  // aren't in it.
  const click = async (event: MouseEvent<HTMLDivElement>) => {
    if (!event.currentTarget.contains(event.target as Node)) return;
    if (event.button !== 0 || event.detail > 1) return;
    // Reserved for selecting more than one row.
    if (event.ctrlKey || event.metaKey) return;
    setKeyboard(false);
    const target = targetOf(event.target);
    if (target === "arc") return;
    if (target?.kind === "row") {
      setCursor(target.id);
      if (inExpander(event) && expandable(target.id)) {
        await toggle(target.id);
        return;
      }
    }
    if (pinned) return;
    const same =
      target !== null && selected !== null && sameFocus(target, selected);
    await onSelect(target === null || same ? null : target);
  };
  // A double-click on a row's name expands or collapses it, and leaves it
  // selected. Left of the name, the first click expanded it already.
  const doubleClick = async (event: MouseEvent<HTMLDivElement>) => {
    if (!event.currentTarget.contains(event.target as Node)) return;
    const target = targetOf(event.target);
    if (target === null || target === "arc" || target.kind !== "row") return;
    if (inExpander(event)) return;
    if (expandable(target.id)) await toggle(target.id);
    if (!pinned) await onSelect(target);
  };
  // The keyboard walks the visible rows as a tree: ↑ ↓ Home End move, →
  // expands or goes to the first child, ← collapses or goes to the parent;
  // + and − expand and collapse, as on every graph; Enter and Space select.
  const keyDown = async (event: KeyboardEvent<HTMLDivElement>) => {
    if (!event.currentTarget.contains(event.target as Node)) return;
    if (event.defaultPrevented || event.altKey || event.ctrlKey) return;
    if (event.metaKey) return;
    const rows = rowElements(event.currentTarget);
    if (rows.length === 0) return;
    const ids = rows.map((row) => row.dataset.nodeId ?? "");
    const index = cursorId === undefined ? -1 : ids.indexOf(cursorId);
    const row = index < 0 ? undefined : rows[index];
    const state = row ? itemOf(row)?.getAttribute("aria-expanded") : null;
    const start =
      selected?.kind === "row" && ids.includes(selected.id)
        ? selected.id
        : ids[0];
    let next: string | undefined;
    switch (event.key) {
      case "ArrowDown":
        next = row ? ids[Math.min(index + 1, ids.length - 1)] : start;
        break;
      case "ArrowUp":
        next = row ? ids[Math.max(index - 1, 0)] : start;
        break;
      case "Home":
        next = ids[0];
        break;
      case "End":
        next = ids[ids.length - 1];
        break;
      case "ArrowRight":
        if (!row || cursorId === undefined) next = start;
        else if (state === "false") {
          event.preventDefault();
          setKeyboard(true);
          await toggle(cursorId);
          return;
        } else if (
          state === "true" &&
          rows[index + 1] &&
          parentRowOf(rows[index + 1]) === row
        ) {
          next = ids[index + 1];
        }
        break;
      case "ArrowLeft":
        if (!row || cursorId === undefined) next = start;
        else if (state === "true") {
          event.preventDefault();
          setKeyboard(true);
          await toggle(cursorId);
          return;
        } else next = parentRowOf(row)?.dataset.nodeId;
        break;
      // Expands the row.
      case "+":
      case "=":
        event.preventDefault();
        setKeyboard(true);
        if (row && cursorId !== undefined && state === "false") {
          await toggle(cursorId);
        }
        return;
      // Collapses the row, or, in a row that isn't open, the row it lies in,
      // which the cursor moves to.
      case "-": {
        event.preventDefault();
        setKeyboard(true);
        if (!row || cursorId === undefined) return;
        if (state === "true") {
          await toggle(cursorId);
          return;
        }
        const parent = parentRowOf(row)?.dataset.nodeId;
        if (parent === undefined) return;
        setCursor(parent);
        await toggle(parent);
        return;
      }
      case "Enter":
      case " ":
        event.preventDefault();
        setKeyboard(true);
        if (cursorId !== undefined && !pinned) {
          const focus: ArcFocus = { kind: "row", id: cursorId };
          const same = selected !== null && sameFocus(focus, selected);
          await onSelect(same ? null : focus);
        }
        return;
      default:
        return;
    }
    event.preventDefault();
    setKeyboard(true);
    if (next === undefined) return;
    setCursor(next);
    document
      .getElementById(domId(next))
      ?.querySelector("[data-node-id]")
      ?.scrollIntoView({ block: "nearest", inline: "nearest" });
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
    <div
      ref={tree}
      role="tree"
      aria-label="Hierarchy"
      tabIndex={0}
      aria-activedescendant={
        cursorId === undefined ? undefined : domId(cursorId)
      }
      // Takes the whole pane, so a click below the rows clears the selection.
      className="group/tree flex-1 outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-(--hg-state-focus-ring)"
      onClick={click}
      onDoubleClick={doubleClick}
      onKeyDown={keyDown}
      onPointerDown={() => setKeyboard(false)}
    >
      <TreeArcs scope={scope} selected={selected} onSelect={onSelect}>
        <TreeLevel
          role="none"
          scope={scope}
          level={query.data}
          parentText=""
          depth={0}
          controls={controls}
        />
      </TreeArcs>
    </div>
  );
}

type CycleBarProps = {
  mark: CycleMark;
  depth: number;
  controls: TreeControls;
};

// One segment of the bar along a cycle's block, at the nodes' indent; the
// block's ends are inset, so two adjacent cycles read as two. A click selects
// the cycle, drawing the arcs that close it, and so does its context menu; a
// hit area wider than the bar makes it easy to hit. Selected, it is wider.
function CycleBar({ mark, depth, controls }: CycleBarProps) {
  const focus: ArcFocus = { kind: "cycle", members: mark.ids };
  const selected =
    controls.selected !== null && sameFocus(controls.selected, focus);
  return (
    <ArcFocusMenu focus={focus}>
      <span
        aria-hidden
        data-cycle-bar
        data-members={mark.ids.join(",")}
        data-selected={selected || undefined}
        // The bar's menu, not the row's.
        onContextMenu={(event) => event.stopPropagation()}
        className="absolute flex w-2 cursor-pointer justify-center"
        style={{
          left: `${depth * 14 - 3}px`,
          top: mark.first ? "4px" : "0",
          bottom: mark.last ? "4px" : "0",
        }}
      >
        <span
          className={twMerge(
            "bg-status-warning w-0.5 rounded-full",
            selected && "w-1",
          )}
        />
      </span>
    </ArcFocusMenu>
  );
}

function isExternal(node: TreeNode): boolean {
  return treeNodeKind(node.type, node.text) === "external";
}

type TreeLevelProps = {
  // The top level's list is no group: its items belong to the tree.
  role: "none" | "group";
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
    <ul role={role} className="flex flex-col">
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
  const focus: ArcFocus = { kind: "row", id: node.id };
  const selected =
    controls.selected !== null && sameFocus(controls.selected, focus);
  const atCursor = controls.keyboard && controls.cursorId === node.id;
  // The row's menu: select it, and expand or collapse it.
  const menuItems = expandable && (
    <ContextMenuItem onSelect={() => controls.toggle(node.id)}>
      {open ? <SquareMinus /> : <SquarePlus />}
      {open ? "Collapse" : "Expand"}
      <ContextMenuShortcut>
        {open ? "− / Double-click" : "+ / Double-click"}
      </ContextMenuShortcut>
    </ContextMenuItem>
  );
  return (
    <li
      role="treeitem"
      id={controls.domId(node.id)}
      aria-expanded={expandable ? open : undefined}
      aria-selected={selected}
      aria-label={label}
      // No tooltip on the row: it would cover the arcs.
      aria-description={rowDescription(node, cycle?.others)}
      data-cycle={cycle ? "true" : undefined}
      data-tree-node={node.id}
      className={twMerge(separated && "border-border mt-1 border-t pt-1")}
    >
      {/* A click selects the row (see `TreeRoot`); its context menu too. */}
      <ArcFocusMenu focus={focus} items={menuItems}>
        <div
          data-node-id={node.id}
          data-label={label}
          data-frontier={frontier ? "true" : undefined}
          style={{ paddingLeft: `${depth * 14 + 4}px` }}
          // Not selectable: a double-click would select the word under the
          // pointer.
          className={twMerge(
            "hover:bg-state-hover relative flex w-full cursor-pointer items-center gap-1 rounded-[4px] py-[3px] text-left text-[13px] select-none",
            selected &&
              "bg-state-selected-bg text-state-selected-fg hover:bg-state-selected-bg",
            atCursor &&
              "group-focus/tree:outline-2 group-focus/tree:-outline-offset-2 group-focus/tree:outline-(--hg-state-focus-ring)",
          )}
        >
          {cycle && <CycleBar mark={cycle} depth={depth} controls={controls} />}
          {expandable ? (
            <button
              type="button"
              // No tab stop: the keyboard expands with → and ←.
              tabIndex={-1}
              aria-label={`${open ? "Collapse" : "Expand"} ${label}`}
              // A click on it expands or collapses the row (see `TreeRoot`).
              data-expander
              className="text-fg-subtle hover:text-fg flex size-3.5 shrink-0 items-center justify-center"
            >
              <Chevron className="size-3.5" />
            </button>
          ) : (
            <span className="size-3.5 shrink-0" />
          )}
          <Icon
            aria-hidden
            // Left of the name: a click on it expands the row, too.
            data-expander
            className={twMerge(
              "text-fg-subtle size-3.5 shrink-0",
              kind === "group" && "text-primary",
            )}
          />
          <span
            data-tree-label
            // A folded row names the lone packages it stands for on its label
            // only, left of the arcs.
            title={foldedTitle(node)}
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
        </div>
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

// What a folded row stands for: the lone packages above it, outermost first.
function foldedTitle(node: TreeNode): string | undefined {
  if (node.compactedFrom.length === 0) return undefined;
  const skipped = node.compactedFrom.map((package_) => package_.text);
  return `${node.text}\nfolded: ${skipped.join(", ")}`;
}

// A row's description for assistive technology: what it stands for when it
// is folded, and the cycle it is in.
function rowDescription(
  node: TreeNode,
  cycleOthers: string[] | undefined,
): string | undefined {
  const parts: string[] = [];
  if (node.compactedFrom.length > 0) {
    const skipped = node.compactedFrom.map((package_) => package_.text);
    parts.push(`folded from ${skipped.join(", ")}`);
  }
  if (cycleOthers)
    parts.push(`in a dependency cycle with ${cycleOthers.join(", ")}`);
  return parts.length > 0 ? parts.join("; ") : undefined;
}
