import { createFileRoute } from "@tanstack/react-router";

import { HierarchyTree } from "@/components/hierarchy/HierarchyTree";
import { NodeDetails } from "@/components/hierarchy/NodeDetails";
import { SelectionProvider } from "@/components/hierarchy/SelectionContext";
import { Pane } from "@/components/layout/Pane";
import { TwoOneSplitLayout } from "@/components/layout/TwoOneSplitLayout";

export const Route = createFileRoute("/dependencies")({
  component: DependenciesView,
});

function DependenciesView() {
  return (
    <SelectionProvider>
      <TwoOneSplitLayout
        topLeft={
          <Pane title="Hierarchical Graph">
            <HierarchyTree />
          </Pane>
        }
        topRight={
          <Pane title="Dependency Overview">
            <p className="text-muted-foreground text-sm">
              TODO: Dependency Overview
            </p>
          </Pane>
        }
        bottom={
          <Pane title="Dependencies Details">
            <NodeDetails />
          </Pane>
        }
      />
    </SelectionProvider>
  );
}
