import { useQuery } from "@tanstack/react-query";

import { formatNodeLabel, type NodeLabelFormat } from "@/graph/nodeLabel";
import { nodeBasicsQueryOptions } from "@/graph/queries";

// Resolves a node id to its display label — empty until an id is selected,
// the raw id while the query is pending, then the formatted node text.
export function useNodeLabel(
  id: string | undefined,
  labelFormat: NodeLabelFormat,
): string {
  const { data, isPending } = useQuery({
    ...nodeBasicsQueryOptions(id ?? ""),
    enabled: id !== undefined,
  });

  if (id === undefined) {
    return "";
  }

  const node = data?.hierarchicalGraph?.node;
  return isPending
    ? id
    : formatNodeLabel(node?.text ?? id, labelFormat, node?.type);
}
