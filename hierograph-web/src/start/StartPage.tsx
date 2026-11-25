import { useNavigate, useSearch } from "@tanstack/react-router";
import type { ReactNode } from "react";

import { Pane } from "@/design-system/layout/Pane";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/design-system/ui/resizable";
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "@/design-system/ui/tabs";
import { useMediaQuery } from "@/design-system/useMediaQuery";

import { DependencyTrees } from "./DependencyTrees";
import { HierarchyTree } from "./HierarchyTree";

// The start screen: the hierarchy tree of the current hierarchy view, and
// beside it the dependencies of its selection (#77). The view is chosen in the
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
  const dependencies = <DependencyTabs />;
  return wide ? (
    <WideLayout tree={tree} dependencies={dependencies} />
  ) : (
    <div className="text-fg grid w-full grid-cols-1 gap-3">
      {tree}
      <div className="min-h-[320px]">{dependencies}</div>
    </div>
  );
}

type WideLayoutProps = {
  tree: ReactNode;
  dependencies: ReactNode;
};

// On a wide screen the tree on the left, the dependencies on the right, both
// as tall as the window. The split is moved by the user (and remembered by the
// browser).
function WideLayout({ tree, dependencies }: WideLayoutProps) {
  return (
    <ResizablePanelGroup
      direction="horizontal"
      autoSaveId="start-columns"
      className="text-fg"
    >
      <ResizablePanel id="tree" order={1} defaultSize={50} minSize={20}>
        {tree}
      </ResizablePanel>
      <ResizableHandle
        aria-label="Resize the hierarchy"
        className="mx-0.5 rounded-[4px]"
      />
      <ResizablePanel id="dependencies" order={2} defaultSize={50} minSize={20}>
        {dependencies}
      </ResizablePanel>
    </ResizablePanelGroup>
  );
}

// The dependency pane's tabs, in order. The id is the `dependencies` URL
// parameter (validated by the route); without one the first tab shows.
export const DEPENDENCY_TABS = ["incoming", "outgoing"] as const;
export type DependencyTab = (typeof DEPENDENCY_TABS)[number];

const TAB_LABELS: Record<DependencyTab, string> = {
  incoming: "Incoming dependencies",
  outgoing: "Outgoing dependencies",
};

function isDependencyTab(value: unknown): value is DependencyTab {
  return (DEPENDENCY_TABS as readonly unknown[]).includes(value);
}

// The dependencies of the tree's selection, incoming and outgoing, one tab
// each. The tab is navigation state (`dependencies=`), so a link can open
// one; switching replaces the history entry, and the first tab leaves no
// param behind.
function DependencyTabs() {
  const search: { dependencies?: unknown } = useSearch({ strict: false });
  const navigate = useNavigate();
  const tab = isDependencyTab(search.dependencies)
    ? search.dependencies
    : DEPENDENCY_TABS[0];
  const select = async (value: string) => {
    if (!isDependencyTab(value)) return;
    await navigate({
      to: "/",
      search: (previous) => ({
        ...previous,
        dependencies: value === DEPENDENCY_TABS[0] ? undefined : value,
      }),
      replace: true,
    });
  };
  const triggers = DEPENDENCY_TABS.map((id) => (
    <DependencyTabTrigger key={id} id={id} />
  ));
  return (
    <Tabs value={tab} onValueChange={select} className="h-full">
      <Pane
        title="Dependencies"
        titleBar={<TabsList className="h-full">{triggers}</TabsList>}
        bodyClassName="flex flex-col"
      >
        <TabsContent value="incoming" className="min-h-0 flex-1">
          <DependencyTrees direction="incoming" />
        </TabsContent>
        <TabsContent value="outgoing" className="min-h-0 flex-1">
          <DependencyTrees direction="outgoing" />
        </TabsContent>
      </Pane>
    </Tabs>
  );
}

type DependencyTabTriggerProps = {
  id: DependencyTab;
};

function DependencyTabTrigger({ id }: DependencyTabTriggerProps) {
  return <TabsTrigger value={id}>{TAB_LABELS[id]}</TabsTrigger>;
}
