import { Filter, ListFilter } from "lucide-react";
import { type RefObject, useRef, useState } from "react";
import { twMerge } from "tailwind-merge";

import { Pane } from "@/design-system/layout/Pane";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuGhostTrigger,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  ghostIconTriggerClassName,
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
  AUTO_EXPAND_STORAGE_KEY,
  AUTO_REVEAL_STORAGE_KEY,
  FILTER_COUNTERPARTS_STORAGE_KEY,
  HIGHLIGHT_ON_HOVER_STORAGE_KEY,
  LABEL_FORMAT_STORAGE_KEY,
  normalizeLabelFormat,
  normalizeTraceViewMode,
  TRACE_VIEW_MODE_STORAGE_KEY,
} from "./dependencyDetailsLabelSettings";
import { DependencyDetailsPanel } from "./DependencyDetailsPanel";
import { DependencyEdgeTable } from "./DependencyEdgeTable";
import { DependencyInspectorHeader } from "./DependencyInspectorHeader";
import { NodeDetailsWidget } from "./NodeDetailsWidget";
import type { TraceViewMode } from "./serializeTrace";
import {
  TabHelpButton,
  TRACE_HELP_LABEL,
  TraceHelpContent,
  USAGES_HELP_LABEL,
  UsagesHelpContent,
} from "./TabHelp";
import { TraceCopyButton } from "./TraceCopyButton";
import { TracePanel, type TracePanelHandle } from "./TracePanel";

type ActiveTab = "usages" | "locations" | "trace";

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

type LocationsControlsProps = {
  filterCounterparts: boolean;
  setFilterCounterparts: (value: boolean) => void;
  autoRevealCounterparts: boolean;
  setAutoRevealCounterparts: (value: boolean) => void;
  autoExpandSingleChildren: boolean;
  setAutoExpandSingleChildren: (value: boolean) => void;
  highlightOnHover: boolean;
  setHighlightOnHover: (value: boolean) => void;
};

function LocationsControls({
  filterCounterparts,
  setFilterCounterparts,
  autoRevealCounterparts,
  setAutoRevealCounterparts,
  autoExpandSingleChildren,
  setAutoExpandSingleChildren,
  highlightOnHover,
  setHighlightOnHover,
}: LocationsControlsProps) {
  return (
    <div className="ml-auto flex items-center gap-1">
      <button
        type="button"
        aria-pressed={filterCounterparts}
        title="Filter counterparts"
        onClick={() => setFilterCounterparts(!filterCounterparts)}
        className={twMerge(
          ghostIconTriggerClassName,
          filterCounterparts && "bg-state-hover text-fg",
        )}
      >
        <ListFilter className="size-4" />
      </button>
      <DropdownMenu>
        <DropdownMenuGhostTrigger title="Options" />
        <DropdownMenuContent>
          <DropdownMenuCheckboxItem
            checked={autoRevealCounterparts}
            onCheckedChange={setAutoRevealCounterparts}
            onSelect={(e) => e.preventDefault()}
          >
            Auto-reveal counterparts
          </DropdownMenuCheckboxItem>
          <DropdownMenuCheckboxItem
            checked={autoExpandSingleChildren}
            onCheckedChange={setAutoExpandSingleChildren}
            onSelect={(e) => e.preventDefault()}
          >
            Auto-expand single children
          </DropdownMenuCheckboxItem>
          <DropdownMenuCheckboxItem
            checked={highlightOnHover}
            onCheckedChange={setHighlightOnHover}
            onSelect={(e) => e.preventDefault()}
          >
            Highlight on hover
          </DropdownMenuCheckboxItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}

type TraceControlsProps = {
  viewMode: TraceViewMode;
  setViewMode: (value: TraceViewMode) => void;
  traceRef: RefObject<TracePanelHandle | null>;
};

function TraceControls({
  viewMode,
  setViewMode,
  traceRef,
}: TraceControlsProps) {
  return (
    <div className="ml-auto flex items-center gap-1">
      <button
        type="button"
        aria-pressed={viewMode === "hits-only"}
        title="Show hits only"
        onClick={() =>
          setViewMode(viewMode === "hits-only" ? "in-context" : "hits-only")
        }
        className={twMerge(
          ghostIconTriggerClassName,
          viewMode === "hits-only" && "bg-state-hover text-fg",
        )}
      >
        <Filter className="size-4" />
      </button>
      {import.meta.env.DEV && (
        <TraceCopyButton
          buildInput={() => traceRef.current?.buildSerializeInput() ?? null}
        />
      )}
    </div>
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

  // The Locations options live here (not in the per-cell-remounted Panel) so
  // they persist across cell selections and can drive the shared subHeader.
  const [autoExpandSingleChildren, setAutoExpandSingleChildren] =
    useLocalStorage<boolean>(AUTO_EXPAND_STORAGE_KEY, false);
  const [autoRevealCounterparts, setAutoRevealCounterparts] =
    useLocalStorage<boolean>(AUTO_REVEAL_STORAGE_KEY, false);
  const [highlightOnHover, setHighlightOnHover] = useLocalStorage<boolean>(
    HIGHLIGHT_ON_HOVER_STORAGE_KEY,
    false,
  );
  const [filterCounterparts, setFilterCounterparts] = useLocalStorage<boolean>(
    FILTER_COUNTERPARTS_STORAGE_KEY,
    false,
  );

  // Persisted here (not in the per-cell-remounted TracePanel) so "hits only"
  // survives both a cell change (key={cellKey} remount) and a tab switch
  // (forceMount keeps the panel mounted, but its own state would still reset).
  const [storedViewMode, setStoredViewMode] = useLocalStorage<string>(
    TRACE_VIEW_MODE_STORAGE_KEY,
    "in-context",
  );
  const viewMode = normalizeTraceViewMode(storedViewMode);
  const setViewMode = (value: TraceViewMode) => setStoredViewMode(value);
  const traceRef = useRef<TracePanelHandle>(null);

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
              <TabsTrigger value="trace">Trace</TabsTrigger>
            </TabsList>
            <div className="ml-auto flex items-center pr-3">
              {activeTab === "usages" && (
                <TabHelpButton label={USAGES_HELP_LABEL}>
                  <UsagesHelpContent />
                </TabHelpButton>
              )}
              {activeTab === "trace" && (
                <TabHelpButton label={TRACE_HELP_LABEL}>
                  <TraceHelpContent />
                </TabHelpButton>
              )}
              <LabelFormatMenu
                labelFormat={labelFormat}
                onLabelFormatChange={setLabelFormat}
              />
            </div>
          </div>
        }
        subHeader={
          cellKey && cellSelection ? (
            <div className="flex items-center gap-2">
              <DependencyInspectorHeader
                sourceNodeId={cellSelection.sourceNodeId}
                targetNodeId={cellSelection.targetNodeId}
                labelFormat={labelFormat}
              />
              {activeTab === "locations" && (
                <LocationsControls
                  filterCounterparts={filterCounterparts}
                  setFilterCounterparts={setFilterCounterparts}
                  autoRevealCounterparts={autoRevealCounterparts}
                  setAutoRevealCounterparts={setAutoRevealCounterparts}
                  autoExpandSingleChildren={autoExpandSingleChildren}
                  setAutoExpandSingleChildren={setAutoExpandSingleChildren}
                  highlightOnHover={highlightOnHover}
                  setHighlightOnHover={setHighlightOnHover}
                />
              )}
              {activeTab === "trace" && (
                <TraceControls
                  viewMode={viewMode}
                  setViewMode={setViewMode}
                  traceRef={traceRef}
                />
              )}
            </div>
          ) : undefined
        }
        bodyClassName="flex flex-col p-0"
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
            <TabsContent
              value="locations"
              forceMount
              className="flex min-h-0 flex-1 flex-col"
            >
              <DependencyDetailsPanel
                key={cellKey}
                sourceNodeId={cellSelection.sourceNodeId}
                targetNodeId={cellSelection.targetNodeId}
                labelFormat={labelFormat}
                autoExpandSingleChildren={autoExpandSingleChildren}
                autoRevealCounterparts={autoRevealCounterparts}
                highlightOnHover={highlightOnHover}
                filterCounterparts={filterCounterparts}
              />
            </TabsContent>
            <TabsContent
              value="trace"
              forceMount
              className="flex min-h-0 flex-1 flex-col"
            >
              <TracePanel
                key={cellKey}
                ref={traceRef}
                sourceNodeId={cellSelection.sourceNodeId}
                targetNodeId={cellSelection.targetNodeId}
                labelFormat={labelFormat}
                autoExpandSingleChildren={autoExpandSingleChildren}
                viewMode={viewMode}
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
