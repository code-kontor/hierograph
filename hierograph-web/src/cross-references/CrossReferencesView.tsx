import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo } from "react";

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
import { useNormalizedSubjectKey } from "./useNormalizedSubjectIds";

type CrossReferencesViewProps = {
  settings: TreeSettings;
};

export function CrossReferencesView({ settings }: CrossReferencesViewProps) {
  const {
    selectedIds,
    focusedId,
    setFocusedId,
    setFocusedName,
    setCellSelection,
    setSelectedIds,
  } = useSelection();
  const queryClient = useQueryClient();

  // subjectKey is a stable string — it only changes when the normalized set
  // content changes. Deriving subjectIds from it keeps the array referentially
  // stable as well, so no cascading re-renders from predecessor query resolution.
  const subjectKey = useNormalizedSubjectKey(selectedIds, focusedId);
  const subjectIds = useMemo(
    () => (subjectKey ? subjectKey.split(",") : []),
    [subjectKey],
  );
  const subjectSet = useMemo(() => new Set(subjectIds), [subjectKey]); // eslint-disable-line react-hooks/exhaustive-deps
  const hasSubject = subjectKey.length > 0;

  const {
    data: rootData,
    isPending: rootPending,
    isError: rootError,
  } = useQuery(rootNodeQueryOptions());
  const rootNode = rootData?.hierarchicalGraph?.rootNode ?? null;

  const loadUsedByChildren = useCallback(
    async (parentId: string): Promise<TreeNodeData[]> => {
      if (subjectIds.length === 0) return [];
      const result = await queryClient.ensureQueryData(
        crossReferencesUsedByQueryOptions(parentId, subjectIds),
      );
      const nodes =
        result.hierarchicalGraph?.node?.childrenFilteredByReferencedNodes
          .nodes ?? [];
      // E2: drop set members as partners at every level so set-internal edges
      // never surface (and we never descend into a member's internals).
      // Known limitation: an ancestor-container of set members may appear as a
      // partner with a contaminated aggregate weight, because the API aggregates
      // internal+external edges at container level and offers no exclusion
      // argument. Follow-up task: add excludingNodeIds to childrenFilteredBy* /
      // dependenciesTo/From or a set-aware net-weight field.
      return nodes
        .filter((n) => !subjectSet.has(n.id))
        .map((n) => ({
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [queryClient, subjectKey, subjectSet],
  );

  const loadUsesChildren = useCallback(
    async (parentId: string): Promise<TreeNodeData[]> => {
      if (subjectIds.length === 0) return [];
      const result = await queryClient.ensureQueryData(
        crossReferencesUsesQueryOptions(parentId, subjectIds),
      );
      const nodes =
        result.hierarchicalGraph?.node?.childrenFilteredByReferencingNodes
          .nodes ?? [];
      // E2: drop set members as partners (see loadUsedByChildren comment).
      return nodes
        .filter((n) => !subjectSet.has(n.id))
        .map((n) => ({
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [queryClient, subjectKey, subjectSet],
  );

  // E4: only a single-subject end is unambiguous. For a multi-subject set a
  // partner click has no single subject end → leave the Inspector unchanged.
  const handleUsedByFocusedIdChange = useCallback(
    (id: string | null) => {
      if (id == null || subjectIds.length !== 1) return;
      // "Used by" = incoming: partner → subject
      setCellSelection({ sourceNodeId: id, targetNodeId: subjectIds[0] });
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [subjectKey, setCellSelection],
  );

  const handleUsesFocusedIdChange = useCallback(
    (id: string | null) => {
      if (id == null || subjectIds.length !== 1) return;
      // "Uses" = outgoing: subject → partner
      setCellSelection({ sourceNodeId: subjectIds[0], targetNodeId: id });
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [subjectKey, setCellSelection],
  );

  // "→ as subject" — promote a partner to become the new subject.
  // Also resets selectedIds so E1 doesn't let a prior multi-selection shadow
  // the promoted single subject.
  const handlePromoteToSubject = useCallback(
    (nodeData: TreeNodeData) => {
      setSelectedIds([nodeData.id]);
      setFocusedId(nodeData.id);
      setFocusedName(nodeData.text.split(".").pop() ?? nodeData.text);
    },
    [setSelectedIds, setFocusedId, setFocusedName],
  );

  // Reset stale cell selection when the subject set changes (e.g. after Promote).
  useEffect(() => {
    setCellSelection(null);
  }, [subjectKey, setCellSelection]);

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
      Incoming — nodes that depend on{" "}
      {subjectIds.length > 1 ? "the selected set" : "the selected node"}
    </p>
  );

  const usesSubHeader = (
    <p className="text-fg-subtle text-[11px]">
      Outgoing — nodes that{" "}
      {subjectIds.length > 1
        ? "the selected set depends on"
        : "the selected node depends on"}
    </p>
  );

  return (
    <div className="grid h-full grid-cols-2 gap-2">
      <Pane
        title="Used by"
        subHeader={usedBySubHeader}
        bodyClassName="overflow-auto p-1.5"
      >
        {!hasSubject ? (
          <Message variant="empty" title="No subject selected">
            Select a node in the hierarchy to see who depends on it.
          </Message>
        ) : (
          <AsyncTree
            key={subjectKey}
            rootNode={rootNode}
            loadChildren={loadUsedByChildren}
            onSelectedIdsChange={noop}
            onFocusedIdChange={handleUsedByFocusedIdChange}
            onPromoteToSubject={handlePromoteToSubject}
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
        {!hasSubject ? (
          <Message variant="empty" title="No subject selected">
            Select a node in the hierarchy to see what it depends on.
          </Message>
        ) : (
          <AsyncTree
            key={subjectKey}
            rootNode={rootNode}
            loadChildren={loadUsesChildren}
            onSelectedIdsChange={noop}
            onFocusedIdChange={handleUsesFocusedIdChange}
            onPromoteToSubject={handlePromoteToSubject}
            label="CrossReferencesUsesTree"
            settings={settings}
          />
        )}
      </Pane>
    </div>
  );
}
