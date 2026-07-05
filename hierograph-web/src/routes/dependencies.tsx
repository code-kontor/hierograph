import { createFileRoute } from "@tanstack/react-router";

import { DependencyMatrix } from "@/dependencies";
import { DependencyDetailsPane } from "@/dependency-details";
import { Pane } from "@/design-system/layout/Pane";
import { TwoOneSplitLayout } from "@/design-system/layout/TwoOneSplitLayout";
import { HierarchyTree } from "@/hierarchy";
import { SelectionProvider } from "@/selection";
import { TreeSettingsMenu, useTreeSettings } from "@/tree";

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
