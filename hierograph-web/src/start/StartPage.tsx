import { useQuery } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { PanelLeftOpen, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { ImperativePanelHandle } from "react-resizable-panels";
import { twMerge } from "tailwind-merge";

import { GesturesHelp } from "@/design-system/graph/GesturesHelp";
import { treeNodeLabel } from "@/design-system/graph/treeLabels";
import { Pane } from "@/design-system/layout/Pane";
import { ghostIconTriggerClassName } from "@/design-system/ui/dropdown-menu";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/design-system/ui/resizable";
import { useMediaQuery } from "@/design-system/useMediaQuery";
import { type GraphScope, useGraphScope } from "@/frame/GraphScope";

import { DependencyListPane } from "./DependencyList";
import {
  DEPENDENCY_GESTURES,
  DEPENDENCY_LEGEND,
  DependencyTrees,
  DirectionModeSwitch,
  PICK_PARAMS,
  TREE_ROW_PARAMS,
} from "./DependencyTrees";
import { HierarchyTree } from "./HierarchyTree";
import { dependencySetQueryOptions } from "./queries";

// The dependency list's tabs, for the start route's `dependency_list`.
export { DEPENDENCY_LIST_TABS } from "./DependencyList";
export { DIRECTION_MODES } from "./DependencyTrees";

// The start screen: the hierarchy tree of the current hierarchy view, and
// beside it the dependencies of its selection (#77), as three trees and,
// below them, as a list. The view is chosen in the
// header, and the server's state is in the header's status panel. It needs no
// graph: the tree says when there is none.
export function StartPage() {
  // Tailwind's `lg`.
  const wide = useMediaQuery("(min-width: 64rem)");
  const tree = (
    // As tall as the space it is given, scrolling on its own.
    <div className="relative h-full min-h-[480px] min-w-0">
      <div className="absolute inset-0">
        <HierarchyTree />
      </div>
    </div>
  );
  return wide ? (
    <WideLayout />
  ) : (
    <div className="text-fg grid w-full grid-cols-1 gap-3">
      {tree}
      <div className="h-[420px]">
        <DependencyPane />
      </div>
      <div className="h-[320px]">
        <DependencyListPane />
      </div>
    </div>
  );
}

// The width of the collapsed hierarchy.
const RAIL_WIDTH = 28;

// On a wide screen the tree on the left; on the right the dependencies, the
// trees above and their list below; all as tall as the window. The splits are
// moved by the user (and remembered by the browser, the hierarchy's collapse
// too). The hierarchy collapses to a rail, to make room for the dependencies:
// with its button, by dragging the splitter to the edge, or by
// double-clicking the splitter; the rail, or a double-click, brings it back.
function WideLayout() {
  const hierarchy = useRef<ImperativePanelHandle>(null);
  const navigate = useNavigate();
  const search: { hierarchy_collapsed?: unknown } = useSearch({
    strict: false,
  });
  const wanted = search.hierarchy_collapsed === true;
  // The panel's own state, for the rail; the URL says what it should be.
  const [collapsed, setCollapsed] = useState(false);
  const last = useRef<boolean | null>(null);
  const collapse = () => hierarchy.current?.collapse();
  const expand = () => hierarchy.current?.expand();
  // The URL wins: a link (or Back) collapses or expands the panel, whatever
  // the browser remembered of the layout.
  useEffect(() => {
    const panel = hierarchy.current;
    if (!panel) return;
    if (wanted && !panel.isCollapsed()) panel.collapse();
    else if (!wanted && panel.isCollapsed()) panel.expand();
  }, [wanted]);
  // A collapse or an expansion, however done, goes into the URL
  // (`hierarchy_collapsed`), replacing the history entry.
  const resized = async (size: number) => {
    const now = size === 0;
    if (last.current === now) return;
    last.current = now;
    setCollapsed(now);
    await navigate({
      to: ".",
      search: (previous: Record<string, unknown>) => ({
        ...previous,
        hierarchy_collapsed: now || undefined,
      }),
      replace: true,
    } as never);
  };
  return (
    <div className="text-fg flex h-full w-full min-w-0">
      {collapsed && <HierarchyRail onExpand={expand} />}
      <ResizablePanelGroup direction="horizontal" autoSaveId="start-columns">
        <ResizablePanel
          ref={hierarchy}
          id="tree"
          order={1}
          // Narrow by default: the dependencies get the room (a layout the
          // browser kept wins).
          defaultSize={30}
          minSize={20}
          collapsible
          collapsedSize={0}
          onResize={resized}
        >
          {!collapsed && (
            <div className="relative h-full min-w-0">
              <div className="absolute inset-0">
                <HierarchyTree onCollapse={collapse} />
              </div>
            </div>
          )}
        </ResizablePanel>
        <ResizableHandle
          aria-label="Resize the hierarchy"
          title="Drag to resize, double-click to collapse or expand the hierarchy"
          className={
            collapsed
              ? "mr-0.5 ml-1.5 w-1.5 rounded-[4px] after:absolute after:-inset-x-[3px] after:inset-y-0"
              : "mx-0.5 w-1.5 rounded-[4px] after:absolute after:-inset-x-[3px] after:inset-y-0"
          }
          onDoubleClick={collapsed ? expand : collapse}
        />
        <ResizablePanel
          id="dependencies"
          order={2}
          defaultSize={70}
          minSize={20}
        >
          <ResizablePanelGroup
            direction="vertical"
            autoSaveId="start-dependencies"
          >
            <ResizablePanel id="trees" order={1} defaultSize={40} minSize={20}>
              <DependencyPane />
            </ResizablePanel>
            <ResizableHandle
              aria-label="Resize the dependency list"
              // Drawn 6 px; the pointer finds it in 12 px.
              className="my-0.5 rounded-[4px] after:absolute after:inset-x-0 after:-inset-y-[3px] data-[panel-group-direction=vertical]:h-1.5"
            />
            <ResizablePanel id="list" order={2} defaultSize={60} minSize={15}>
              <DependencyListPane />
            </ResizablePanel>
          </ResizablePanelGroup>
        </ResizablePanel>
      </ResizablePanelGroup>
    </div>
  );
}

type HierarchyRailProps = {
  onExpand: () => void;
};

// The collapsed hierarchy: a narrow bar that brings it back, naming the
// selection the dependencies are of, written upwards.
function HierarchyRail({ onExpand }: HierarchyRailProps) {
  const scope = useGraphScope();
  const search: { selected_id?: unknown } = useSearch({ strict: false });
  const selectedId =
    search.selected_id === undefined || search.selected_id === null
      ? undefined
      : String(search.selected_id);
  return (
    <button
      type="button"
      aria-label="Expand the hierarchy"
      title="Expand the hierarchy"
      onClick={onExpand}
      style={{ width: RAIL_WIDTH }}
      className="bg-panel border-border-strong hover:bg-state-hover text-fg-muted flex h-full shrink-0 flex-col items-center gap-2 overflow-hidden rounded-[8px] border py-2 shadow-[var(--hg-shadow)]"
    >
      <PanelLeftOpen aria-hidden className="size-4 shrink-0" />
      <span className="rotate-180 font-mono text-[12px] tracking-[0.06em] uppercase [writing-mode:vertical-rl]">
        Hierarchy
      </span>
      {scope && selectedId !== undefined && (
        <SelectionName scope={scope} id={selectedId} />
      )}
    </button>
  );
}

type SelectionNameProps = {
  scope: GraphScope;
  id: string;
};

// The selection's name, as the dependency pane has it (the same query).
function SelectionName({ scope, id }: SelectionNameProps) {
  const query = useQuery(dependencySetQueryOptions(scope, id));
  const selection = query.data?.selection;
  if (!selection) return null;
  return (
    <span
      data-testid="rail-selection"
      className="text-fg min-h-0 rotate-180 truncate font-mono text-[12px] [writing-mode:vertical-rl]"
    >
      {treeNodeLabel(selection.type, selection.text, undefined)}
    </span>
  );
}

// The dependency pane's title: whose dependencies these are, so the trees
// can't be taken for the hierarchy (with the hierarchy collapsed, "Used by"
// sits where it was).
function DependencyPaneTitle() {
  const scope = useGraphScope();
  const search: { selected_id?: unknown; selected_cycle_ids?: unknown } =
    useSearch({ strict: false });
  const navigate = useNavigate();
  const selectedId =
    search.selected_id === undefined || search.selected_id === null
      ? undefined
      : String(search.selected_id);
  const anything =
    selectedId !== undefined || search.selected_cycle_ids !== undefined;
  // As clearing it in the hierarchy tree: the picks and the trees' open rows
  // go with it.
  const clear = async () => {
    await navigate({
      to: ".",
      search: (previous: Record<string, unknown>) => ({
        ...previous,
        selected_id: undefined,
        selected_cycle_ids: undefined,
        // Back to both directions.
        dependency_directions: undefined,
        ...Object.fromEntries(
          [...Object.values(PICK_PARAMS), ...TREE_ROW_PARAMS].map((param) => [
            param,
            undefined,
          ]),
        ),
      }),
      replace: true,
    } as never);
  };
  return (
    <span className="flex min-w-0 items-center gap-1">
      <span role="heading" aria-level={2} className="truncate">
        Dependencies
        {scope && selectedId !== undefined && (
          <PaneSelectionName scope={scope} id={selectedId} />
        )}
      </span>
      {anything && (
        <button
          type="button"
          aria-label="Clear selection"
          title="Clear selection (Esc)"
          onClick={clear}
          className={twMerge(
            ghostIconTriggerClassName,
            "size-6 shrink-0 normal-case",
          )}
        >
          <X className="size-3.5" />
        </button>
      )}
    </span>
  );
}

// " of <name>", the name as written, not in the title's capitals.
function PaneSelectionName({ scope, id }: SelectionNameProps) {
  const query = useQuery(dependencySetQueryOptions(scope, id));
  const selection = query.data?.selection;
  if (!selection) return null;
  return (
    <>
      {" of "}
      <span className="text-fg normal-case">
        {treeNodeLabel(selection.type, selection.text, undefined)}
      </span>
    </>
  );
}

// The dependencies of the tree's selection: what uses it, the selection, and
// what it uses, as three trees.
function DependencyPane() {
  return (
    <Pane
      title={<DependencyPaneTitle />}
      plainHeader
      toolbar={
        <>
          <DirectionModeSwitch />
          <GesturesHelp
            gestures={DEPENDENCY_GESTURES}
            legend={DEPENDENCY_LEGEND}
          />
        </>
      }
      bodyClassName="flex flex-col"
    >
      <div className="min-h-0 flex-1">
        <DependencyTrees />
      </div>
    </Pane>
  );
}
