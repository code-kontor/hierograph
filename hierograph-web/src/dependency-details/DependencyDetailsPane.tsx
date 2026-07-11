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
  normalizePathsViewMode,
  PATHS_VIEW_MODE_STORAGE_KEY,
} from "./dependencyDetailsLabelSettings";
import { DependencyEdgeTable } from "./DependencyEdgeTable";
import { DependencyInspectorHeader } from "./DependencyInspectorHeader";
import { PathsCopyButton } from "./PathsCopyButton";
import { PathsPanel, type PathsPanelHandle } from "./PathsPanel";
import type { PathsViewMode } from "./serializePaths";
import {
  PATHS_HELP_LABEL,
  PathsHelpContent,
  USAGES_HELP_LABEL,
  UsagesHelpContent,
} from "./TabHelp";

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

type PathsControlsProps = {
  viewMode: PathsViewMode;
  setViewMode: (value: PathsViewMode) => void;
  pathsRef: RefObject<PathsPanelHandle | null>;
  hasSelection: boolean;
};

function PathsControls({
  viewMode,
  setViewMode,
  pathsRef,
  hasSelection,
}: PathsControlsProps) {
  return (
    <div className="ml-auto flex items-center gap-1">
      <button
        type="button"
        title="Clear selection"
        disabled={!hasSelection}
        onClick={() => pathsRef.current?.clearSelection()}
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
        onClick={() => pathsRef.current?.expandAll()}
        className={ghostIconTriggerClassName}
      >
        <ChevronsUpDown className="size-4" />
      </button>
      <button
        type="button"
        title="Collapse all"
        onClick={() => pathsRef.current?.collapseAll()}
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
        <PathsCopyButton
          buildInput={() => pathsRef.current?.buildSerializeInput() ?? null}
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

  // Persisted here (not in the per-cell-remounted PathsPanel) so "hits only"
  // survives both a cell change (key={cellKey} remount) and a tab switch
  // (forceMount keeps the panel mounted, but its own state would still reset).
  const [storedViewMode, setStoredViewMode] = useLocalStorage<string>(
    PATHS_VIEW_MODE_STORAGE_KEY,
    "in-context",
  );
  const viewMode = normalizePathsViewMode(storedViewMode);
  const setViewMode = (value: PathsViewMode) => setStoredViewMode(value);
  const pathsRef = useRef<PathsPanelHandle>(null);

  // Mirrors the paths panel's driver presence so the Clear Selection control
  // can disable when nothing is selected.
  const [pathsHasSelection, setPathsHasSelection] = useState(false);
  const handlePathsSelectionChange = (hasSelection: boolean) =>
    setPathsHasSelection(hasSelection);

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
                <HelpPopoverButton label={PATHS_HELP_LABEL}>
                  <PathsHelpContent />
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
                <PathsControls
                  viewMode={viewMode}
                  setViewMode={setViewMode}
                  pathsRef={pathsRef}
                  hasSelection={pathsHasSelection}
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
              <PathsPanel
                key={cellKey}
                ref={pathsRef}
                sourceNodeId={cellSelection.sourceNodeId}
                targetNodeId={cellSelection.targetNodeId}
                labelFormat={labelFormat}
                autoExpandSingleChildren={autoExpandSingleChildren}
                viewMode={viewMode}
                onSelectionChange={handlePathsSelectionChange}
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
