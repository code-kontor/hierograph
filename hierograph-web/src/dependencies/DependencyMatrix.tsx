import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";

import { Pane } from "@/design-system/layout/Pane";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuGhostTrigger,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
} from "@/design-system/ui/dropdown-menu";
import { Message } from "@/design-system/ui/message";
import { useLocalStorage } from "@/design-system/useLocalStorage";
import { formatNodeLabel, type NodeLabelFormat } from "@/graph/nodeLabel";
import type { NodeAdjacencyMatrixQuery } from "@/graphql/generated/graphql";
import { useSelection } from "@/selection/SelectionContext";

import { DsmCanvas, type DsmCellSelection } from "./DsmCanvas";
import {
  LABEL_FORMAT_OPTIONS,
  LABEL_FORMAT_STORAGE_KEY,
  SHOW_DIAGONAL_DEFAULT,
  SHOW_DIAGONAL_STORAGE_KEY,
} from "./dsmLabelSettings";
import { dsmQueryOptions, nodesDsmQueryOptions } from "./queries";

type MatrixData = NonNullable<
  NonNullable<NodeAdjacencyMatrixQuery["hierarchicalGraph"]>["node"]
>["children"]["orderedAdjacencyMatrix"];

export function DependencyMatrix() {
  const { selectedIds, setCellSelection } = useSelection();

  useEffect(() => {
    setCellSelection(null);
  }, [selectedIds, setCellSelection]);

  if (selectedIds.length === 0) {
    return (
      <Pane title="Dependency Overview">
        <Message variant="empty">
          Select a package node to view its dependency matrix.
        </Message>
      </Pane>
    );
  }

  if (selectedIds.length === 1) {
    return <SingleNodeMatrix id={selectedIds[0]} />;
  }

  return <MultiNodeMatrix ids={selectedIds} />;
}

type SingleNodeMatrixProps = { id: string };

type MultiNodeMatrixProps = { ids: string[] };

type DsmSubject =
  { kind: "single"; name: string } | { kind: "multi"; count: number };

type MatrixViewProps = { matrix: MatrixData | undefined; subject: DsmSubject };

type DependencyFromToProps = { selection: DsmCellSelection | undefined };

type DsmSubjectHeaderProps = {
  subject: DsmSubject;
  labelFormat: NodeLabelFormat;
};

function DsmSubjectHeader({ subject, labelFormat }: DsmSubjectHeaderProps) {
  const text =
    subject.kind === "single"
      ? `Internals of ${formatNodeLabel(subject.name, labelFormat)}`
      : `${subject.count} selected nodes`;
  return (
    <div className="text-fg min-w-0 truncate font-mono text-[12px] leading-tight">
      {text}
    </div>
  );
}

function DependencyFromTo({ selection }: DependencyFromToProps) {
  return (
    <div className="flex flex-col gap-[2px] font-mono text-[12px] leading-tight">
      <div className="flex min-w-0">
        <span className="text-fg-subtle shrink-0">From:&nbsp;</span>
        <span className="text-fg min-w-0 truncate">
          {selection?.sourceLabel.text ?? "—"}
        </span>
      </div>
      <div className="flex min-w-0">
        <span className="text-fg-subtle shrink-0">To:&nbsp;</span>
        <span className="text-fg min-w-0 truncate">
          {selection?.targetLabel.text ?? "—"}
        </span>
      </div>
    </div>
  );
}

type DsmOptionsMenuProps = {
  labelFormat: NodeLabelFormat;
  onLabelFormatChange: (value: NodeLabelFormat) => void;
  showDiagonal: boolean;
  onShowDiagonalChange: (value: boolean) => void;
};

function DsmOptionsMenu({
  labelFormat,
  onLabelFormatChange,
  showDiagonal,
  onShowDiagonalChange,
}: DsmOptionsMenuProps) {
  return (
    <DropdownMenu>
      <DropdownMenuGhostTrigger title="DSM options" />
      <DropdownMenuContent>
        <DropdownMenuLabel>Options</DropdownMenuLabel>
        <DropdownMenuCheckboxItem
          checked={showDiagonal}
          onCheckedChange={onShowDiagonalChange}
          onSelect={(e) => e.preventDefault()}
        >
          Show diagonal
        </DropdownMenuCheckboxItem>
        <DropdownMenuSeparator />
        <DropdownMenuLabel>Label format</DropdownMenuLabel>
        <DropdownMenuRadioGroup
          value={labelFormat}
          onValueChange={(v) => onLabelFormatChange(v as NodeLabelFormat)}
        >
          <DropdownMenuRadioItem
            value={LABEL_FORMAT_OPTIONS[0].value}
            onSelect={(e) => e.preventDefault()}
          >
            {LABEL_FORMAT_OPTIONS[0].label}
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem
            value={LABEL_FORMAT_OPTIONS[1].value}
            onSelect={(e) => e.preventDefault()}
          >
            {LABEL_FORMAT_OPTIONS[1].label}
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem
            value={LABEL_FORMAT_OPTIONS[2].value}
            onSelect={(e) => e.preventDefault()}
          >
            {LABEL_FORMAT_OPTIONS[2].label}
          </DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function DsmLegend() {
  return (
    <div className="text-muted-foreground border-border flex flex-wrap items-center gap-3 border-t px-3 py-2 font-mono text-[11px]">
      <div className="flex items-center gap-1.5">
        <span className="border-dsm-grid bg-dsm-empty inline-flex size-4 shrink-0 items-center justify-center rounded-sm border text-[9px]">
          n
        </span>
        <span>dependency</span>
      </div>
      <div className="flex items-center gap-1.5">
        <span className="bg-dsm-cycle inline-block size-4 shrink-0 rounded-sm" />
        <span>cycle</span>
      </div>
      <div className="flex items-center gap-1.5">
        <span className="border-dsm-outline inline-block size-4 shrink-0 rounded-sm border-2" />
        <span>hovered / selected</span>
      </div>
      <span className="ml-auto">click a cell → details</span>
    </div>
  );
}

function SingleNodeMatrix({ id }: SingleNodeMatrixProps) {
  const { data, isPending, isError } = useQuery(dsmQueryOptions(id));

  if (isPending) {
    return (
      <Pane title="Dependency Overview">
        <Message variant="loading">Loading dependency matrix…</Message>
      </Pane>
    );
  }

  if (isError) {
    return (
      <Pane title="Dependency Overview">
        <Message variant="error">Could not load dependency matrix.</Message>
      </Pane>
    );
  }

  const matrix = data.hierarchicalGraph?.node?.children?.orderedAdjacencyMatrix;
  return (
    <MatrixView
      matrix={matrix}
      subject={{
        kind: "single",
        name: data.hierarchicalGraph?.node?.text ?? "",
      }}
    />
  );
}

function MultiNodeMatrix({ ids }: MultiNodeMatrixProps) {
  const { data, isPending, isError } = useQuery(nodesDsmQueryOptions(ids));

  if (isPending) {
    return (
      <Pane title="Dependency Overview">
        <Message variant="loading">Loading dependency matrix…</Message>
      </Pane>
    );
  }

  if (isError) {
    return (
      <Pane title="Dependency Overview">
        <Message variant="error">Could not load dependency matrix.</Message>
      </Pane>
    );
  }

  const matrix = data.hierarchicalGraph?.nodes?.orderedAdjacencyMatrix;
  return (
    <MatrixView
      matrix={matrix}
      subject={{ kind: "multi", count: ids.length }}
    />
  );
}

function MatrixView({ matrix, subject }: MatrixViewProps) {
  const { setCellSelection } = useSelection();
  const [hovered, setHovered] = useState<DsmCellSelection | undefined>(
    undefined,
  );
  const [selectedCell, setSelectedCell] = useState<
    DsmCellSelection | undefined
  >(undefined);

  function handleSelectCell(sel: DsmCellSelection | undefined) {
    setSelectedCell(sel);
    setCellSelection(
      sel
        ? { sourceNodeId: sel.sourceNodeId, targetNodeId: sel.targetNodeId }
        : null,
    );
  }

  const [storedFormat, setLabelFormat] = useLocalStorage<string>(
    LABEL_FORMAT_STORAGE_KEY,
    "last-segment",
  );
  const labelFormat: NodeLabelFormat = LABEL_FORMAT_OPTIONS.some(
    (o) => o.value === storedFormat,
  )
    ? (storedFormat as NodeLabelFormat)
    : "last-segment";

  const [showDiagonal, setShowDiagonal] = useLocalStorage<boolean>(
    SHOW_DIAGONAL_STORAGE_KEY,
    SHOW_DIAGONAL_DEFAULT,
  );

  const orderedNodes = matrix?.orderedNodes ?? [];

  if (orderedNodes.length === 0) {
    return (
      <Pane title="Dependency Overview">
        <Message variant="empty">No dependencies to display.</Message>
      </Pane>
    );
  }

  const cells = matrix?.cells ?? [];
  const sccs = matrix?.stronglyConnectedComponents ?? [];
  const display = hovered ?? selectedCell;

  return (
    <Pane
      title="Dependency Overview"
      toolbar={
        <DsmOptionsMenu
          labelFormat={labelFormat}
          onLabelFormatChange={setLabelFormat}
          showDiagonal={showDiagonal}
          onShowDiagonalChange={setShowDiagonal}
        />
      }
      subHeader={
        <div className="flex flex-col gap-1">
          <DsmSubjectHeader subject={subject} labelFormat={labelFormat} />
          <DependencyFromTo selection={display} />
        </div>
      }
      bodyClassName="p-0 flex flex-col overflow-hidden"
    >
      <div className="min-h-0 flex-1 overflow-auto">
        <DsmCanvas
          labels={orderedNodes}
          cells={cells}
          sccs={sccs}
          labelFormat={labelFormat}
          showDiagonal={showDiagonal}
          onHoverCell={setHovered}
          onSelectCell={handleSelectCell}
        />
      </div>
      <DsmLegend />
    </Pane>
  );
}
