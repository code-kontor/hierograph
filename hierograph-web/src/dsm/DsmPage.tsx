import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { Pane } from "@/design-system/layout/Pane";
import { TwoOneSplitLayout } from "@/design-system/layout/TwoOneSplitLayout";
import { SelectionProvider } from "@/selection/SelectionContext";
import { TreeSettingsMenu } from "@/tree/TreeSettingsMenu";
import { useTreeSettings } from "@/tree/useTreeSettings";

import { DependencyMatrix } from "./DependencyMatrix";
import { HierarchyTree } from "./HierarchyTree";

export function DsmPage() {
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
