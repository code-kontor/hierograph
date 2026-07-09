import { useQuery } from "@tanstack/react-query";

import { formatNodeLabel, type NodeLabelFormat } from "@/graph/nodeLabel";
import { nodeBasicsQueryOptions, rootNodeQueryOptions } from "@/graph/queries";

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
  const { data: rootData } = useQuery(rootNodeQueryOptions());

  const sourceNode = sourceData?.hierarchicalGraph?.node;
  const targetNode = targetData?.hierarchicalGraph?.node;
  const rootId = rootData?.hierarchicalGraph?.rootNode?.id;

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

  // Directional wording ("<source> uses <target>") rather than a bare arrow, so
  // the dependency direction reads unambiguously in every panel: a Used-by
  // partner reads "<partner> uses <center>", a Uses partner "<center> uses
  // <partner>". The two root cases below spell out the aggregated ends.
  if (rootId !== undefined && sourceNodeId === rootId) {
    return (
      <div className="flex items-center gap-2 font-mono text-[12px]">
        <span className="text-fg-subtle">Everything that uses</span>
        <span className="text-fg">{targetText}</span>
      </div>
    );
  }

  if (rootId !== undefined && targetNodeId === rootId) {
    return (
      <div className="flex items-center gap-2 font-mono text-[12px]">
        <span className="text-fg-subtle">Everything</span>
        <span className="text-fg">{sourceText}</span>
        <span className="text-fg-subtle">uses</span>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-2 font-mono text-[12px]">
      <span className="text-fg">{sourceText}</span>
      <span className="text-fg-subtle">uses</span>
      <span className="text-fg">{targetText}</span>
    </div>
  );
}
