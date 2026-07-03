import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { twMerge } from "tailwind-merge";

import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { NodeAdjacencyMatrixQuery } from "@/generated/graphql/graphql";
import { useLocalStorage } from "@/hooks/useLocalStorage";
import { dsmQueryOptions, nodesDsmQueryOptions } from "@/queries/dsm";

import { useSelection } from "../hierarchy/SelectionContext";
import { DsmCanvas, type DsmCellSelection } from "./DsmCanvas";
import {
  LABEL_FORMAT_OPTIONS,
  LABEL_FORMAT_STORAGE_KEY,
  type LabelFormat,
} from "./labelFormat";

type MatrixData = NonNullable<
  NonNullable<NodeAdjacencyMatrixQuery["hierarchicalGraph"]>["node"]
>["children"]["orderedAdjacencyMatrix"];

export function DependencyMatrix() {
  const { selectedIds } = useSelection();

  if (selectedIds.length === 0) {
    return (
      <p className="text-muted-foreground text-sm">
        Select a node to view its dependency matrix.
      </p>
    );
  }

  if (selectedIds.length === 1) {
    return <SingleNodeMatrix id={selectedIds[0]} />;
  }

  return <MultiNodeMatrix ids={selectedIds} />;
}

type SingleNodeMatrixProps = { id: string };

type MultiNodeMatrixProps = { ids: string[] };

type MatrixViewProps = { matrix: MatrixData | undefined };

type DependencyFromToProps = { selection: DsmCellSelection | undefined };

function DependencyFromTo({ selection }: DependencyFromToProps) {
  return (
    <div className="text-muted-foreground min-w-0 flex-1 text-sm">
      <div className="flex min-w-0">
        <span className="shrink-0">From:&nbsp;</span>
        <span className={twMerge("min-w-0 truncate")}>
          {selection?.sourceLabel.text ?? "—"}
        </span>
      </div>
      <div className="flex min-w-0">
        <span className="shrink-0">To:&nbsp;</span>
        <span className={twMerge("min-w-0 truncate")}>
          {selection?.targetLabel.text ?? "—"}
        </span>
      </div>
    </div>
  );
}

type LabelFormatSelectProps = {
  value: LabelFormat;
  onChange: (value: LabelFormat) => void;
};

function LabelFormatSelect({ value, onChange }: LabelFormatSelectProps) {
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger className="w-44 shrink-0">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={LABEL_FORMAT_OPTIONS[0].value}>
          {LABEL_FORMAT_OPTIONS[0].label}
        </SelectItem>
        <SelectItem value={LABEL_FORMAT_OPTIONS[1].value}>
          {LABEL_FORMAT_OPTIONS[1].label}
        </SelectItem>
        <SelectItem value={LABEL_FORMAT_OPTIONS[2].value}>
          {LABEL_FORMAT_OPTIONS[2].label}
        </SelectItem>
      </SelectContent>
    </Select>
  );
}

function SingleNodeMatrix({ id }: SingleNodeMatrixProps) {
  const { data, isPending, isError } = useQuery(dsmQueryOptions(id));

  if (isPending) {
    return (
      <p className="text-muted-foreground text-sm">
        Loading dependency matrix…
      </p>
    );
  }

  if (isError) {
    return (
      <p className="text-destructive text-sm">
        Could not load dependency matrix.
      </p>
    );
  }

  const matrix = data.hierarchicalGraph?.node?.children?.orderedAdjacencyMatrix;
  return <MatrixView matrix={matrix} />;
}

function MultiNodeMatrix({ ids }: MultiNodeMatrixProps) {
  const { data, isPending, isError } = useQuery(nodesDsmQueryOptions(ids));

  if (isPending) {
    return (
      <p className="text-muted-foreground text-sm">
        Loading dependency matrix…
      </p>
    );
  }

  if (isError) {
    return (
      <p className="text-destructive text-sm">
        Could not load dependency matrix.
      </p>
    );
  }

  const matrix = data.hierarchicalGraph?.nodes?.orderedAdjacencyMatrix;
  return <MatrixView matrix={matrix} />;
}

function MatrixView({ matrix }: MatrixViewProps) {
  const [hovered, setHovered] = useState<DsmCellSelection | undefined>(
    undefined,
  );
  const [selectedCell, setSelectedCell] = useState<
    DsmCellSelection | undefined
  >(undefined);
  const [storedFormat, setLabelFormat] = useLocalStorage<string>(
    LABEL_FORMAT_STORAGE_KEY,
    "full",
  );
  const labelFormat: LabelFormat = LABEL_FORMAT_OPTIONS.some(
    (o) => o.value === storedFormat,
  )
    ? (storedFormat as LabelFormat)
    : "full";

  const orderedNodes = matrix?.orderedNodes ?? [];

  if (orderedNodes.length === 0) {
    return (
      <p className="text-muted-foreground text-sm">
        No dependencies to display.
      </p>
    );
  }

  const cells = matrix?.cells ?? [];
  const sccs = matrix?.stronglyConnectedComponents ?? [];
  const display = hovered ?? selectedCell;

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-start gap-2 px-1 py-1">
        <DependencyFromTo selection={display} />
        <LabelFormatSelect value={labelFormat} onChange={setLabelFormat} />
      </div>
      <div className="overflow-auto">
        <DsmCanvas
          labels={orderedNodes}
          cells={cells}
          sccs={sccs}
          labelFormat={labelFormat}
          onHoverCell={setHovered}
          onSelectCell={setSelectedCell}
        />
      </div>
    </div>
  );
}
