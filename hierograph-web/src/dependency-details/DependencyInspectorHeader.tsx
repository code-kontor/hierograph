import { useQuery } from "@tanstack/react-query";

import { formatNodeLabel, type NodeLabelFormat } from "@/graph/nodeLabel";
import { nodeBasicsQueryOptions } from "@/graph/queries";

type DependencyInspectorHeaderProps = {
  sourceNodeId: string;
  targetNodeId: string;
  labelFormat: NodeLabelFormat;
};

export function DependencyInspectorHeader({
  sourceNodeId,
  targetNodeId,
  labelFormat,
}: DependencyInspectorHeaderProps) {
  const { data: sourceData, isPending: sourcePending } = useQuery(
    nodeBasicsQueryOptions(sourceNodeId),
  );
  const { data: targetData, isPending: targetPending } = useQuery(
    nodeBasicsQueryOptions(targetNodeId),
  );

  const sourceNode = sourceData?.hierarchicalGraph?.node;
  const targetNode = targetData?.hierarchicalGraph?.node;

  const sourceText = sourcePending
    ? sourceNodeId
    : formatNodeLabel(
        sourceNode?.text ?? sourceNodeId,
        labelFormat,
        sourceNode?.type,
      );
  const targetText = targetPending
    ? targetNodeId
    : formatNodeLabel(
        targetNode?.text ?? targetNodeId,
        labelFormat,
        targetNode?.type,
      );

  return (
    <div className="flex items-center gap-2 font-mono text-[12px]">
      <span className="text-fg-subtle">From</span>
      <span className="text-fg">{sourceText}</span>
      <span className="text-fg-subtle">→</span>
      <span className="text-fg-subtle">To</span>
      <span className="text-fg">{targetText}</span>
    </div>
  );
}
