import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback } from "react";

import {
  nodeChildrenQueryOptions,
  rootNodeQueryOptions,
} from "@/graph/queries";
import type { RootNodeQuery } from "@/graphql/generated/graphql";
import { useSelection } from "@/selection/SelectionContext";
import { AsyncTree, type TreeNodeData } from "@/tree/AsyncTree";
import type { TreeSettings } from "@/tree/useTreeSettings";

import { TreeFooter } from "./TreeFooter";

type RootNode = NonNullable<
  NonNullable<RootNodeQuery["hierarchicalGraph"]>["rootNode"]
>;

type HierarchyTreeProps = {
  settings: TreeSettings;
};

export function HierarchyTree({ settings }: HierarchyTreeProps) {
  const { data, isPending, isError, error } = useQuery(rootNodeQueryOptions());

  if (isPending) {
    return <p className="text-muted-foreground text-sm">Loading root node…</p>;
  }

  if (isError || !data.hierarchicalGraph?.rootNode) {
    console.log(error);

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
    <HierarchyTreeInner
      rootNode={data.hierarchicalGraph.rootNode}
      settings={settings}
    />
  );
}

type HierarchyTreeInnerProps = {
  rootNode: RootNode;
  settings: TreeSettings;
};

function HierarchyTreeInner({ rootNode, settings }: HierarchyTreeInnerProps) {
  const queryClient = useQueryClient();
  const { setSelectedIds, setFocusedId, setFocusedName } = useSelection();

  const loadChildren = useCallback(
    async (id: string): Promise<TreeNodeData[]> => {
      const result = await queryClient.ensureQueryData(
        nodeChildrenQueryOptions(id),
      );
      return result.hierarchicalGraph?.node?.children.nodes ?? [];
    },
    [queryClient],
  );

  const handleFocusedIdChange = useCallback(
    (id: string | null, name: string | null) => {
      setFocusedId(id);
      setFocusedName(name);
    },
    [setFocusedId, setFocusedName],
  );

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
      <TreeFooter />
    </div>
  );
}
