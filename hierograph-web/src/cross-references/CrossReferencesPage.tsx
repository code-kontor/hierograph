import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { Pane } from "@/design-system/layout/Pane";
import { TwoOneSplitLayout } from "@/design-system/layout/TwoOneSplitLayout";
import { Message } from "@/design-system/ui/message";
import { HierarchyTree } from "@/hierarchy/HierarchyTree";
import { TreeSettingsMenu } from "@/tree/TreeSettingsMenu";
import { useTreeSettings } from "@/tree/useTreeSettings";

export function CrossReferencesPage() {
  const {
    settings,
    setShowIndentGuides,
    setAutoExpandSingleChildren,
    setPreserveSelectionOnCollapse,
    setLabelFormat,
  } = useTreeSettings();

  return (
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
        <Pane
          title="Cross References"
          bodyClassName="flex items-center justify-center"
        >
          <Message variant="empty" title="Cross References">
            Cross-reference analysis coming soon.
          </Message>
        </Pane>
      }
      bottom={<DependencyDetailsPane />}
    />
  );
}
