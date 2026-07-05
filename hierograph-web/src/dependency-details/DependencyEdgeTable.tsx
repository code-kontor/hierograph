import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { Button } from "@/design-system/ui/button";
import { Message } from "@/design-system/ui/message";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/design-system/ui/table";

import { dependencyEdgesQueryOptions } from "./queries";

const DEFAULT_PAGE_SIZE = 50;

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

type EdgePaginationProps = {
  pageNumber: number;
  maxPages: number;
  totalCount: number;
  onPrev: () => void;
  onNext: () => void;
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

function EdgePagination({
  pageNumber,
  maxPages,
  totalCount,
  onPrev,
  onNext,
}: EdgePaginationProps) {
  return (
    <div className="text-fg-muted flex items-center justify-between px-4 py-2 text-sm">
      <span>{totalCount} dependencies</span>
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={pageNumber <= 1}
          onClick={onPrev}
        >
          Previous
        </Button>
        <span>
          Page {pageNumber} of {maxPages}
        </span>
        <Button
          variant="outline"
          size="sm"
          disabled={pageNumber >= maxPages}
          onClick={onNext}
        >
          Next
        </Button>
      </div>
    </div>
  );
}

export function DependencyEdgeTable({
  sourceNodeId,
  targetNodeId,
}: DependencyEdgeTableProps) {
  const [selectedRowId, setSelectedRowId] = useState<string | null>(null);
  const [pageNumber, setPageNumber] = useState(1);

  const {
    data: edgesData,
    isPending: edgesPending,
    isError: edgesError,
  } = useQuery(
    dependencyEdgesQueryOptions(
      sourceNodeId,
      targetNodeId,
      pageNumber,
      DEFAULT_PAGE_SIZE,
    ),
  );

  if (edgesPending) {
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

  const page =
    edgesData?.hierarchicalGraph?.dependencySetForAggregatedDependency
      ?.dependencyPage;
  const dependencies = page?.dependencies ?? [];
  const pageInfo = page?.pageInfo;

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
    <div className="flex h-full min-h-0 flex-col">
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
      {pageInfo ? (
        <EdgePagination
          pageNumber={pageInfo.pageNumber}
          maxPages={pageInfo.maxPages}
          totalCount={pageInfo.totalCount}
          onPrev={() => setPageNumber((p) => Math.max(1, p - 1))}
          onNext={() =>
            setPageNumber((p) => Math.min(pageInfo.maxPages, p + 1))
          }
        />
      ) : null}
    </div>
  );
}
