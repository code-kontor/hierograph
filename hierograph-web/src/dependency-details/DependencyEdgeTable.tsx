import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { Message } from "@/design-system/ui/message";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/design-system/ui/table";
import { dependencyEdgesQueryOptions } from "@/queries/dependencies";
import { nodeBasicsQueryOptions } from "@/queries/hierarchical-graph";

type DependencyEdgeTableProps = {
  sourceNodeId: string;
  targetNodeId: string;
};

type EdgeRow = {
  id: string;
  fromText: string;
  usageType: string;
  toText: string;
};

type DependencyEdgeRowProps = {
  row: EdgeRow;
  isSelected: boolean;
  onClick: () => void;
};

function DependencyEdgeRow({
  row,
  isSelected,
  onClick,
}: DependencyEdgeRowProps) {
  return (
    <TableRow
      data-state={isSelected ? "selected" : undefined}
      onClick={onClick}
      className="cursor-pointer"
    >
      <TableCell>{row.fromText}</TableCell>
      <TableCell className="text-fg-muted font-sans">{row.usageType}</TableCell>
      <TableCell>{row.toText}</TableCell>
    </TableRow>
  );
}

export function DependencyEdgeTable({
  sourceNodeId,
  targetNodeId,
}: DependencyEdgeTableProps) {
  const [selectedRowId, setSelectedRowId] = useState<string | null>(null);

  const {
    data: edgesData,
    isPending: edgesPending,
    isError: edgesError,
  } = useQuery(dependencyEdgesQueryOptions(sourceNodeId, targetNodeId));
  const { data: sourceRootData, isPending: sourcePending } = useQuery(
    nodeBasicsQueryOptions(sourceNodeId),
  );
  const { data: targetRootData, isPending: targetPending } = useQuery(
    nodeBasicsQueryOptions(targetNodeId),
  );

  if (edgesPending || sourcePending || targetPending) {
    return (
      <div className="p-4">
        <Message variant="loading" title="Loading dependencies" />
      </div>
    );
  }

  if (edgesError) {
    return (
      <div className="p-4">
        <Message variant="error" title="Failed to load dependencies" />
      </div>
    );
  }

  const depSet =
    edgesData?.hierarchicalGraph?.dependencySetForAggregatedDependency;
  const dependencies = depSet?.dependencies ?? [];
  const depFrom = sourceRootData?.hierarchicalGraph?.node?.text ?? sourceNodeId;
  const depTo = targetRootData?.hierarchicalGraph?.node?.text ?? targetNodeId;

  if (dependencies.length === 0) {
    return (
      <div className="p-4">
        <Message variant="empty" title="No dependencies">
          No type-level dependencies for this cell.
        </Message>
      </div>
    );
  }

  const rows: EdgeRow[] = dependencies.map((dep) => ({
    id: dep.id,
    fromText: dep.sourceNode.text,
    usageType: dep.type,
    toText: dep.targetNode.text,
  }));

  return (
    <div>
      <div className="border-border flex items-center gap-2 border-b px-4 py-[9px] font-mono text-[12px]">
        <span className="text-fg-subtle">From</span>
        <span className="text-fg">{depFrom}</span>
        <span className="text-fg-subtle">→</span>
        <span className="text-fg-subtle">To</span>
        <span className="text-fg">{depTo}</span>
        <span className="text-border-strong">·</span>
        <span className="text-fg-subtle">
          {dependencies.length} type dependencies
        </span>
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-[38%]">From type</TableHead>
            <TableHead className="w-[24%]">Usage</TableHead>
            <TableHead className="w-[38%]">To type</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <DependencyEdgeRow
              key={row.id}
              row={row}
              isSelected={selectedRowId === row.id}
              onClick={() =>
                setSelectedRowId(selectedRowId === row.id ? null : row.id)
              }
            />
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
