import { createFileRoute } from "@tanstack/react-router";

import { HierarchyTree } from "@/components/hierarchy/HierarchyTree";
import {
  SelectionProvider,
  useSelection,
} from "@/components/hierarchy/SelectionContext";
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
            <SelectionSummary />
          </Pane>
        }
      />
    </SelectionProvider>
  );
}

function SelectionSummary() {
  const { selectedIds } = useSelection();

  if (selectedIds.length === 0) {
    return <p className="text-muted-foreground text-sm">Nothing selected.</p>;
  }

  return (
    <div className="text-sm">
      <p className="text-muted-foreground mb-2">
        Selected: {selectedIds.length}
      </p>
      <ul className="space-y-0.5">
        {selectedIds.map((id) => (
          <li key={id} className="font-mono text-xs">
            {id}
          </li>
        ))}
      </ul>
    </div>
  );
}
