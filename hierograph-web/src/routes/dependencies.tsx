import { createFileRoute } from "@tanstack/react-router";

import { DependencyMatrix } from "@/components/dsm/DependencyMatrix";
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
            <DependencyMatrix />
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
