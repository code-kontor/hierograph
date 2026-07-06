import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback } from "react";

import { Pane } from "@/design-system/layout/Pane";
import { Message } from "@/design-system/ui/message";
import { rootNodeQueryOptions } from "@/graph/queries";
import { useSelection } from "@/selection/SelectionContext";
import type { TreeNodeData } from "@/tree/AsyncTree";
import { AsyncTree } from "@/tree/AsyncTree";
import type { TreeSettings } from "@/tree/useTreeSettings";

import {
  crossReferencesUsedByQueryOptions,
  crossReferencesUsesQueryOptions,
} from "./queries";

type CrossReferencesViewProps = {
  settings: TreeSettings;
};

export function CrossReferencesView({ settings }: CrossReferencesViewProps) {
  const { focusedId } = useSelection();
  const subjectId = focusedId;
  const queryClient = useQueryClient();

  const {
    data: rootData,
    isPending: rootPending,
    isError: rootError,
  } = useQuery(rootNodeQueryOptions());
  const rootNode = rootData?.hierarchicalGraph?.rootNode ?? null;

  const loadUsedByChildren = useCallback(
    async (parentId: string): Promise<TreeNodeData[]> => {
      if (subjectId == null) return [];
      const result = await queryClient.ensureQueryData(
        crossReferencesUsedByQueryOptions(parentId, subjectId),
      );
      const nodes =
        result.hierarchicalGraph?.node?.childrenFilteredByReferencedNodes
          .nodes ?? [];
      return nodes.map((n) => ({
        id: n.id,
        text: n.text,
        type: n.type,
        hasChildren: n.hasChildren,
        // Empty dependenciesTo → omit weight so "no data" differs from weight 0
        weight:
          n.dependenciesTo.length > 0
            ? n.dependenciesTo.reduce((sum, d) => sum + d.weight, 0)
            : undefined,
      }));
    },
    [queryClient, subjectId],
  );

  const loadUsesChildren = useCallback(
    async (parentId: string): Promise<TreeNodeData[]> => {
      if (subjectId == null) return [];
      const result = await queryClient.ensureQueryData(
        crossReferencesUsesQueryOptions(parentId, subjectId),
      );
      const nodes =
        result.hierarchicalGraph?.node?.childrenFilteredByReferencingNodes
          .nodes ?? [];
      return nodes.map((n) => ({
        id: n.id,
        text: n.text,
        type: n.type,
        hasChildren: n.hasChildren,
        // Empty dependenciesFrom → omit weight so "no data" differs from weight 0
        weight:
          n.dependenciesFrom.length > 0
            ? n.dependenciesFrom.reduce((sum, d) => sum + d.weight, 0)
            : undefined,
      }));
    },
    [queryClient, subjectId],
  );

  const noop = useCallback(() => {}, []);

  if (rootPending) {
    return (
      <div className="grid h-full grid-cols-2 gap-2">
        <Pane title="Used by" bodyClassName="flex items-center justify-center">
          <Message variant="loading" title="Loading hierarchy" />
        </Pane>
        <Pane title="Uses" bodyClassName="flex items-center justify-center">
          <Message variant="loading" title="Loading hierarchy" />
        </Pane>
      </div>
    );
  }

  if (rootError || !rootNode) {
    return (
      <div className="grid h-full grid-cols-2 gap-2">
        <Pane title="Used by" bodyClassName="flex items-center justify-center">
          <Message variant="error" title="Could not load hierarchy root" />
        </Pane>
        <Pane title="Uses" bodyClassName="flex items-center justify-center">
          <Message variant="error" title="Could not load hierarchy root" />
        </Pane>
      </div>
    );
  }

  const usedBySubHeader = (
    <p className="text-fg-subtle text-[11px]">
      Incoming — nodes that depend on the selected node
    </p>
  );

  const usesSubHeader = (
    <p className="text-fg-subtle text-[11px]">
      Outgoing — nodes that the selected node depends on
    </p>
  );

  return (
    <div className="grid h-full grid-cols-2 gap-2">
      <Pane
        title="Used by"
        subHeader={usedBySubHeader}
        bodyClassName="overflow-auto p-1.5"
      >
        {subjectId === null ? (
          <Message variant="empty" title="No subject selected">
            Select a node in the hierarchy to see who depends on it.
          </Message>
        ) : (
          <AsyncTree
            key={subjectId}
            rootNode={rootNode}
            loadChildren={loadUsedByChildren}
            onSelectedIdsChange={noop}
            label="CrossReferencesUsedByTree"
            settings={settings}
          />
        )}
      </Pane>
      <Pane
        title="Uses"
        subHeader={usesSubHeader}
        bodyClassName="overflow-auto p-1.5"
      >
        {subjectId === null ? (
          <Message variant="empty" title="No subject selected">
            Select a node in the hierarchy to see what it depends on.
          </Message>
        ) : (
          <AsyncTree
            key={subjectId}
            rootNode={rootNode}
            loadChildren={loadUsesChildren}
            onSelectedIdsChange={noop}
            label="CrossReferencesUsesTree"
            settings={settings}
          />
        )}
      </Pane>
    </div>
  );
}
