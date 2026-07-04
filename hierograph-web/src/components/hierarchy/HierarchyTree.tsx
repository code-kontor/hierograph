import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback } from "react";

import { AsyncTree, type TreeNodeData } from "@/components/tree/AsyncTree";
import type { RootNodeQuery } from "@/generated/graphql/graphql";
import {
  nodeChildrenQueryOptions,
  rootNodeQueryOptions,
} from "@/queries/hierarchical-graph";

import { useSelection } from "./SelectionContext";

type RootNode = NonNullable<
  NonNullable<RootNodeQuery["hierarchicalGraph"]>["rootNode"]
>;

export function HierarchyTree() {
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

  return <HierarchyTreeInner rootNode={data.hierarchicalGraph.rootNode} />;
}

type HierarchyTreeInnerProps = {
  rootNode: RootNode;
};

function HierarchyTreeInner({ rootNode }: HierarchyTreeInnerProps) {
  const queryClient = useQueryClient();
  const { setSelectedIds, setFocusedId } = useSelection();

  const loadChildren = useCallback(
    async (id: string): Promise<TreeNodeData[]> => {
      const result = await queryClient.ensureQueryData(
        nodeChildrenQueryOptions(id),
      );
      return result.hierarchicalGraph?.node?.children.nodes ?? [];
    },
    [queryClient],
  );

  return (
    <AsyncTree
      rootNode={rootNode}
      loadChildren={loadChildren}
      onSelectedIdsChange={setSelectedIds}
      onFocusedIdChange={setFocusedId}
      label="Hierarchy"
    />
  );
}
