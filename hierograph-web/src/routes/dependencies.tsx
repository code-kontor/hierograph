import { createFileRoute } from "@tanstack/react-router";

import { DependencyDetailsPane } from "@/components/dependency-details/DependencyDetailsPane";
import { DependencyMatrix } from "@/components/dsm/DependencyMatrix";
import { HierarchyTree } from "@/components/hierarchy/HierarchyTree";
import { SelectionProvider } from "@/components/hierarchy/SelectionContext";
import { TreeSettingsMenu } from "@/components/hierarchy/TreeSettingsMenu";
import { useTreeSettings } from "@/components/hierarchy/useTreeSettings";
import { Pane } from "@/components/layout/Pane";
import { TwoOneSplitLayout } from "@/components/layout/TwoOneSplitLayout";

export const Route = createFileRoute("/dependencies")({
  component: DependenciesView,
});

function DependenciesView() {
  const {
    settings,
    setShowIndentGuides,
    setAutoExpandSingleChildren,
    setPreserveSelectionOnCollapse,
    setLabelFormat,
  } = useTreeSettings();

  return (
    <SelectionProvider>
      <TwoOneSplitLayout
        topLeft={
          <Pane
            title="Hierarchical Graph"
            bodyClassName="overflow-hidden p-0"
            toolbar={
              <TreeSettingsMenu
                {...settings}
                setShowIndentGuides={setShowIndentGuides}
                setAutoExpandSingleChildren={setAutoExpandSingleChildren}
                setPreserveSelectionOnCollapse={setPreserveSelectionOnCollapse}
                setLabelFormat={setLabelFormat}
              />
            }
          >
            <HierarchyTree settings={settings} />
          </Pane>
        }
        topRight={
          <Pane title="Dependency Overview">
            <DependencyMatrix />
          </Pane>
        }
        bottom={
          <Pane title="Dependencies Details">
            <DependencyDetailsPane />
          </Pane>
        }
      />
    </SelectionProvider>
  );
}
