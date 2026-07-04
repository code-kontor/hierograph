import {
  asyncDataLoaderFeature,
  hotkeysCoreFeature,
  type ItemInstance,
  selectionFeature,
  type TreeState,
} from "@headless-tree/core";
import { useTree } from "@headless-tree/react";
import { ChevronRight, Loader2 } from "lucide-react";
import { createElement, useEffect, useMemo, useRef, useState } from "react";

import { getNodeIcon } from "@/components/hierarchy/nodeIcon";
import { cn } from "@/lib/utils";

export type TreeNodeData = {
  id: string;
  text: string;
  type: string;
  hasChildren: boolean;
};

export type AsyncTreeProps = {
  rootNode: TreeNodeData;
  loadChildren: (parentId: string) => Promise<TreeNodeData[]>;
  onSelectedIdsChange: (ids: string[]) => void;
  onFocusedIdChange?: (id: string | null) => void;
  markedIds?: string[];
  label: string;
};

export function AsyncTree({
  rootNode,
  loadChildren,
  onSelectedIdsChange,
  onFocusedIdChange,
  markedIds,
  label,
}: AsyncTreeProps) {
  const markedSet = useMemo(() => new Set(markedIds ?? []), [markedIds]);

  const itemData = useRef<Map<string, TreeNodeData>>(
    new Map([[rootNode.id, rootNode]]),
  );

  const [state, setState] = useState<Partial<TreeState<TreeNodeData>>>({});

  const tree = useTree<TreeNodeData>({
    rootItemId: rootNode.id,
    getItemName: (item) => item.getItemData().text,
    isItemFolder: (item) => item.getItemData().hasChildren,
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
        const nodes = await loadChildren(id);
        for (const n of nodes) {
          itemData.current.set(n.id, n);
        }
        return nodes.map((n) => n.id);
      },
    },
    features: [asyncDataLoaderFeature, selectionFeature, hotkeysCoreFeature],
  });

  useEffect(() => {
    onSelectedIdsChange(state.selectedItems ?? []);
  }, [state.selectedItems, onSelectedIdsChange]);

  useEffect(() => {
    onFocusedIdChange?.(state.focusedItem ?? null);
  }, [state.focusedItem, onFocusedIdChange]);

  return (
    <div {...tree.getContainerProps(label)} className="text-sm">
      {tree.getItems().map((item) => (
        <TreeRow
          key={item.getId()}
          item={item}
          isMarked={markedSet.has(item.getId())}
        />
      ))}
    </div>
  );
}

type TreeRowProps = {
  item: ItemInstance<TreeNodeData>;
  isMarked: boolean;
};

function TreeRow({ item, isMarked }: TreeRowProps) {
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
        isMarked && "bg-primary/10 text-primary",
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
      {createElement(getNodeIcon(item.getItemData().type), {
        className: "text-muted-foreground h-4 w-4 shrink-0",
      })}
      <span>{item.getItemName()}</span>
    </div>
  );
}
