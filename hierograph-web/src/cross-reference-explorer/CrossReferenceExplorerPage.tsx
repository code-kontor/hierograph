import { useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";

import { DependencyDetailsPanel } from "@/dependency-details/DependencyDetailsPanel";
import { OneOneSplitLayout } from "@/design-system/layout/OneOneSplitLayout";
import { SelectionProvider } from "@/selection/SelectionContext";
import type { AsyncTreeHandle } from "@/tree/AsyncTree";
import { useTreeSettings } from "@/tree/useTreeSettings";

import { CrossReferenceExplorerView } from "./CrossReferenceExplorerView";
import { crossReferenceExplorerCenterPredecessorsQueryOptions } from "./queries";

export function CrossReferenceExplorerPage() {
  const {
    settings,
    setShowIndentGuides,
    setAutoExpandSingleChildren,
    setPreserveSelectionOnCollapse,
    setLabelFormat,
  } = useTreeSettings();

  const queryClient = useQueryClient();
  const centerTreeRef = useRef<AsyncTreeHandle>(null);

  // Reveal-only channel (no SelectionContext): fetch the clicked partner's
  // ancestor chain (same predecessors query that powers revealHighlighted),
  // then ask the center tree to expand-to + scroll. revealNode drops the root
  // id itself, so the raw predecessor ids can be passed through.
  const handleRevealInCenter = async (id: string) => {
    const data = await queryClient.ensureQueryData(
      crossReferenceExplorerCenterPredecessorsQueryOptions([id]),
    );
    const node = data.hierarchicalGraph?.nodes.nodes[0];
    const ancestors = node?.predecessors.map((p) => p.id) ?? [];
    centerTreeRef.current?.revealNode(id, ancestors);
  };

  return (
    <SelectionProvider>
      <OneOneSplitLayout
        top={
          <CrossReferenceExplorerView
            settings={settings}
            centerTreeRef={centerTreeRef}
            setShowIndentGuides={setShowIndentGuides}
            setAutoExpandSingleChildren={setAutoExpandSingleChildren}
            setPreserveSelectionOnCollapse={setPreserveSelectionOnCollapse}
            setLabelFormat={setLabelFormat}
          />
        }
        bottom={
          <DependencyDetailsPanel
            emptyStateTitle="No selection"
            emptyStateDescription="Use the column inspect buttons or click a partner node to inspect dependencies."
            onRevealInCenter={(id) => {
              handleRevealInCenter(id).catch(console.error);
            }}
          />
        }
      />
    </SelectionProvider>
  );
}
