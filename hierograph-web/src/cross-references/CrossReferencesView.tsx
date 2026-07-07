import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRightFromLine, ArrowRightToLine } from "lucide-react";
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
    focusedName,
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [queryClient, subjectKey],
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [queryClient, subjectKey],
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

  const subjectLabel =
    subjectIds.length === 1
      ? (focusedName ?? "the selected node")
      : `the selected set (${subjectIds.length} nodes)`;

  const usedBySubHeader = (
    <div className="space-y-0.5">
      <p className="text-fg-subtle text-[11px]">Used by {subjectLabel}</p>
      <p className="text-fg-subtle text-[11px]">
        # = dependency weight · → = set as subject
      </p>
    </div>
  );

  const usesSubHeader = (
    <div className="space-y-0.5">
      <p className="text-fg-subtle text-[11px]">{subjectLabel} uses</p>
      <p className="text-fg-subtle text-[11px]">
        # = dependency weight · → = set as subject
      </p>
    </div>
  );

  return (
    <div className="flex h-full flex-col gap-2">
      {hasSubject && (
        <div className="text-fg-subtle border-border shrink-0 rounded-[8px] border px-3 py-1.5 text-[11px]">
          Cross references for: {subjectLabel}
        </div>
      )}
      <div className="grid min-h-0 flex-1 grid-cols-2 gap-2">
        <Pane
          title={
            <span className="flex items-center gap-1.5">
              <ArrowRightToLine className="size-[13px]" aria-hidden />
              Used by
            </span>
          }
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
              autoExpandOnLoad="all"
            />
          )}
        </Pane>
        <Pane
          title={
            <span className="flex items-center gap-1.5">
              <ArrowRightFromLine className="size-[13px]" aria-hidden />
              Uses
            </span>
          }
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
              autoExpandOnLoad="all"
            />
          )}
        </Pane>
      </div>
    </div>
  );
}
