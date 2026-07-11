import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useSearch } from "@tanstack/react-router";
import { useEffect, useEffectEvent, useRef } from "react";

import {
  nodeChildrenQueryOptions,
  rootNodeQueryOptions,
} from "@/graph/queries";
import type { RootNodeQuery } from "@/graphql/generated/graphql";
import { useSelection } from "@/selection/SelectionContext";
import {
  AsyncTree,
  type AsyncTreeHandle,
  type TreeNodeData,
} from "@/tree/AsyncTree";
import type { TreeSettings } from "@/tree/useTreeSettings";

import { dsmSubjectPredecessorsQueryOptions } from "./queries";
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

  const search = useSearch({ from: "/dsm" });
  const subjectIds = search.subject_ids ?? [];
  const subjectKey = subjectIds.join(",");
  const treeRef = useRef<AsyncTreeHandle>(null);

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

  // Reveal the current URL selection: expand each subject's ancestor folders
  // and scroll it into view (via AsyncTree.revealNode, which never touches
  // selection). Reads the latest search/queryClient/ref through useEffectEvent
  // so the effect can key purely on the joined subject ids. Never navigates —
  // this is the single allowed URL-reading effect (no sync loop).
  const revealSubjects = useEffectEvent((ids: string[]) => {
    queryClient
      .ensureQueryData(dsmSubjectPredecessorsQueryOptions(ids))
      .then((data) => {
        const nodes = data.hierarchicalGraph?.nodes.nodes ?? [];
        const ancestorsById = new Map(
          nodes.map((node) => [node.id, node.predecessors.map((p) => p.id)]),
        );
        for (const id of ids) {
          treeRef.current?.revealNode(id, ancestorsById.get(id) ?? []);
        }
        // Focus is transient (never in the URL); the name is left as-is
        // (display-only, filled in on an actual click).
        setFocusedId(ids[0]);
      })
      .catch(console.error);
  });

  const lastRevealedKeyRef = useRef<string | null>(null);
  useEffect(() => {
    // Covers both a deep-link reload (first non-empty key) and a Back-button
    // change (key differs from the last revealed one). A user's own tree click
    // produces the same key it just revealed to, so the redundant reveal is a
    // near no-op (already-visible row).
    if (lastRevealedKeyRef.current === subjectKey) return;
    lastRevealedKeyRef.current = subjectKey;
    if (subjectKey.length === 0) return;
    revealSubjects(subjectKey.split(","));
  }, [subjectKey]);

  return (
    <div className="flex h-full flex-col">
      <div className="min-h-0 flex-1 overflow-auto p-3">
        <AsyncTree
          ref={treeRef}
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
