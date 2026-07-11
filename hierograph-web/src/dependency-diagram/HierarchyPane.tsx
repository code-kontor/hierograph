import { useQuery, useQueryClient } from "@tanstack/react-query";

import {
  nodeChildrenQueryOptions,
  rootNodeQueryOptions,
} from "@/graph/queries";
import type { RootNodeQuery } from "@/graphql/generated/graphql";
import { useSelection } from "@/selection/SelectionContext";
import { AsyncTree, type TreeNodeData } from "@/tree/AsyncTree";
import type { TreeSettings } from "@/tree/useTreeSettings";

type RootNode = NonNullable<
  NonNullable<RootNodeQuery["hierarchicalGraph"]>["rootNode"]
>;

type HierarchyPaneProps = {
  settings: TreeSettings;
};

// Self-contained tree wiring for this screen (root-node fetch, lazy children,
// selection wiring) — mirrors dsm's HierarchyTree, but built directly on the
// shared `tree`/`graph` primitives rather than importing another screen
// vertical's internals (screen verticals never import each other).
export function HierarchyPane({ settings }: HierarchyPaneProps) {
  const { data, isPending, isError, error } = useQuery(rootNodeQueryOptions());

  if (isPending) {
    return <p className="text-muted-foreground text-sm">Loading root node…</p>;
  }

  if (isError || !data.hierarchicalGraph?.rootNode) {
    console.error(error);

    return (
      <div className="border-destructive/50 max-w-md rounded-lg border p-4 text-sm">
        <p className="text-destructive font-medium">
          Could not load the root node.
        </p>
        <p className="text-muted-foreground mt-1">
          Make sure the hierograph MCP server is running on
          http://localhost:8080 and is serving a store.
        </p>
      </div>
    );
  }

  return (
    <HierarchyPaneInner
      rootNode={data.hierarchicalGraph.rootNode}
      settings={settings}
    />
  );
}

type HierarchyPaneInnerProps = {
  rootNode: RootNode;
  settings: TreeSettings;
};

function HierarchyPaneInner({ rootNode, settings }: HierarchyPaneInnerProps) {
  const queryClient = useQueryClient();
  const {
    selectedIds,
    focusedName,
    setSelectedIds,
    setFocusedId,
    setFocusedName,
  } = useSelection();

  const loadChildren = async (id: string): Promise<TreeNodeData[]> => {
    const result = await queryClient.ensureQueryData(
      nodeChildrenQueryOptions(id),
    );
    return result.hierarchicalGraph?.node?.children.nodes ?? [];
  };

  const handleFocusedIdChange = (id: string | null, name: string | null) => {
    setFocusedId(id);
    setFocusedName(name);
  };

  return (
    <div className="flex h-full flex-col">
      <div className="min-h-0 flex-1 overflow-auto p-3">
        <AsyncTree
          rootNode={rootNode}
          loadChildren={loadChildren}
          onSelectedIdsChange={setSelectedIds}
          onFocusedIdChange={handleFocusedIdChange}
          label="Hierarchy"
          settings={settings}
          autoExpandOnLoad="root-chain"
        />
      </div>
      <div className="bg-panel-header border-border flex shrink-0 items-center gap-[14px] border-t px-3 py-[6px] font-mono text-[11px] text-[var(--hg-fg-subtle)]">
        <span>{selectedIds.length} selected</span>
        <span className="text-[var(--hg-border-strong)]">·</span>
        <span>
          focus:{" "}
          <span className="text-[var(--hg-fg-muted)]">{focusedName ?? ""}</span>
        </span>
        <span className="flex-1" />
        <span>click = select · ⌘-click = multi · ▸ = expand</span>
      </div>
    </div>
  );
}
