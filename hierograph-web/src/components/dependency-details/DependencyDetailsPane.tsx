import { NodeDetails } from "@/components/hierarchy/NodeDetails";
import { useSelection } from "@/components/hierarchy/SelectionContext";

import { DependencyDetailsPanel } from "./DependencyDetailsPanel";

export function DependencyDetailsPane() {
  const { cellSelection } = useSelection();
  if (!cellSelection) return <NodeDetails />;
  return (
    <DependencyDetailsPanel
      key={`${cellSelection.sourceNodeId}:${cellSelection.targetNodeId}`}
      sourceNodeId={cellSelection.sourceNodeId}
      targetNodeId={cellSelection.targetNodeId}
    />
  );
}
