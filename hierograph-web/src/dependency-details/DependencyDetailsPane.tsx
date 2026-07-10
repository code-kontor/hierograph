import { ChevronsDownUp, ChevronsUpDown, Filter, X } from "lucide-react";
import { type RefObject, useRef, useState } from "react";
import { twMerge } from "tailwind-merge";

import { Pane } from "@/design-system/layout/Pane";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGhostTrigger,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  ghostIconTriggerClassName,
} from "@/design-system/ui/dropdown-menu";
import { HelpPopoverButton } from "@/design-system/ui/help-popover";
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
  LABEL_FORMAT_STORAGE_KEY,
  normalizeLabelFormat,
  normalizeTraceViewMode,
  TRACE_VIEW_MODE_STORAGE_KEY,
} from "./dependencyDetailsLabelSettings";
import { DependencyEdgeTable } from "./DependencyEdgeTable";
import { DependencyInspectorHeader } from "./DependencyInspectorHeader";
import type { TraceViewMode } from "./serializeTrace";
import {
  TRACE_HELP_LABEL,
  TraceHelpContent,
  USAGES_HELP_LABEL,
  UsagesHelpContent,
} from "./TabHelp";
import { TraceCopyButton } from "./TraceCopyButton";
import { TracePanel, type TracePanelHandle } from "./TracePanel";

type ActiveTab = "usages" | "paths";

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

type TraceControlsProps = {
  viewMode: TraceViewMode;
  setViewMode: (value: TraceViewMode) => void;
  traceRef: RefObject<TracePanelHandle | null>;
  hasSelection: boolean;
};

function TraceControls({
  viewMode,
  setViewMode,
  traceRef,
  hasSelection,
}: TraceControlsProps) {
  return (
    <div className="ml-auto flex items-center gap-1">
      <button
        type="button"
        title="Clear selection"
        disabled={!hasSelection}
        onClick={() => traceRef.current?.clearSelection()}
        className={twMerge(
          ghostIconTriggerClassName,
          !hasSelection && "cursor-not-allowed opacity-40",
        )}
      >
        <X className="size-4" />
      </button>
      <button
        type="button"
        title="Expand all"
        onClick={() => traceRef.current?.expandAll()}
        className={ghostIconTriggerClassName}
      >
        <ChevronsUpDown className="size-4" />
      </button>
      <button
        type="button"
        title="Collapse all"
        onClick={() => traceRef.current?.collapseAll()}
        className={ghostIconTriggerClassName}
      >
        <ChevronsDownUp className="size-4" />
      </button>
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

export type DependencyDetailsPaneProps = {
  emptyStateTitle?: string;
  emptyStateDescription?: string;
};

export function DependencyDetailsPane({
  emptyStateTitle = "No cell selected",
  emptyStateDescription = "Pick a dependency cell in the matrix to inspect its usages and paths.",
}: DependencyDetailsPaneProps = {}) {
  const { cellSelection } = useSelection();
  const [activeTab, setActiveTab] = useState<ActiveTab>("usages");
  const [storedLabelFormat, setLabelFormat] = useLocalStorage<string>(
    LABEL_FORMAT_STORAGE_KEY,
    "full",
  );
  const labelFormat = normalizeLabelFormat(storedLabelFormat);

  const [autoExpandSingleChildren] = useLocalStorage<boolean>(
    AUTO_EXPAND_STORAGE_KEY,
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

  // Mirrors the trace panel's driver presence so the Clear Selection control
  // can disable when nothing is selected.
  const [traceHasSelection, setTraceHasSelection] = useState(false);
  const handleTraceSelectionChange = (hasSelection: boolean) =>
    setTraceHasSelection(hasSelection);

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
              <TabsTrigger value="paths">Paths</TabsTrigger>
            </TabsList>
            <div className="ml-auto flex items-center pr-3">
              {activeTab === "usages" && (
                <HelpPopoverButton label={USAGES_HELP_LABEL}>
                  <UsagesHelpContent />
                </HelpPopoverButton>
              )}
              {activeTab === "paths" && (
                <HelpPopoverButton label={TRACE_HELP_LABEL}>
                  <TraceHelpContent />
                </HelpPopoverButton>
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
              {activeTab === "paths" && (
                <TraceControls
                  viewMode={viewMode}
                  setViewMode={setViewMode}
                  traceRef={traceRef}
                  hasSelection={traceHasSelection}
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
              value="paths"
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
                onSelectionChange={handleTraceSelectionChange}
              />
            </TabsContent>
          </>
        ) : (
          <div className="p-4">
            <Message variant="empty" title={emptyStateTitle}>
              {emptyStateDescription}
            </Message>
          </div>
        )}
      </Pane>
    </Tabs>
  );
}
