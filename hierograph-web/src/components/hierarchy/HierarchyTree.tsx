import {
  asyncDataLoaderFeature,
  hotkeysCoreFeature,
  type ItemInstance,
  selectionFeature,
  type TreeState,
} from "@headless-tree/core";
import { useTree } from "@headless-tree/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronRight, Loader2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import type { RootNodeQuery } from "@/generated/graphql/graphql";
import { cn } from "@/lib/utils";
import {
  nodeChildrenQueryOptions,
  rootNodeQueryOptions,
} from "@/queries/hierarchical-graph";

import { useSelection } from "./SelectionContext";

type NodeData = NonNullable<
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
  rootNode: NodeData;
};

function HierarchyTreeInner({ rootNode }: HierarchyTreeInnerProps) {
  const queryClient = useQueryClient();
  const { setSelectedIds } = useSelection();

  // Item-Datencache: headless-tree fragt `getItem(id)` synchron-artig; die
  // vollständigen NodeData landen hier, sobald der Elternknoten geladen wurde.
  // Root wird vorgeseedet — sonst gäbe es einen Miss beim ersten Render.
  const itemData = useRef<Map<string, NodeData>>(
    new Map([[rootNode.id, rootNode]]),
  );

  const [state, setState] = useState<Partial<TreeState<NodeData>>>({});

  const tree = useTree<NodeData>({
    rootItemId: rootNode.id,
    getItemName: (item) => item.getItemData().text,
    isItemFolder: (item) => item.getItemData().hasChildren,
    // Solange getItem(id) async lädt, gäbe getItemData() sonst null zurück
    // (asyncDataLoaderFeature) — dieser Platzhalter überbrückt den Lade-Tick.
    createLoadingItemData: () => ({
      id: "",
      text: "Loading…",
      type: "",
      hasChildren: false,
    }),
    state,
    setState,
    dataLoader: {
      async getItem(id: string) {
        return (
          itemData.current.get(id) ?? {
            id,
            text: id,
            type: "",
            hasChildren: false,
          }
        );
      },
      async getChildren(id: string) {
        const result = await queryClient.ensureQueryData(
          nodeChildrenQueryOptions(id),
        );
        const nodes = result.hierarchicalGraph?.node?.children.nodes ?? [];
        for (const n of nodes) {
          itemData.current.set(n.id, n);
        }
        return nodes.map((n) => n.id);
      },
    },
    features: [asyncDataLoaderFeature, selectionFeature, hotkeysCoreFeature],
  });

  // Selektions-Output: Tree spiegelt die Selektion in den Context (Design-Intent:
  // Tree ist controlled gegen selectedIds, spätere Eingangs-Markierung fällt ab).
  useEffect(() => {
    setSelectedIds(state.selectedItems ?? []);
  }, [state.selectedItems, setSelectedIds]);

  return (
    <div {...tree.getContainerProps("Hierarchy")} className="text-sm">
      {tree.getItems().map((item) => (
        <TreeRow key={item.getId()} item={item} />
      ))}
    </div>
  );
}

type TreeRowProps = {
  item: ItemInstance<NodeData>;
};

function TreeRow({ item }: TreeRowProps) {
  const level = item.getItemMeta().level;
  const isFolder = item.isFolder();
  const isLoading = item.isLoading();
  const isExpanded = item.isExpanded();
  const isSelected = item.isSelected();

  return (
    <div
      {...item.getProps()}
      style={{ paddingLeft: level * 16 }}
      className={cn(
        "hover:bg-accent flex cursor-pointer items-center gap-1 rounded px-2 py-1",
        isSelected && "bg-accent",
      )}
    >
      <span className="flex h-4 w-4 items-center justify-center">
        {isFolder ? (
          isLoading ? (
            <Loader2 className="h-3 w-3 animate-spin" />
          ) : (
            <ChevronRight
              className={cn(
                "h-3 w-3 transition-transform",
                isExpanded && "rotate-90",
              )}
            />
          )
        ) : null}
      </span>
      <span>{item.getItemName()}</span>
    </div>
  );
}
