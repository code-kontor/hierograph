import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useState } from "react";

import { AsyncTree } from "@/components/tree/AsyncTree";
import {
  filteredChildrenQueryOptions,
  filteredDependenciesQueryOptions,
} from "@/queries/dependencies";
import { nodeBasicsQueryOptions } from "@/queries/hierarchical-graph";

export type DependencyDetailsPanelProps = {
  sourceNodeId: string;
  targetNodeId: string;
};

export function DependencyDetailsPanel({
  sourceNodeId,
  targetNodeId,
}: DependencyDetailsPanelProps) {
  const queryClient = useQueryClient();
  const [selectedSourceIds, setSelectedSourceIds] = useState<string[]>([]);
  const [selectedTargetIds, setSelectedTargetIds] = useState<string[]>([]);

  const { data: sourceRootData, isPending: sourceRootPending } = useQuery(
    nodeBasicsQueryOptions(sourceNodeId),
  );
  const { data: targetRootData, isPending: targetRootPending } = useQuery(
    nodeBasicsQueryOptions(targetNodeId),
  );

  const { data: filteredData } = useQuery(
    filteredDependenciesQueryOptions(
      sourceNodeId,
      targetNodeId,
      selectedSourceIds,
      selectedTargetIds,
    ),
  );

  const depSet =
    filteredData?.hierarchicalGraph?.dependencySetForAggregatedDependency;
  const size = depSet?.size;
  const hasSelection =
    selectedSourceIds.length > 0 || selectedTargetIds.length > 0;
  const markedSourceIds = hasSelection
    ? (depSet?.filteredDependencies?.markedSourceIds ?? [])
    : [];
  const markedTargetIds = hasSelection
    ? (depSet?.filteredDependencies?.markedTargetIds ?? [])
    : [];

  const loadSourceChildren = useCallback(
    async (parentId: string) => {
      const result = await queryClient.ensureQueryData(
        filteredChildrenQueryOptions(
          sourceNodeId,
          targetNodeId,
          parentId,
          "SOURCE",
        ),
      );
      return (
        result.hierarchicalGraph?.dependencySetForAggregatedDependency
          ?.filteredChildren ?? []
      );
    },
    [queryClient, sourceNodeId, targetNodeId],
  );

  const loadTargetChildren = useCallback(
    async (parentId: string) => {
      const result = await queryClient.ensureQueryData(
        filteredChildrenQueryOptions(
          sourceNodeId,
          targetNodeId,
          parentId,
          "TARGET",
        ),
      );
      return (
        result.hierarchicalGraph?.dependencySetForAggregatedDependency
          ?.filteredChildren ?? []
      );
    },
    [queryClient, sourceNodeId, targetNodeId],
  );

  if (sourceRootPending || targetRootPending) {
    return (
      <p className="text-muted-foreground text-sm">
        Loading dependency details…
      </p>
    );
  }

  const sourceRoot = sourceRootData?.hierarchicalGraph?.node ?? null;
  const targetRoot = targetRootData?.hierarchicalGraph?.node ?? null;

  if (!sourceRoot || !targetRoot) {
    return (
      <p className="text-destructive text-sm">
        Could not load dependency nodes.
      </p>
    );
  }

  if (filteredData && (!depSet || size === 0)) {
    return (
      <p className="text-muted-foreground text-sm">
        No dependencies for this cell.
      </p>
    );
  }

  return (
    <div className="grid min-h-0 flex-1 grid-cols-2 gap-4 overflow-hidden">
      <div className="min-w-0 overflow-auto">
        <AsyncTree
          rootNode={sourceRoot}
          loadChildren={loadSourceChildren}
          onSelectedIdsChange={setSelectedSourceIds}
          markedIds={markedSourceIds}
          label="DependencySourceTree"
        />
      </div>
      <div className="min-w-0 overflow-auto">
        <AsyncTree
          rootNode={targetRoot}
          loadChildren={loadTargetChildren}
          onSelectedIdsChange={setSelectedTargetIds}
          markedIds={markedTargetIds}
          label="DependencyTargetTree"
        />
      </div>
    </div>
  );
}
