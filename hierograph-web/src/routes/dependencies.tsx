import { createFileRoute } from "@tanstack/react-router";

import { DependencyDetailsPane } from "@/components/dependency-details/DependencyDetailsPane";
import { DependencyMatrix } from "@/components/dsm/DependencyMatrix";
import { HierarchyTree } from "@/components/hierarchy/HierarchyTree";
import { SelectionProvider } from "@/components/hierarchy/SelectionContext";
import { Pane } from "@/components/layout/Pane";
import { TwoOneSplitLayout } from "@/components/layout/TwoOneSplitLayout";
import { TreeSettingsMenu } from "@/components/tree/TreeSettingsMenu";
import { useTreeSettings } from "@/components/tree/useTreeSettings";

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
        topRight={<DependencyMatrix />}
        bottom={<DependencyDetailsPane />}
      />
    </SelectionProvider>
  );
}
