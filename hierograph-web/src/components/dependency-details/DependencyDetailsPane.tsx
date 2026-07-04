import { useState } from "react";

import { NodeDetails } from "@/components/hierarchy/NodeDetails";
import { useSelection } from "@/components/hierarchy/SelectionContext";
import { Pane } from "@/components/layout/Pane";
import { Message } from "@/components/ui/message";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

import { DependencyDetailsPanel } from "./DependencyDetailsPanel";
import { DependencyEdgeTable } from "./DependencyEdgeTable";

type ActiveTab = "props" | "table" | "trees";

export function DependencyDetailsPane() {
  const { cellSelection } = useSelection();
  const [activeTab, setActiveTab] = useState<ActiveTab>("props");
  const [prevCellKey, setPrevCellKey] = useState<string | null>(null);

  const cellKey = cellSelection
    ? `${cellSelection.sourceNodeId}:${cellSelection.targetNodeId}`
    : null;

  // Auto-switch to "table" when a new cell is selected.
  // "Storing information from previous renders" pattern (React docs).
  // Guard cellKey !== null: tree navigation sets cellSelection to null, which
  // must not trigger a tab switch.
  if (cellKey !== prevCellKey) {
    setPrevCellKey(cellKey);
    if (cellKey !== null) {
      setActiveTab("table");
    }
  }

  return (
    <Tabs value={activeTab} onValueChange={(v) => setActiveTab(v as ActiveTab)}>
      <Pane
        title="Dependencies Details"
        titleBar={
          <>
            <div className="text-fg-muted border-border flex items-center border-r px-[14px] font-mono text-[11px] font-semibold tracking-[0.06em] uppercase">
              Dependencies Details
            </div>
            <TabsList>
              <TabsTrigger value="props">Node Details</TabsTrigger>
              <TabsTrigger value="table">Dependencies</TabsTrigger>
              <TabsTrigger value="trees">Cross-marked trees</TabsTrigger>
            </TabsList>
          </>
        }
        bodyClassName="p-0"
      >
        <TabsContent value="props" forceMount>
          <NodeDetails />
        </TabsContent>
        <TabsContent value="table" forceMount>
          {cellKey && cellSelection ? (
            <DependencyEdgeTable
              key={cellKey}
              sourceNodeId={cellSelection.sourceNodeId}
              targetNodeId={cellSelection.targetNodeId}
            />
          ) : (
            <div className="p-4">
              <Message variant="empty" title="No cell selected">
                Pick a dependency cell in the matrix.
              </Message>
            </div>
          )}
        </TabsContent>
        <TabsContent value="trees" forceMount>
          {cellKey && cellSelection ? (
            <DependencyDetailsPanel
              key={cellKey}
              sourceNodeId={cellSelection.sourceNodeId}
              targetNodeId={cellSelection.targetNodeId}
            />
          ) : (
            <div className="p-4">
              <Message variant="empty" title="No cell selected">
                Pick a dependency cell in the matrix.
              </Message>
            </div>
          )}
        </TabsContent>
      </Pane>
    </Tabs>
  );
}
