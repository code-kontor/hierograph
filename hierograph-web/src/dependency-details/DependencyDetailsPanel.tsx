import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useState } from "react";

import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuGhostTrigger,
} from "@/design-system/ui/dropdown-menu";
import { Message } from "@/design-system/ui/message";
import { useLocalStorage } from "@/design-system/useLocalStorage";
import { formatNodeLabel, type NodeLabelFormat } from "@/graph/nodeLabel";
import { nodeBasicsQueryOptions } from "@/graph/queries";
import { AsyncTree } from "@/tree/AsyncTree";
import { DEFAULT_TREE_SETTINGS } from "@/tree/useTreeSettings";

import { AUTO_EXPAND_STORAGE_KEY } from "./dependencyDetailsLabelSettings";
import {
  filteredChildrenQueryOptions,
  filteredDependenciesQueryOptions,
} from "./queries";

export type DependencyDetailsPanelProps = {
  sourceNodeId: string;
  targetNodeId: string;
  labelFormat: NodeLabelFormat;
};

export function DependencyDetailsPanel({
  sourceNodeId,
  targetNodeId,
  labelFormat,
}: DependencyDetailsPanelProps) {
  const queryClient = useQueryClient();
  const [selectedSourceIds, setSelectedSourceIds] = useState<string[]>([]);
  const [selectedTargetIds, setSelectedTargetIds] = useState<string[]>([]);
  const [autoExpandSingleChildren, setAutoExpandSingleChildren] =
    useLocalStorage<boolean>(AUTO_EXPAND_STORAGE_KEY, false);

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
      <div className="p-4">
        <Message variant="loading" title="Loading dependency details" />
      </div>
    );
  }

  const sourceRoot = sourceRootData?.hierarchicalGraph?.node ?? null;
  const targetRoot = targetRootData?.hierarchicalGraph?.node ?? null;

  if (!sourceRoot || !targetRoot) {
    return (
      <div className="p-4">
        <Message variant="error" title="Could not load dependency nodes" />
      </div>
    );
  }

  if (filteredData && (!depSet || size === 0)) {
    return (
      <div className="p-4">
        <Message variant="empty" title="No dependencies">
          No dependencies for this cell.
        </Message>
      </div>
    );
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
      <div className="border-border flex items-center justify-end border-b px-1.5 py-1">
        <AutoExpandMenu
          autoExpandSingleChildren={autoExpandSingleChildren}
          setAutoExpandSingleChildren={setAutoExpandSingleChildren}
        />
      </div>
      <div className="grid min-h-0 flex-1 grid-cols-2 overflow-hidden">
        <div className="border-border min-w-0 overflow-auto border-r">
          <div className="border-border text-fg-subtle border-b px-[14px] py-2 font-mono text-[11px]">
            Source ·{" "}
            <span className="text-fg-muted">
              {formatNodeLabel(sourceRoot.text, labelFormat, sourceRoot.type)}
            </span>{" "}
            — click a type to mark its counterparts
          </div>
          <div className="p-1.5">
            <AsyncTree
              rootNode={sourceRoot}
              loadChildren={loadSourceChildren}
              onSelectedIdsChange={setSelectedSourceIds}
              markedIds={markedSourceIds}
              label="DependencySourceTree"
              settings={{
                ...DEFAULT_TREE_SETTINGS,
                labelFormat,
                autoExpandSingleChildren,
              }}
            />
          </div>
        </div>
        <div className="min-w-0 overflow-auto">
          <div className="border-border text-fg-subtle border-b px-[14px] py-2 font-mono text-[11px]">
            Target ·{" "}
            <span className="text-fg-muted">
              {formatNodeLabel(targetRoot.text, labelFormat, targetRoot.type)}
            </span>{" "}
            — <span className="text-state-marked-fg">marked</span> = referenced
            by the source selection
          </div>
          <div className="p-1.5">
            <AsyncTree
              rootNode={targetRoot}
              loadChildren={loadTargetChildren}
              onSelectedIdsChange={setSelectedTargetIds}
              markedIds={markedTargetIds}
              markedBadge
              label="DependencyTargetTree"
              settings={{
                ...DEFAULT_TREE_SETTINGS,
                labelFormat,
                autoExpandSingleChildren,
              }}
            />
          </div>
        </div>
      </div>
    </div>
  );
}

type AutoExpandMenuProps = {
  autoExpandSingleChildren: boolean;
  setAutoExpandSingleChildren: (value: boolean) => void;
};

function AutoExpandMenu({
  autoExpandSingleChildren,
  setAutoExpandSingleChildren,
}: AutoExpandMenuProps) {
  return (
    <DropdownMenu>
      <DropdownMenuGhostTrigger title="Settings" />
      <DropdownMenuContent>
        <DropdownMenuCheckboxItem
          checked={autoExpandSingleChildren}
          onCheckedChange={setAutoExpandSingleChildren}
          onSelect={(e) => e.preventDefault()}
        >
          Auto-expand single children
        </DropdownMenuCheckboxItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
