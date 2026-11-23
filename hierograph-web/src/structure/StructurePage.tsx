import "./structure-map.css";

import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import {
  CircleDot,
  Crosshair,
  FileCode,
  Focus,
  Folder,
  Globe,
  Layers,
  ListChevronsDownUp,
  ListChevronsUpDown,
  type LucideIcon,
  Maximize,
  MousePointerClick,
  Package,
  SquareMinus,
  SquarePlus,
  X,
  ZoomIn,
  ZoomOut,
} from "lucide-react";
import {
  createContext,
  type KeyboardEvent,
  type MouseEvent,
  type PointerEvent,
  type ReactNode,
  useContext,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
import { twMerge } from "tailwind-merge";

import {
  DependencyArrow,
  DependencyArrowHeads,
  dependencyArrowWidth,
  useArrowHeadsId,
} from "@/design-system/graph/DependencyArrow";
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
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuShortcut,
  ContextMenuTrigger,
} from "@/design-system/ui/context-menu";
import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";
import { Message } from "@/design-system/ui/message";
import { type GraphScope, useGraphScope } from "@/frame/GraphScope";

import {
  expansionFor,
  type GivenPlacement,
  parseGiven,
  placeGiven,
} from "./givenDependencies";
import {
  type Arrow,
  type ArrowRole,
  type ArrowSpec,
  layoutArrows,
  type Rect,
} from "./mapArrows";
import {
  boxOrder,
  buildModel,
  isWithin,
  itemWeight,
  type MapItem,
  type MapModel,
  restrictModel,
  trace,
  type TracedLink,
} from "./mapModel";
import {
  structureCellsQueryOptions,
  structureGivenQueryOptions,
  type StructureLevel,
  structureLevelQueryOptions,
  structureRootQueryOptions,
} from "./queries";
import {
  isContainer,
  type StructureKind,
  structureKind,
} from "./structureLabels";

// The map's navigation state, in the URL: the expanded containers, the
// selected box and whether it is pinned, the dependencies handed to the map
// (`<source id>-<target id>`, see givenDependencies.ts) and whether only
// those are drawn.
type MapSearch = {
  expanded_ids?: string[];
  selected_id?: string;
  selection_pinned?: boolean;
  dependency_ids?: string[];
  given_only?: boolean;
};

// What every box needs: the map, the box whose arrows are drawn (the
// selected one) and what it is coupled to, the keyboard's cursor, and the
// ways to change them.
type MapState = {
  model: MapModel;
  expanded: ReadonlySet<string>;
  focusId: string | undefined;
  selectedId: string | undefined;
  // Whether the selection is pinned: clicks and Enter don't change it, only
  // a menu does.
  pinned: boolean;
  // The box the keyboard is on; shown while the map is used by keyboard.
  cursorId: string | undefined;
  setCursor: (id: string) => void;
  // The DOM id of a box's element, for the tree's active descendant.
  domId: (id: string) => string;
  uses: Map<string, number>;
  usedBy: Map<string, number>;
  // The couplings the arrows are drawn for, between visible boxes.
  links: TracedLink[];
  // The dependencies handed to the map, placed on its boxes; null without
  // any, or while they load.
  given: GivenPlacement | null;
  // Only the given dependencies drawn: the other arrows hidden, the boxes
  // they don't touch greyed out.
  givenOnly: boolean;
  // The given dependencies, as a key that changes with the set.
  givenKey: string;
  // Whether the given dependencies are drawn where they will stay: resolved,
  // the map expanded for them and every container on the way loaded.
  givenSettled: boolean;
  // Whether a box is greyed out: it is none of the arrows' ends, nor holds
  // one, nor lies in the box they are drawn for.
  dimmed: (id: string) => boolean;
  toggle: (id: string) => Promise<void>;
  // Expands a container and every container in it, at any depth.
  expandAll: (id: string) => Promise<void>;
  // Collapses a container and every container in it.
  collapseAll: (id: string) => Promise<void>;
  // Collapses every container in the map.
  collapseEverything: () => Promise<void>;
  // The expanded containers in a box, itself included.
  expandedWithin: (id: string) => string[];
  // Selects a box (null clears the selection).
  select: (id: string | null) => Promise<void>;
};

const MapContext = createContext<MapState | null>(null);

function useMap(): MapState {
  const state = useContext(MapContext);
  if (!state) throw new Error("A map box outside the map");
  return state;
}

// The levelized structure map of the hierarchy in the current view, as the
// hierograph-structure skill's static page draws it: every container's
// children in rows by level, bottom first, so a box sits above what it uses;
// the members of a cycle together in a red tangle; and only the dependencies
// against the levels drawn, as dashed arrows in the cycle colour. Selecting a
// box traces what it uses (accent) and what uses it (grey), in the start
// screen's arc colours. Containers load their rows as they are expanded. The
// map follows the design system's interaction model (docs/design-system.md,
// "Interaction").
//
// Dependencies handed to the map in the URL (`dependency_ids`) are drawn as
// arrows of their own; without `expanded_ids`, the map opens just far enough
// to tell their ends apart, and then on them. A toolbar switch draws only
// these.
export function StructurePage() {
  const scope = useGraphScope();
  return scope ? (
    <StructureMap key={scope.view ?? ""} scope={scope} />
  ) : (
    <MapPane toolbar={null}>
      <p className="text-fg-muted text-[13px]">
        No graph is loaded, so there is no structure to show yet.
      </p>
    </MapPane>
  );
}

type MapPaneProps = {
  toolbar: ReactNode;
  children: ReactNode;
};

function MapPane({ toolbar, children }: MapPaneProps) {
  return (
    <Pane
      title="Structure"
      toolbar={toolbar}
      bodyClassName="flex min-h-0 flex-col p-3"
    >
      {children}
    </Pane>
  );
}

type GivenOnlyButtonProps = {
  pressed: boolean;
  onPress: () => void;
};

// Draws only the given dependencies, and the boxes they touch.
function GivenOnlyButton({ pressed, onPress }: GivenOnlyButtonProps) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      title="Draw only the given dependencies"
      onClick={onPress}
      className={twMerge(
        ghostIconTriggerClassName,
        "w-auto px-1.5 font-mono text-[11px]",
        pressed && "bg-state-selected-bg text-state-selected-fg",
      )}
    >
      Only these
    </button>
  );
}

// The map's gestures: every graph's, and its zoom and pan.
const MAP_GESTURES: Gesture[] = [
  ...SELECTION_GESTURES,
  { keys: "Ctrl/⌘ + wheel, pinch", action: "Zoom at the pointer" },
  { keys: "Ctrl/⌘ + + / − / 0", action: "Zoom in, out, to 100%" },
  { keys: "Drag", action: "Pan" },
  { keys: "← → ↑ ↓", action: "Move to the nearest box that way" },
  ...KEYBOARD_GESTURES,
];

type StructureMapProps = {
  scope: GraphScope;
};

function StructureMap({ scope }: StructureMapProps) {
  const search: MapSearch = useSearch({ strict: false });
  const navigate = useNavigate();
  // A lone numeric id may come in as a number; ids are compared as strings.
  const expandedIds = (search.expanded_ids ?? []).map(String);
  const expanded = new Set(expandedIds);
  const selectedParam =
    search.selected_id === undefined ? undefined : String(search.selected_id);
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const domPrefix = useId();
  const queryClient = useQueryClient();

  const root = useQuery(structureRootQueryOptions(scope));
  const rootId = root.data ?? null;
  // The packages of the chains the map folds, as it has asked for them.
  const [chainIds, setChainIds] = useState<string[]>([]);
  // The root and every expanded container, shown or not: a container inside
  // a collapsed one keeps its rows for when it shows again; and the chains'
  // packages.
  const levelIds =
    rootId === null ? [] : [...new Set([rootId, ...expandedIds, ...chainIds])];
  const levelQueries = useQueries({
    queries: levelIds.map((id) => structureLevelQueryOptions(scope, id)),
  });
  const levels = new Map<string, StructureLevel>();
  levelQueries.forEach((query, index) => {
    if (query.data) levels.set(levelIds[index], query.data);
  });
  const model = rootId === null ? null : buildModel(rootId, levels, expanded);
  // Loads the chains' packages the model is waiting for; each loaded one may
  // ask for the next one down. Adjusted while rendering, as React has it for
  // state that follows from what is rendered.
  const pending = model?.pending.filter((id) => !chainIds.includes(id)) ?? [];
  if (pending.length > 0) setChainIds([...chainIds, ...pending]);
  const selectedId =
    selectedParam !== undefined && model?.boxes.has(selectedParam)
      ? selectedParam
      : undefined;

  // The dependencies handed to the map, resolved in the view and placed on
  // the boxes as they are shown now.
  const given = parseGiven(search.dependency_ids?.map(String));
  const resolvedGiven = useQuery({
    ...structureGivenQueryOptions(scope, given.pairs),
    enabled: given.pairs.length > 0,
  });
  const placement =
    model && resolvedGiven.data ? placeGiven(model, resolvedGiven.data) : null;
  const givenOnly = search.given_only === true && placement !== null;
  // With only the given dependencies drawn, nothing is traced.
  const focusId = givenOnly ? undefined : selectedId;

  // The couplings between the shown boxes, loaded for all of them when a box
  // is first selected: selecting the next one draws its arrows at once.
  const cells = useQuery({
    ...structureCellsQueryOptions(scope, model?.frontier ?? []),
    enabled: focusId !== undefined && (model?.frontier.length ?? 0) >= 2,
  });
  const traced =
    model && focusId && cells.data
      ? trace(model, focusId, cells.data)
      : {
          uses: new Map<string, number>(),
          usedBy: new Map<string, number>(),
          links: [],
        };

  // The boxes the arrows touch and the containers they lie in; null while
  // nothing is traced, or the couplings are still loading.
  const involved = new Set<string>();
  if (model && focusId && cells.data) {
    for (const id of [
      focusId,
      ...traced.uses.keys(),
      ...traced.usedBy.keys(),
    ]) {
      for (let at: string | null = id; at !== null;) {
        involved.add(at);
        at = model.boxes.get(at)?.parentId ?? null;
      }
    }
  }
  // Only the outermost box of an uninvolved part is greyed out, so nested
  // boxes don't fade twice. With only the given dependencies drawn, nothing
  // else is on the map to grey out.
  const dimmed = (id: string) => {
    if (givenOnly) return false;
    if (!model || !focusId || involved.size === 0) return false;
    if (involved.has(id) || isWithin(model, id, focusId)) return false;
    const parentId = model.boxes.get(id)?.parentId ?? null;
    return parentId === null || involved.has(parentId);
  };

  const setSearch = async (change: Partial<MapSearch>, replace: boolean) => {
    await navigate({
      to: ".",
      search: (previous: Record<string, unknown>) => ({
        ...previous,
        ...change,
      }),
      replace,
    } as never);
  };
  // Expanding and selecting change the view, not the place: they replace the
  // history entry, so Back doesn't step through every click.
  const toggle = async (id: string) => {
    const next = expanded.has(id)
      ? expandedIds.filter((other) => other !== id)
      : [...expandedIds, id];
    await setSearch({ expanded_ids: next.length > 0 ? next : undefined }, true);
  };
  // Every loaded node's container, from the levels: expanded containers
  // inside a collapsed one are found too.
  const parentOf = new Map<string, string>();
  for (const [id, level] of levels) {
    for (const nodes of level.levels) {
      for (const node of nodes) parentOf.set(node.id, id);
    }
  }
  const expandedWithin = (id: string) =>
    expandedIds.filter((other) => {
      for (let at: string | undefined = other; at; at = parentOf.get(at)) {
        if (at === id) return true;
      }
      return false;
    });
  // Loads every container in the box, level by level, then opens them all
  // at once.
  const expandAll = async (id: string) => {
    const found = new Set<string>([id]);
    const visit = async (containerId: string): Promise<void> => {
      const level = await queryClient.fetchQuery(
        structureLevelQueryOptions(scope, containerId),
      );
      const inner = level.levels
        .flat()
        .filter((node) => isContainer(node.type, node.hasChildren))
        .map((node) => node.id);
      for (const innerId of inner) found.add(innerId);
      await Promise.all(inner.map(visit));
    };
    await visit(id);
    const next = [...new Set([...expandedIds, ...found])];
    await setSearch({ expanded_ids: next }, true);
  };
  const collapseAll = async (id: string) => {
    const closed = new Set(expandedWithin(id));
    const next = expandedIds.filter((other) => !closed.has(other));
    await setSearch({ expanded_ids: next.length > 0 ? next : undefined }, true);
  };
  const collapseEverything = async () => {
    await setSearch({ expanded_ids: undefined }, true);
  };
  // A pinned selection stays whatever is clicked; only a menu changes it,
  // and clearing it unpins it. Only a selection can be pinned.
  const pinned =
    search.selection_pinned === true && selectedParam !== undefined;
  const select = async (id: string | null) => {
    if ((id ?? undefined) === selectedParam) return;
    await setSearch(
      {
        selected_id: id ?? undefined,
        selection_pinned: id === null ? undefined : search.selection_pinned,
      },
      true,
    );
  };
  const setPinned = async (pin: boolean) => {
    await setSearch({ selection_pinned: pin || undefined }, true);
  };
  const setGivenOnly = async (only: boolean) => {
    await setSearch({ given_only: only || undefined }, true);
  };

  // Without an expansion in the URL, the map opens just far enough that the
  // ends of every given dependency lie in different boxes, once per set of
  // dependencies, and writes it into the URL, so a reload or a copied link
  // shows the same. A set that comes with an expansion keeps it.
  const givenKey = given.pairs
    .map((pair) => `${pair.sourceId}-${pair.targetId}`)
    .join(",");
  const expandedFor = useRef<string | null>(null);
  const givenSettled =
    placement !== null &&
    resolvedGiven.data !== undefined &&
    (search.expanded_ids !== undefined ||
      expansionFor(resolvedGiven.data).length === 0) &&
    !levelQueries.some((query) => query.isPending) &&
    (model?.pending.length ?? 0) === 0;
  useEffect(() => {
    const resolved = resolvedGiven.data;
    if (!resolved || givenKey === "" || expandedFor.current === givenKey) {
      return;
    }
    expandedFor.current = givenKey;
    if (search.expanded_ids !== undefined) return;
    const ids = expansionFor(resolved);
    if (ids.length === 0) return;
    const expand = async () => {
      await setSearch({ expanded_ids: ids }, true);
    };
    expand().catch((error: unknown) => console.error(error));
  });
  const toolbar = (
    <>
      {placement !== null && (
        <GivenOnlyButton
          pressed={search.given_only === true}
          onPress={() => setGivenOnly(search.given_only !== true)}
        />
      )}
      {selectedParam !== undefined && (
        <SelectionStatus
          label={model?.boxes.get(selectedParam)?.label ?? selectedParam}
          onClear={() => select(null)}
        />
      )}
      <PinSelectionButton
        pressed={pinned}
        disabled={selectedParam === undefined}
        onPress={() => setPinned(!pinned)}
      />
      <GesturesHelp gestures={MAP_GESTURES} />
    </>
  );
  if (root.isPending) {
    return (
      <MapPane toolbar={toolbar}>
        <Message variant="loading" title="Loading the structure…" />
      </MapPane>
    );
  }
  if (root.isError || model === null) {
    return (
      <MapPane toolbar={toolbar}>
        <Message variant="error" title="The structure can't be loaded">
          {root.error?.message}
        </Message>
      </MapPane>
    );
  }

  // With only the given dependencies drawn, only the boxes they touch and
  // the boxes those lie in.
  const shown =
    givenOnly && placement ? restrictModel(model, placement.involved) : model;
  const state: MapState = {
    model: shown,
    expanded,
    focusId,
    selectedId,
    pinned,
    cursorId:
      cursor !== undefined && shown.boxes.has(cursor) ? cursor : undefined,
    setCursor,
    domId: (id) => `${domPrefix}box-${id}`,
    uses: traced.uses,
    usedBy: traced.usedBy,
    links: traced.links,
    given: placement,
    givenOnly,
    givenKey,
    givenSettled,
    dimmed,
    toggle,
    expandAll,
    collapseAll,
    collapseEverything,
    expandedWithin,
    select,
  };
  return (
    <MapContext value={state}>
      <MapPane toolbar={toolbar}>
        <div className="lsm flex min-h-0 flex-1 flex-col">
          <MapCanvas />
        </div>
      </MapPane>
    </MapContext>
  );
}

const MIN_ZOOM = 0.2;
const MAX_ZOOM = 3;
const ZOOM_STEP = 1.25;
const WHEEL_CAP = 20;
// The margin around the boxes the map zooms to (the selection's, the given
// dependencies').
const FIT_MARGIN = 48;
// Moving the pointer this far with the button down pans instead of clicking.
const DRAG_START = 4;

type Point = { x: number; y: number };

// The zoom that shows all of the map in the viewport, never above 100%.
function fitZoom(
  view: HTMLElement,
  size: { width: number; height: number },
): number {
  return Math.max(
    MIN_ZOOM,
    Math.min(1, view.clientWidth / size.width, view.clientHeight / size.height),
  );
}

// The map and, over it, the arrows, laid out from the boxes whenever the map
// changes or is resized. The map sits in a viewport that scrolls natively,
// pans by dragging and zooms (ctrl/⌘ + wheel or pinch around the pointer,
// ctrl/⌘ + +/−/0, or the buttons around the centre). The map is laid out at
// the viewport's width and scaled, so zooming never rearranges it. A click
// selects a box (again, on the canvas or Esc clears it), a double-click
// expands or collapses it, a right click opens its menu; the keyboard walks
// the boxes as a tree. A box selected in the URL is scrolled into view once
// it is drawn.
function MapCanvas() {
  const {
    model,
    expanded,
    focusId,
    selectedId,
    pinned,
    cursorId,
    setCursor,
    domId,
    uses,
    usedBy,
    links,
    given,
    givenOnly,
    givenKey,
    givenSettled,
    select,
    toggle,
  } = useMap();
  const heads = useArrowHeadsId();
  // The box or arrow a right click opened the menu for.
  const [menuTarget, setMenuTarget] = useState<MenuTarget | null>(null);
  const viewport = useRef<HTMLDivElement>(null);
  const map = useRef<HTMLDivElement>(null);
  const scrolledTo = useRef<string | undefined>(undefined);
  const [arrows, setArrows] = useState<Arrow[]>([]);
  // The last layout and what it was laid out from: zooming, scrolling and
  // re-rendering draw the same arrows again without laying them out.
  const lastLayout = useRef<{
    key: string;
    layout: ReturnType<typeof layoutArrows>;
  } | null>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const [gutter, setGutter] = useState({ left: 0, right: 0 });
  const [viewWidth, setViewWidth] = useState(0);
  const [zoom, setZoom] = useState(1);
  const [panning, setPanning] = useState(false);
  // Whether the map is used by keyboard: the cursor is shown only then.
  const [keyboard, setKeyboard] = useState(false);
  // Set when the keyboard moves the cursor, to scroll it into view.
  const revealCursor = useRef(false);
  // The zoom asked for last, ahead of the one drawn while a render is due.
  const zoomAsked = useRef(1);
  // The point of the map to keep under a point of the viewport when the next
  // zoom is drawn.
  const anchor = useRef<{ map: Point; view: Point } | null>(null);
  const drag = useRef<{
    start: Point;
    scroll: Point;
    moved: boolean;
  } | null>(null);
  const swallowClick = useRef(false);

  const zoomTo = (next: number, at?: Point) => {
    const view = viewport.current;
    if (!view) return;
    const clamped = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, next));
    const point = at ?? { x: view.clientWidth / 2, y: view.clientHeight / 2 };
    anchor.current ??= {
      map: {
        x: (view.scrollLeft + point.x) / zoom,
        y: (view.scrollTop + point.y) / zoom,
      },
      view: point,
    };
    zoomAsked.current = clamped;
    setZoom(clamped);
  };
  // Zooms and scrolls to the boxes the given dependencies join.
  const zoomToGiven = () => {
    if (!given) return;
    zoomToBoxes(given.arrows.flatMap((arrow) => [arrow.fromBox, arrow.toBox]));
  };
  // Zooms and scrolls to the selected box and the boxes its arrows end at,
  // with a margin, never above 100%.
  const zoomToSelection = () => {
    if (selectedId === undefined) return;
    zoomToBoxes([selectedId, ...uses.keys(), ...usedBy.keys()]);
  };
  // Zooms and scrolls to the boxes, with a margin, never above 100%.
  const zoomToBoxes = (ids: string[]) => {
    const view = viewport.current;
    const element = map.current;
    if (!view || !element) return;
    const rects = ids
      .map((id) => boxRect(element, id))
      .filter((rect) => rect !== null);
    if (rects.length === 0) return;
    const l = Math.min(...rects.map((rect) => rect.l));
    const t = Math.min(...rects.map((rect) => rect.t));
    const r = Math.max(...rects.map((rect) => rect.r));
    const bottom = Math.max(...rects.map((rect) => rect.b));
    const next = Math.max(
      MIN_ZOOM,
      Math.min(
        1,
        view.clientWidth / (r - l + 2 * FIT_MARGIN),
        view.clientHeight / (bottom - t + 2 * FIT_MARGIN),
      ),
    );
    const point = {
      map: { x: (l + r) / 2, y: (t + bottom) / 2 },
      view: { x: view.clientWidth / 2, y: view.clientHeight / 2 },
    };
    zoomAsked.current = next;
    if (next === zoom) {
      view.scrollLeft = point.map.x * zoom - point.view.x;
      view.scrollTop = point.map.y * zoom - point.view.y;
      return;
    }
    anchor.current = point;
    setZoom(next);
  };
  // The map opens on the given dependencies, once per set, when they are
  // drawn where they stay.
  const zoomedFor = useRef<string | null>(null);
  const hasGivenArrows = (given?.arrows.length ?? 0) > 0;
  useLayoutEffect(() => {
    if (!givenSettled || !hasGivenArrows || viewWidth === 0) return;
    // Again when only these are drawn, or all again: the map changes.
    const key = `${givenKey}|${givenOnly}`;
    if (zoomedFor.current === key) return;
    zoomedFor.current = key;
    zoomToGiven();
  });

  const fit = () => {
    const view = viewport.current;
    if (!view || size.width === 0 || size.height === 0) return;
    anchor.current = { map: { x: 0, y: 0 }, view: { x: 0, y: 0 } };
    zoomTo(fitZoom(view, size));
  };

  // The screen opens with the whole map in view, once its top level is drawn
  // at the viewport's width. The map is measured itself: the size of the
  // last drawing may still be the empty map's.
  const fitted = useRef(false);
  const drawn = (model.rowsOf.get(model.rootId)?.length ?? 0) > 0;
  useLayoutEffect(() => {
    const view = viewport.current;
    const element = map.current;
    if (fitted.current || !view || !element || !drawn || viewWidth === 0) {
      return;
    }
    // Opened on the given dependencies already.
    if (zoomedFor.current !== null) return;
    fitted.current = true;
    const next = fitZoom(view, {
      width: element.offsetWidth,
      height: element.offsetHeight,
    });
    zoomAsked.current = next;
    setZoom(next);
  }, [drawn, viewWidth]);

  useLayoutEffect(() => {
    const view = viewport.current;
    const point = anchor.current;
    if (!view || !point) return;
    anchor.current = null;
    view.scrollLeft = point.map.x * zoom - point.view.x;
    view.scrollTop = point.map.y * zoom - point.view.y;
  }, [zoom]);

  // A wheel with ctrl or ⌘ (a pinch on a trackpad) zooms instead of
  // scrolling; it needs a listener that can cancel the scroll.
  useEffect(() => {
    const view = viewport.current;
    if (!view) return;
    const wheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      const bounds = view.getBoundingClientRect();
      // A pinch sends small steps; a mouse wheel's notch, capped, about 20%.
      const delta = Math.max(-WHEEL_CAP, Math.min(WHEEL_CAP, event.deltaY));
      zoomTo(zoomAsked.current * Math.exp(-delta * 0.01), {
        x: event.clientX - bounds.left,
        y: event.clientY - bounds.top,
      });
    };
    view.addEventListener("wheel", wheel, { passive: false });
    return () => view.removeEventListener("wheel", wheel);
  });

  useLayoutEffect(() => {
    const view = viewport.current;
    if (!view) return;
    const measure = () => setViewWidth(view.clientWidth);
    measure();
    const resizes = new ResizeObserver(measure);
    resizes.observe(view);
    return () => resizes.disconnect();
  }, []);

  const pointerDown = (event: PointerEvent<HTMLDivElement>) => {
    setKeyboard(false);
    const view = viewport.current;
    if (!view || event.button !== 0) return;
    drag.current = {
      start: { x: event.clientX, y: event.clientY },
      scroll: { x: view.scrollLeft, y: view.scrollTop },
      moved: false,
    };
  };
  const pointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const view = viewport.current;
    const current = drag.current;
    if (!view || !current) return;
    const dx = event.clientX - current.start.x;
    const dy = event.clientY - current.start.y;
    if (!current.moved) {
      if (Math.hypot(dx, dy) < DRAG_START) return;
      current.moved = true;
      view.setPointerCapture(event.pointerId);
      setPanning(true);
    }
    view.scrollLeft = current.scroll.x - dx;
    view.scrollTop = current.scroll.y - dy;
  };
  const pointerUp = () => {
    // A drag ends in a click on whatever is under the pointer; that click
    // doesn't select or toggle anything.
    swallowClick.current = drag.current?.moved ?? false;
    drag.current = null;
    setPanning(false);
  };
  const clickCapture = (event: { stopPropagation(): void }) => {
    if (!swallowClick.current) return;
    swallowClick.current = false;
    event.stopPropagation();
  };

  useLayoutEffect(() => {
    const element = map.current;
    if (!element) return;
    const draw = () => {
      const rect = (id: string): Rect | null => boxRect(element, id);
      const groups = new Map<
        string,
        {
          from: string;
          to: string;
          weight: number;
          role: ArrowRole;
          count: number;
        }
      >();
      const add = (
        from: string,
        to: string,
        weight: number,
        role: ArrowRole,
        count = 0,
      ) => {
        if (from === to) return;
        const key = `${from}>${to}>${role}`;
        const group = groups.get(key) ?? { from, to, weight: 0, role, count };
        group.weight += weight;
        groups.set(key, group);
      };
      const feedbackPairs = new Set(
        model.feedback.map((dep) => `${dep.sourceId}>${dep.targetId}`),
      );
      // A given dependency against the levels is drawn once, as given and
      // dashed, instead of as a feedback arrow.
      const givenPairs = new Set(
        given?.arrows.map((arrow) => `${arrow.fromBox}>${arrow.toBox}`) ?? [],
      );
      for (const arrow of given?.arrows ?? []) {
        add(arrow.fromBox, arrow.toBox, arrow.weight, "given", arrow.count);
      }
      if (!givenOnly) {
        for (const dep of model.feedback) {
          const pair = `${dep.sourceId}>${dep.targetId}`;
          if (!givenPairs.has(pair)) {
            add(dep.sourceId, dep.targetId, dep.weight, "feedback");
          }
        }
      }
      // A pair a feedback or a given arrow already joins gets no second one.
      if (focusId) {
        for (const link of links) {
          const pair = `${link.from}>${link.to}`;
          if (!feedbackPairs.has(pair) && !givenPairs.has(pair)) {
            add(link.from, link.to, link.weight, link.role);
          }
        }
      }
      const maxWeight = (role: ArrowRole) =>
        Math.max(
          1,
          ...[...groups.values()]
            .filter((group) =>
              role === "given"
                ? group.role === "given"
                : group.role === "uses" || group.role === "usedBy",
            )
            .map((group) => group.weight),
        );
      const specs: ArrowSpec[] = [];
      for (const [key, group] of groups) {
        const from = rect(group.from);
        const to = rect(group.to);
        if (!from || !to) continue;
        const touches =
          focusId !== undefined &&
          (isWithin(model, group.from, focusId) ||
            isWithin(model, group.to, focusId));
        const pair = `${group.from}>${group.to}`;
        const names = `${model.boxes.get(group.from)?.text} → ${model.boxes.get(group.to)?.text}`;
        specs.push({
          key,
          fromId: group.from,
          toId: group.to,
          from,
          to,
          role: group.role,
          against:
            group.role === "feedback" ||
            (group.role === "given" && feedbackPairs.has(pair)),
          weight: group.weight,
          width: dependencyArrowWidth(
            group.role,
            group.weight,
            maxWeight(group.role),
            touches,
          ),
          title:
            group.role === "given"
              ? `${names} · ${group.count} given ${group.count === 1 ? "dependency" : "dependencies"} · weight ${group.weight}`
              : `${names} · ${group.weight}`,
        });
      }
      const key = JSON.stringify(specs);
      const layout =
        lastLayout.current?.key === key
          ? lastLayout.current.layout
          : layoutArrows(specs);
      lastLayout.current = { key, layout };
      const next: Arrow[] = layout.arrows;
      setGutter((previous) =>
        previous.left === layout.gutterLeft && previous.right === layout.gutter
          ? previous
          : { left: layout.gutterLeft, right: layout.gutter },
      );
      setArrows((previous) =>
        JSON.stringify(previous) === JSON.stringify(next) ? previous : next,
      );
      // The map's own box, not its scroll size: that would include the
      // arrows' layer, which is as big as the last size and so never shrinks.
      setSize((previous) =>
        previous.width === element.offsetWidth &&
        previous.height === element.offsetHeight
          ? previous
          : { width: element.offsetWidth, height: element.offsetHeight },
      );
    };
    draw();
    const resizes = new ResizeObserver(draw);
    resizes.observe(element);
    return () => resizes.disconnect();
  }, [model, focusId, links, given, givenOnly]);

  useEffect(() => {
    if (!selectedId || scrolledTo.current === selectedId) return;
    const box = map.current?.querySelector(
      `[data-box-id="${CSS.escape(selectedId)}"]`,
    );
    if (!box) return;
    box.scrollIntoView({ block: "nearest", inline: "nearest" });
    scrolledTo.current = selectedId;
  });

  useEffect(() => {
    if (!revealCursor.current || cursorId === undefined) return;
    revealCursor.current = false;
    document
      .getElementById(domId(cursorId))
      ?.scrollIntoView({ block: "nearest", inline: "nearest" });
  });

  // Escape clears the selection, unless an open menu takes it: the menu
  // marks the Escape that closes it as handled.
  useEffect(() => {
    if (selectedId === undefined || menuTarget !== null) return;
    const onKey = async (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape" && !event.defaultPrevented) await select(null);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  });

  // The innermost box under the pointer.
  const boxAt = (target: EventTarget | null): string | null => {
    const box =
      target instanceof Element ? target.closest("[data-box-id]") : null;
    return box instanceof HTMLElement ? (box.dataset.boxId ?? null) : null;
  };

  // The arrow under the pointer.
  const arrowAt = (target: EventTarget | null): Arrow | null => {
    const line =
      target instanceof Element ? target.closest("[data-arrow-key]") : null;
    const key = line?.getAttribute("data-arrow-key");
    return arrows.find((arrow) => arrow.key === key) ?? null;
  };

  // A click selects the box under the pointer, or clears the selection if it
  // is the selected one or the canvas; on an arrow it does nothing. The
  // second click of a double-click is left to the double-click.
  const click = async (event: MouseEvent<HTMLDivElement>) => {
    if (event.button !== 0 || event.detail > 1) return;
    // Reserved for selecting more than one box.
    if (event.ctrlKey || event.metaKey) return;
    if (arrowAt(event.target)) return;
    const id = boxAt(event.target);
    if (id !== null) setCursor(id);
    if (pinned) return;
    await select(id === null || id === selectedId ? null : id);
  };
  // A double-click expands or collapses a container, and leaves it selected
  // (pinned, the selection stays as it is).
  const doubleClick = async (event: MouseEvent<HTMLDivElement>) => {
    if (event.button !== 0 || arrowAt(event.target)) return;
    const id = boxAt(event.target);
    if (id === null) return;
    if (model.boxes.get(id)?.container) await toggle(id);
    if (!pinned) await select(id);
  };

  // The arrow keys move the way the map is drawn: to the nearest box that
  // way (`boxToward`); Home and End to the first and the last box in reading
  // order; + and − expand and collapse; Enter and Space select; ctrl/⌘ +
  // +/−/0 zoom.
  const keyDown = async (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.defaultPrevented || event.altKey) return;
    if (event.ctrlKey || event.metaKey) {
      const zoomed = {
        "+": zoomAsked.current * ZOOM_STEP,
        "=": zoomAsked.current * ZOOM_STEP,
        "-": zoomAsked.current / ZOOM_STEP,
        "0": 1,
      }[event.key];
      if (zoomed === undefined) return;
      event.preventDefault();
      zoomTo(zoomed);
      return;
    }
    const order = boxOrder(model);
    if (order.length === 0) return;
    const at = cursorId;
    const box = at === undefined ? undefined : model.boxes.get(at);
    const start = selectedId ?? order[0];
    const direction = ARROW_DIRECTIONS[event.key];
    let next: string | undefined;
    if (direction !== undefined) {
      const element = map.current;
      next =
        at === undefined || !element
          ? start
          : boxToward(element, at, direction);
    } else {
      switch (event.key) {
        case "Home":
          next = order[0];
          break;
        case "End":
          next = order[order.length - 1];
          break;
        // Expands the box.
        case "+":
        case "=":
          event.preventDefault();
          setKeyboard(true);
          if (at !== undefined && box?.container && !expanded.has(at)) {
            await toggle(at);
          }
          return;
        // Collapses the box, or, in a box that isn't open, its container,
        // which the cursor moves to.
        case "-": {
          event.preventDefault();
          setKeyboard(true);
          if (at === undefined || !box) return;
          if (box.container && expanded.has(at)) {
            await toggle(at);
            return;
          }
          if (box.parentId === null) return;
          setCursor(box.parentId);
          await toggle(box.parentId);
          return;
        }
        case "Enter":
        case " ":
          event.preventDefault();
          setKeyboard(true);
          if (at !== undefined && !pinned) {
            await select(at === selectedId ? null : at);
          }
          return;
        default:
          return;
      }
    }
    event.preventDefault();
    setKeyboard(true);
    if (next === undefined) return;
    revealCursor.current = true;
    setCursor(next);
  };

  const rows = model.rowsOf.get(model.rootId) ?? [];
  const paths = arrows.map((arrow) => (
    <ArrowPath key={arrow.key} arrow={arrow} heads={heads} />
  ));
  return (
    <div className="lsm-viewport">
      <ContextMenu
        onOpenChange={(open) => {
          if (!open) setMenuTarget(null);
        }}
      >
        <ContextMenuTrigger asChild>
          <div
            ref={viewport}
            role="tree"
            aria-label="Levelized structure map"
            tabIndex={0}
            aria-activedescendant={
              cursorId === undefined ? undefined : domId(cursorId)
            }
            className={twMerge(
              "lsm-canvas",
              panning && "lsm-panning",
              keyboard && "lsm-kbd",
            )}
            onPointerDown={pointerDown}
            onPointerMove={pointerMove}
            onPointerUp={pointerUp}
            onPointerCancel={pointerUp}
            onClickCapture={clickCapture}
            onClick={click}
            onDoubleClick={doubleClick}
            onKeyDown={keyDown}
            onContextMenu={(event) => {
              // A box, an arrow and the canvas around them have a menu.
              const arrow = arrowAt(event.target);
              const id = boxAt(event.target);
              if (arrow) setMenuTarget({ kind: "arrow", arrow });
              else if (id !== null) setMenuTarget({ kind: "box", id });
              else setMenuTarget({ kind: "canvas" });
            }}
          >
            <div
              className="lsm-sizer"
              style={{
                width: `${size.width * zoom}px`,
                height: `${size.height * zoom}px`,
              }}
            >
              <div
                ref={map}
                className="lsm-map"
                style={{
                  width: viewWidth > 0 ? `${viewWidth}px` : undefined,
                  transform: `scale(${zoom})`,
                  paddingLeft: `${Math.max(8, gutter.left + 14)}px`,
                  paddingRight: `${Math.max(16, gutter.right + 14)}px`,
                }}
              >
                <MapRows rows={rows} />
                <svg
                  className="lsm-arrows"
                  width={size.width}
                  height={size.height}
                  aria-hidden
                >
                  <DependencyArrowHeads id={heads} />
                  {paths}
                </svg>
              </div>
            </div>
          </div>
        </ContextMenuTrigger>
        <ContextMenuContent
          onCloseAutoFocus={(event) => event.preventDefault()}
        >
          {menuTarget?.kind === "box" && <BoxMenu id={menuTarget.id} />}
          {menuTarget?.kind === "canvas" && <CanvasMenu />}
          {menuTarget?.kind === "arrow" && (
            <ArrowMenu arrow={menuTarget.arrow} />
          )}
        </ContextMenuContent>
      </ContextMenu>
      <ZoomControls
        zoom={zoom}
        onZoomOut={() => zoomTo(zoom / ZOOM_STEP)}
        onZoomIn={() => zoomTo(zoom * ZOOM_STEP)}
        onReset={() => zoomTo(1)}
        onFit={fit}
        onSelection={selectedId === undefined ? undefined : zoomToSelection}
        onGiven={hasGivenArrows ? zoomToGiven : undefined}
      />
    </div>
  );
}

type Direction = "left" | "right" | "up" | "down";

const ARROW_DIRECTIONS: Record<string, Direction | undefined> = {
  ArrowLeft: "left",
  ArrowRight: "right",
  ArrowUp: "up",
  ArrowDown: "down",
};

// How far apart two ranges are: 0 where they overlap.
function rangeGap(a0: number, a1: number, b0: number, b1: number): number {
  return Math.max(0, b0 - a1, a0 - b1);
}

// The box an arrow key moves to from `fromId`: the nearest box that lies
// that way as the map is drawn, nearest along the way and, weighed double,
// across it. An open container counts by its name bar, so ↓ from it enters
// its top row and ↑ from its top row leaves it; ← and → stay in the row as
// long as it has boxes that way. Equally near, the first in the map wins.
function boxToward(
  element: HTMLElement,
  fromId: string,
  direction: Direction,
): string | undefined {
  const anchors = new Map<string, DOMRect>();
  for (const box of element.querySelectorAll<HTMLElement>("[data-box-id]")) {
    const header = box.querySelector<HTMLElement>(":scope > .lsm-hd");
    anchors.set(
      box.dataset.boxId ?? "",
      (header ?? box).getBoundingClientRect(),
    );
  }
  const from = anchors.get(fromId);
  if (!from) return undefined;
  const horizontal = direction === "left" || direction === "right";
  let best: string | undefined;
  let bestScore = Infinity;
  for (const [id, rect] of anchors) {
    if (id === fromId) continue;
    const along = {
      right: rect.left - from.right,
      left: from.left - rect.right,
      down: rect.top - from.bottom,
      up: from.top - rect.bottom,
    }[direction];
    // Not that way (a pixel of overlap allowed).
    if (along < -1) continue;
    const across = horizontal
      ? rangeGap(from.top, from.bottom, rect.top, rect.bottom)
      : rangeGap(from.left, from.right, rect.left, rect.right);
    const score = Math.max(0, along) + 2 * across;
    if (score < bestScore) {
      best = id;
      bestScore = score;
    }
  }
  return best;
}

// A box's rectangle in the map's own units (it is drawn scaled), in whole
// pixels: unscaling leaves fractions that differ from zoom to zoom, and would
// lay the same arrows out again.
function boxRect(element: HTMLElement, id: string): Rect | null {
  const box = element.querySelector(`[data-box-id="${CSS.escape(id)}"]`);
  if (!box) return null;
  const base = element.getBoundingClientRect();
  const scale = element.offsetWidth > 0 ? base.width / element.offsetWidth : 1;
  const r = box.getBoundingClientRect();
  return {
    l: Math.round((r.left - base.left) / scale),
    t: Math.round((r.top - base.top) / scale),
    r: Math.round((r.right - base.left) / scale),
    b: Math.round((r.bottom - base.top) / scale),
  };
}

type MenuTarget =
  | { kind: "box"; id: string }
  | { kind: "arrow"; arrow: Arrow }
  | { kind: "canvas" };

// The canvas's menu, around the boxes: collapse the whole map.
function CanvasMenu() {
  const { expanded, collapseEverything } = useMap();
  return (
    <ContextMenuItem
      disabled={expanded.size === 0}
      onSelect={() => collapseEverything()}
    >
      <ListChevronsDownUp />
      Collapse all
    </ContextMenuItem>
  );
}

type BoxMenuProps = {
  id: string;
};

// A box's menu: select it, and expand or collapse a container, one level or
// all of it (what doesn't apply disabled), each with its gesture.
function BoxMenu({ id }: BoxMenuProps) {
  const { model, expanded, toggle, expandAll, collapseAll, expandedWithin } =
    useMap();
  const container = model.boxes.get(id)?.container ?? false;
  const isExpanded = expanded.has(id);
  const anyExpanded = expandedWithin(id).length > 0;
  return (
    <>
      <SelectItem id={id} />
      {container && (
        <>
          <ContextMenuSeparator />
          <ContextMenuItem disabled={isExpanded} onSelect={() => toggle(id)}>
            <SquarePlus />
            Expand
            <ContextMenuShortcut>+ / Double-click</ContextMenuShortcut>
          </ContextMenuItem>
          <ContextMenuItem onSelect={() => expandAll(id)}>
            <ListChevronsUpDown />
            Expand all
          </ContextMenuItem>
          <ContextMenuItem disabled={!isExpanded} onSelect={() => toggle(id)}>
            <SquareMinus />
            Collapse
            <ContextMenuShortcut>− / Double-click</ContextMenuShortcut>
          </ContextMenuItem>
          <ContextMenuItem
            disabled={!anyExpanded}
            onSelect={() => collapseAll(id)}
          >
            <ListChevronsDownUp />
            Collapse all
          </ContextMenuItem>
        </>
      )}
    </>
  );
}

type ArrowMenuProps = {
  arrow: Arrow;
};

// An arrow's menu: what it joins and how heavily, the selection to clear,
// its ends to select instead, and its collapsed ends to expand.
function ArrowMenu({ arrow }: ArrowMenuProps) {
  const { model, expanded, focusId, select, toggle } = useMap();
  const from = model.boxes.get(arrow.fromId);
  const to = model.boxes.get(arrow.toId);
  const ends = [from, to].filter((box) => box !== undefined);
  const selectable = ends.filter((box) => box.id !== focusId);
  const expandable = ends.filter(
    (box) => box.container && !expanded.has(box.id),
  );
  const selectItems = selectable.map((box) => (
    <ContextMenuItem key={box.id} onSelect={() => select(box.id)}>
      <MousePointerClick />
      <span className="truncate">Select {box.label}</span>
    </ContextMenuItem>
  ));
  const expandItems = expandable.map((box) => (
    <ContextMenuItem key={box.id} onSelect={() => toggle(box.id)}>
      <SquarePlus />
      <span className="truncate">Expand {box.label}</span>
    </ContextMenuItem>
  ));
  return (
    <>
      <div className="text-fg-subtle px-2 py-1 font-mono text-[11.5px]">
        {from?.label} → {to?.label} · {arrow.weight}
      </div>
      <ContextMenuSeparator />
      {focusId !== undefined && <SelectItem id={focusId} />}
      {selectItems}
      {expandItems.length > 0 && <ContextMenuSeparator />}
      {expandItems}
    </>
  );
}

type SelectItemProps = {
  id: string;
};

// Selects the box (instead of any selected before), or clears the selection
// if it is the selected one.
function SelectItem({ id }: SelectItemProps) {
  const { selectedId, select } = useMap();
  const isSelected = selectedId === id;
  return (
    <ContextMenuItem
      onSelect={() => select(isSelected ? null : id)}
      aria-keyshortcuts={isSelected ? "Escape" : undefined}
    >
      {isSelected ? <X /> : <MousePointerClick />}
      {isSelected ? "Clear selection" : "Select"}
      <ContextMenuShortcut>{isSelected ? "Esc" : "Click"}</ContextMenuShortcut>
    </ContextMenuItem>
  );
}

type ZoomControlsProps = {
  zoom: number;
  onZoomOut: () => void;
  onZoomIn: () => void;
  onReset: () => void;
  onFit: () => void;
  // Zooms to the selection; without one, the control is disabled.
  onSelection: (() => void) | undefined;
  // Zooms to the given dependencies; without them, no such control.
  onGiven: (() => void) | undefined;
};

function ZoomControls({
  zoom,
  onZoomOut,
  onZoomIn,
  onReset,
  onFit,
  onSelection,
  onGiven,
}: ZoomControlsProps) {
  return (
    <div role="group" aria-label="Zoom" className="lsm-zoom">
      <ZoomButton
        icon={ZoomOut}
        label="Zoom out"
        disabled={zoom <= MIN_ZOOM}
        onPress={onZoomOut}
      />
      <button
        type="button"
        title="Reset the zoom to 100%"
        onClick={onReset}
        className={twMerge(
          ghostIconTriggerClassName,
          "w-11 font-mono text-[11px]",
        )}
      >
        {Math.round(zoom * 100)}%
      </button>
      <ZoomButton
        icon={ZoomIn}
        label="Zoom in"
        disabled={zoom >= MAX_ZOOM}
        onPress={onZoomIn}
      />
      <ZoomButton icon={Maximize} label="Fit the map" onPress={onFit} />
      <ZoomButton
        icon={Focus}
        label="Zoom to the selection"
        disabled={onSelection === undefined}
        onPress={() => onSelection?.()}
      />
      {onGiven && (
        <ZoomButton
          icon={Crosshair}
          label="Zoom to the dependencies"
          onPress={onGiven}
        />
      )}
    </div>
  );
}

type ZoomButtonProps = {
  icon: LucideIcon;
  label: string;
  disabled?: boolean;
  onPress: () => void;
};

function ZoomButton({ icon: Icon, label, disabled, onPress }: ZoomButtonProps) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onPress}
      className={ghostIconTriggerClassName}
    >
      <Icon className="size-3.5" />
    </button>
  );
}

type ArrowPathProps = {
  arrow: Arrow;
  // The id of the SVG's arrowheads.
  heads: string;
};

function ArrowPath({ arrow, heads }: ArrowPathProps) {
  return (
    <DependencyArrow
      d={arrow.d}
      role={arrow.role}
      against={arrow.against}
      width={arrow.width}
      heads={heads}
      title={arrow.title}
      data-arrow-key={arrow.key}
    />
  );
}

type MapRowsProps = {
  rows: MapItem[][];
};

// A container's rows, top first: the highest level at the top.
function MapRows({ rows }: MapRowsProps) {
  const rowElements = rows.map((row, index) => (
    <MapRow key={index} level={rows.length - 1 - index} items={row} />
  ));
  return <div className="lsm-rows">{rowElements}</div>;
}

type MapRowProps = {
  level: number;
  items: MapItem[];
};

function MapRow({ level, items }: MapRowProps) {
  const { model } = useMap();
  const weightOf = (id: string) => model.weights.get(id) ?? 1;
  const cells = items.map((item) =>
    item.kind === "box" ? (
      <MapCell key={item.id} weight={itemWeight(item, weightOf)}>
        <MapBoxView id={item.id} />
      </MapCell>
    ) : (
      <MapCell
        key={`tangle:${item.rows.flat()[0]}`}
        weight={itemWeight(item, weightOf)}
      >
        <TangleView rows={item.rows} />
      </MapCell>
    ),
  );
  return (
    <div className="lsm-row" role="group" aria-label={`Level ${level}`}>
      {cells}
    </div>
  );
}

type MapCellProps = {
  // Its share of the row: how many boxes are visible in it.
  weight: number;
  children: ReactNode;
};

// A box's place in its row: a share of the row by its weight, and never
// narrower than its content, so a name is cut off only when the map is wider
// than the screen (it scrolls then).
function MapCell({ weight, children }: MapCellProps) {
  return (
    <div className="lsm-item" style={{ flexGrow: weight }}>
      {children}
    </div>
  );
}

type TangleViewProps = {
  rows: string[][];
};

// The members of a cycle in their own rows, top first, inside a red frame.
function TangleView({ rows }: TangleViewProps) {
  const rowElements = rows.map((ids, index) => (
    <TangleRow key={index} ids={ids} />
  ));
  return (
    <div className="lsm-tangle" role="group" aria-label="Tangle">
      <div className="lsm-rows">{rowElements}</div>
    </div>
  );
}

type TangleRowProps = {
  ids: string[];
};

function TangleRow({ ids }: TangleRowProps) {
  const { model } = useMap();
  const cells = ids.map((id) => (
    <MapCell key={id} weight={model.weights.get(id) ?? 1}>
      <MapBoxView id={id} />
    </MapCell>
  ));
  return <div className="lsm-row">{cells}</div>;
}

// A package gets a small package glyph before its name, which also tells
// packages from modules, types and External Types.
// The start screen's tree icons, by what a box is.
const KIND_ICONS: Record<StructureKind, LucideIcon> = {
  group: Layers,
  module: Package,
  external: Globe,
  package: Folder,
  type: FileCode,
  member: CircleDot,
  other: CircleDot,
};

type KindIconProps = {
  type: string;
  text: string;
};

function KindIcon({ type, text }: KindIconProps) {
  const kind = structureKind(type, text);
  const Icon = KIND_ICONS[kind];
  return (
    <Icon
      aria-hidden
      className={twMerge(
        "lsm-ic text-fg-subtle",
        kind === "group" && "text-primary",
      )}
    />
  );
}

type MapBoxViewProps = {
  id: string;
};

// One box: a leaf (a collapsed container, a type) with its name centred, or
// an open container with its name on top and its rows inside. The corner
// toggle expands and collapses a container; it is no tab stop, the keyboard
// expands with → and ←.
function MapBoxView({ id }: MapBoxViewProps) {
  const {
    model,
    expanded,
    focusId,
    selectedId,
    cursorId,
    domId,
    uses,
    usedBy,
    dimmed,
    toggle,
  } = useMap();
  const box = model.boxes.get(id);
  if (!box) return null;
  const rows = model.rowsOf.get(id);
  const open = rows !== undefined;
  const loading = box.container && expanded.has(id) && !open;
  const toggleButton = box.container && (
    <button
      type="button"
      className="lsm-tg"
      aria-label={`${open ? "Collapse" : "Expand"} ${box.label}`}
      aria-expanded={open}
      tabIndex={-1}
      onClick={async (event) => {
        event.stopPropagation();
        await toggle(id);
      }}
      // Clicked twice, it toggles twice: the box's double-click is not.
      onDoubleClick={(event) => event.stopPropagation()}
    >
      {open ? "−" : "+"}
    </button>
  );
  const name = (
    <span className="lsm-nm">
      <KindIcon type={box.type} text={box.text} />
      {box.label}
    </span>
  );
  return (
    <div
      role="treeitem"
      id={domId(id)}
      aria-label={box.label}
      aria-selected={selectedId === id}
      aria-current={focusId === id || undefined}
      aria-expanded={box.container ? open : undefined}
      aria-busy={loading || undefined}
      data-box-id={id}
      title={`${box.text}\n${box.type} · level ${box.level}${box.inTangle ? " · in a tangle" : ""}`}
      className={twMerge(
        "lsm-box",
        open ? "lsm-cont" : "lsm-leaf",
        box.depth % 2 === 1 && "lsm-odd",
        box.external && "lsm-ext",
        box.container && !open && "lsm-hastg",
        focusId === id && "lsm-sel",
        cursorId === id && "lsm-cursor",
        uses.has(id) && "lsm-uses",
        usedBy.has(id) && "lsm-usedby",
        dimmed(id) && "lsm-dim",
        loading && "opacity-60",
      )}
    >
      {open ? (
        <>
          <div className="lsm-hd">
            {toggleButton}
            {name}
          </div>
          <MapRows rows={rows} />
        </>
      ) : (
        <>
          {toggleButton}
          {name}
        </>
      )}
    </div>
  );
}
