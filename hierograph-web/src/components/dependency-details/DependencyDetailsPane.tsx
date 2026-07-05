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
