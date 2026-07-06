import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ListTree } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { twMerge } from "tailwind-merge";

import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuGhostTrigger,
  ghostIconTriggerClassName,
} from "@/design-system/ui/dropdown-menu";
import { Message } from "@/design-system/ui/message";
import { useLocalStorage } from "@/design-system/useLocalStorage";
import { formatNodeLabel, type NodeLabelFormat } from "@/graph/nodeLabel";
import { nodeBasicsQueryOptions } from "@/graph/queries";
import { AsyncTree, type AsyncTreeHandle } from "@/tree/AsyncTree";
import { DEFAULT_TREE_SETTINGS } from "@/tree/useTreeSettings";

import {
  AUTO_EXPAND_STORAGE_KEY,
  AUTO_REVEAL_STORAGE_KEY,
} from "./dependencyDetailsLabelSettings";
import {
  filteredChildrenQueryOptions,
  filteredDependenciesQueryOptions,
} from "./queries";
import { useDebouncedValue } from "./useDebouncedValue";

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
  const [hoverTarget, setHoverTarget] = useState<{
    side: "source" | "target";
    id: string;
  } | null>(null);
  const debouncedHover = useDebouncedValue(hoverTarget, 200);
  const [autoExpandSingleChildren, setAutoExpandSingleChildren] =
    useLocalStorage<boolean>(AUTO_EXPAND_STORAGE_KEY, false);
  const [autoRevealCounterparts, setAutoRevealCounterparts] =
    useLocalStorage<boolean>(AUTO_REVEAL_STORAGE_KEY, false);
  const sourceTreeRef = useRef<AsyncTreeHandle>(null);
  const targetTreeRef = useRef<AsyncTreeHandle>(null);

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

  // With auto-reveal on, a selection on one side expands the *other* tree so its
  // marked counterparts become visible without an extra click. Keyed on the
  // marked-id sets (not the hover preview) so it reacts to deliberate selection
  // changes only — the tree never jumps while hovering. revealMarked just opens
  // the ancestor folders of already-marked rows, so re-running it is idempotent.
  const markedSourceKey = markedSourceIds.join(",");
  const markedTargetKey = markedTargetIds.join(",");
  useEffect(() => {
    if (autoRevealCounterparts && markedTargetIds.length > 0) {
      targetTreeRef.current?.revealMarked();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoRevealCounterparts, markedTargetKey]);
  useEffect(() => {
    if (autoRevealCounterparts && markedSourceIds.length > 0) {
      sourceTreeRef.current?.revealMarked();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoRevealCounterparts, markedSourceKey]);

  const hoverSourceIds =
    debouncedHover?.side === "source" ? [debouncedHover.id] : [];
  const hoverTargetIds =
    debouncedHover?.side === "target" ? [debouncedHover.id] : [];

  const { data: hoverData } = useQuery({
    ...filteredDependenciesQueryOptions(
      sourceNodeId,
      targetNodeId,
      hoverSourceIds,
      hoverTargetIds,
    ),
    enabled: debouncedHover != null,
  });

  const hoverDepSet =
    hoverData?.hierarchicalGraph?.dependencySetForAggregatedDependency;
  const hoverActive =
    debouncedHover != null && !!hoverDepSet?.filteredDependencies;
  const displaySourceIds = hoverActive
    ? (hoverDepSet?.filteredDependencies?.markedSourceIds ?? [])
    : markedSourceIds;
  const displayTargetIds = hoverActive
    ? (hoverDepSet?.filteredDependencies?.markedTargetIds ?? [])
    : markedTargetIds;

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
      <div className="border-border flex items-center justify-end gap-1 border-b px-1.5 py-1">
        <button
          type="button"
          aria-pressed={autoRevealCounterparts}
          title="Auto-reveal counterparts"
          onClick={() => setAutoRevealCounterparts(!autoRevealCounterparts)}
          className={twMerge(
            ghostIconTriggerClassName,
            autoRevealCounterparts && "bg-state-hover text-fg",
          )}
        >
          <ListTree className="size-4" />
        </button>
        <AutoExpandMenu
          autoExpandSingleChildren={autoExpandSingleChildren}
          setAutoExpandSingleChildren={setAutoExpandSingleChildren}
        />
      </div>
      <div className="grid min-h-0 flex-1 grid-cols-2 overflow-hidden">
        <div className="border-border min-w-0 overflow-auto border-r">
          <div className="border-border text-fg-subtle flex items-center border-b px-[14px] py-2 font-mono text-[11px]">
            <span className="min-w-0 truncate">
              Source ·{" "}
              <span className="text-fg-muted">
                {formatNodeLabel(sourceRoot.text, labelFormat, sourceRoot.type)}
              </span>{" "}
              — click a type to mark its counterparts
            </span>
          </div>
          <div className="p-1.5">
            <AsyncTree
              ref={sourceTreeRef}
              rootNode={sourceRoot}
              loadChildren={loadSourceChildren}
              onSelectedIdsChange={setSelectedSourceIds}
              onHoveredIdChange={(id) =>
                setHoverTarget(id ? { side: "source", id } : null)
              }
              markedIds={displaySourceIds}
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
          <div className="border-border text-fg-subtle flex items-center border-b px-[14px] py-2 font-mono text-[11px]">
            <span className="min-w-0 truncate">
              Target ·{" "}
              <span className="text-fg-muted">
                {formatNodeLabel(targetRoot.text, labelFormat, targetRoot.type)}
              </span>{" "}
              — <span className="text-state-marked-fg">marked</span> =
              referenced by the source selection
            </span>
          </div>
          <div className="p-1.5">
            <AsyncTree
              ref={targetTreeRef}
              rootNode={targetRoot}
              loadChildren={loadTargetChildren}
              onSelectedIdsChange={setSelectedTargetIds}
              onHoveredIdChange={(id) =>
                setHoverTarget(id ? { side: "target", id } : null)
              }
              markedIds={displayTargetIds}
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
