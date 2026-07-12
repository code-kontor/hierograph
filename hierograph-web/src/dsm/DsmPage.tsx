import { useNavigate, useSearch } from "@tanstack/react-router";

import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { Pane } from "@/design-system/layout/Pane";
import { TwoOneSplitLayout } from "@/design-system/layout/TwoOneSplitLayout";
import { TreeSettingsMenu } from "@/tree/TreeSettingsMenu";
import { useTreeSettings } from "@/tree/useTreeSettings";

import { DependencyMatrix } from "./DependencyMatrix";
import { DsmSelectionProvider } from "./DsmSelectionProvider";
import { HierarchyTree } from "./HierarchyTree";

export function DsmPage() {
  const {
    settings,
    setShowIndentGuides,
    setAutoExpandSingleChildren,
    setPreserveSelectionOnCollapse,
    setLabelFormat,
  } = useTreeSettings();

  // Inspector tab lives in the URL (source of truth). A tab switch replaces the
  // history entry rather than pushing (view toggle, not a navigation step); the
  // "usages" default is stripped from the URL by the root middleware.
  const search = useSearch({ from: "/dsm" });
  const navigate = useNavigate({ from: "/dsm" });
  const activeTab = search.tab ?? "usages";
  const handleTabChange = async (tab: "usages" | "paths") => {
    await navigate({
      search: (prev) => ({ ...prev, tab: tab === "usages" ? undefined : tab }),
      replace: true,
    });
  };

  return (
    <DsmSelectionProvider>
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
        bottom={
          <DependencyDetailsPane
            activeTab={activeTab}
            onActiveTabChange={handleTabChange}
          />
        }
      />
    </DsmSelectionProvider>
  );
}
