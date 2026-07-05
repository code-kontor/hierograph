import { useState } from "react";

import { Pane } from "@/design-system/layout/Pane";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGhostTrigger,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
} from "@/design-system/ui/dropdown-menu";
import { Message } from "@/design-system/ui/message";
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "@/design-system/ui/tabs";
import { useLocalStorage } from "@/design-system/useLocalStorage";
import type { NodeLabelFormat } from "@/graph/nodeLabel";
import { useSelection } from "@/selection/SelectionContext";

import {
  LABEL_FORMAT_STORAGE_KEY,
  normalizeLabelFormat,
} from "./dependencyDetailsLabelSettings";
import { DependencyDetailsPanel } from "./DependencyDetailsPanel";
import { DependencyEdgeTable } from "./DependencyEdgeTable";
import { DependencyInspectorHeader } from "./DependencyInspectorHeader";
import { NodeDetailsWidget } from "./NodeDetailsWidget";

type ActiveTab = "usages" | "locations";

type LabelFormatMenuProps = {
  labelFormat: NodeLabelFormat;
  onLabelFormatChange: (value: NodeLabelFormat) => void;
};

function LabelFormatMenu({
  labelFormat,
  onLabelFormatChange,
}: LabelFormatMenuProps) {
  return (
    <DropdownMenu>
      <DropdownMenuGhostTrigger title="Label format" />
      <DropdownMenuContent>
        <DropdownMenuLabel>Label format</DropdownMenuLabel>
        <DropdownMenuRadioGroup
          value={labelFormat}
          onValueChange={(v) => onLabelFormatChange(v as NodeLabelFormat)}
        >
          <DropdownMenuRadioItem
            value="full"
            onSelect={(e) => e.preventDefault()}
          >
            Full
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem
            value="last-segment"
            onSelect={(e) => e.preventDefault()}
          >
            Own name
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem
            value="abbreviated"
            onSelect={(e) => e.preventDefault()}
          >
            Abbreviated qualifier
          </DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

export function DependencyDetailsPane() {
  const { cellSelection } = useSelection();
  const [activeTab, setActiveTab] = useState<ActiveTab>("usages");
  const [storedLabelFormat, setLabelFormat] = useLocalStorage<string>(
    LABEL_FORMAT_STORAGE_KEY,
    "full",
  );
  const labelFormat = normalizeLabelFormat(storedLabelFormat);

  const cellKey = cellSelection
    ? `${cellSelection.sourceNodeId}:${cellSelection.targetNodeId}`
    : null;

  // Selecting a matrix cell only updates the shown data — it must never change
  // the active tab. The tab changes only when the user clicks a tab title.

  return (
    <Tabs
      value={activeTab}
      onValueChange={(v) => setActiveTab(v as ActiveTab)}
      className="h-full min-h-0"
    >
      <Pane
        title="Dependencies Details"
        titleBar={
          <div className="flex flex-1 items-stretch">
            <div className="text-fg-muted border-border flex items-center border-r px-[14px] font-mono text-[11px] font-semibold tracking-[0.06em] uppercase">
              Dependencies Details
            </div>
            <TabsList>
              <TabsTrigger value="usages">Usages</TabsTrigger>
              <TabsTrigger value="locations">Locations</TabsTrigger>
            </TabsList>
            <div className="ml-auto flex items-center pr-3">
              <LabelFormatMenu
                labelFormat={labelFormat}
                onLabelFormatChange={setLabelFormat}
              />
            </div>
          </div>
        }
        subHeader={
          cellKey && cellSelection ? (
            <DependencyInspectorHeader
              sourceNodeId={cellSelection.sourceNodeId}
              targetNodeId={cellSelection.targetNodeId}
              labelFormat={labelFormat}
            />
          ) : undefined
        }
        bodyClassName="p-0"
      >
        {cellKey && cellSelection ? (
          <>
            <TabsContent value="usages" forceMount>
              <DependencyEdgeTable
                key={cellKey}
                sourceNodeId={cellSelection.sourceNodeId}
                targetNodeId={cellSelection.targetNodeId}
                labelFormat={labelFormat}
              />
            </TabsContent>
            <TabsContent value="locations" forceMount>
              <DependencyDetailsPanel
                key={cellKey}
                sourceNodeId={cellSelection.sourceNodeId}
                targetNodeId={cellSelection.targetNodeId}
                labelFormat={labelFormat}
              />
            </TabsContent>
          </>
        ) : (
          <div className="p-4">
            <Message variant="empty" title="No cell selected">
              Pick a dependency cell in the matrix to inspect its usages and
              locations.
            </Message>
          </div>
        )}
        {import.meta.env.DEV && <NodeDetailsWidget />}
      </Pane>
    </Tabs>
  );
}
