import { useQuery } from "@tanstack/react-query";

import { nodeBasicsQueryOptions } from "@/graph/queries";

type DependencyInspectorHeaderProps = {
  sourceNodeId: string;
  targetNodeId: string;
};

export function DependencyInspectorHeader({
  sourceNodeId,
  targetNodeId,
}: DependencyInspectorHeaderProps) {
  const { data: sourceData, isPending: sourcePending } = useQuery(
    nodeBasicsQueryOptions(sourceNodeId),
  );
  const { data: targetData, isPending: targetPending } = useQuery(
    nodeBasicsQueryOptions(targetNodeId),
  );

  const sourceText = sourcePending
    ? sourceNodeId
    : (sourceData?.hierarchicalGraph?.node?.text ?? sourceNodeId);
  const targetText = targetPending
    ? targetNodeId
    : (targetData?.hierarchicalGraph?.node?.text ?? targetNodeId);

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
