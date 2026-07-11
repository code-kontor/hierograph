import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { Pane } from "@/design-system/layout/Pane";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/design-system/ui/resizable";
import { SelectionProvider } from "@/selection/SelectionContext";
import { TreeSettingsMenu } from "@/tree/TreeSettingsMenu";
import { useTreeSettings } from "@/tree/useTreeSettings";

import { DependencyDiagram } from "./DependencyDiagram";
import { HierarchyPane } from "./HierarchyPane";

export function DependencyDiagramPage() {
  const {
    settings,
    setShowIndentGuides,
    setAutoExpandSingleChildren,
    setPreserveSelectionOnCollapse,
    setLabelFormat,
  } = useTreeSettings();

  return (
    <SelectionProvider>
      <ResizablePanelGroup
        direction="vertical"
        autoSaveId="dependency-diagram-2-1-v"
        className="h-full"
      >
        <ResizablePanel defaultSize={60} minSize={20}>
          <ResizablePanelGroup
            direction="horizontal"
            autoSaveId="dependency-diagram-1-1"
            className="h-full"
          >
            <ResizablePanel defaultSize={33} minSize={15}>
              <Pane
                title="Hierarchical Graph"
                bodyClassName="overflow-hidden p-0"
                toolbar={
                  <TreeSettingsMenu
                    {...settings}
                    setShowIndentGuides={setShowIndentGuides}
                    setAutoExpandSingleChildren={setAutoExpandSingleChildren}
                    setPreserveSelectionOnCollapse={
                      setPreserveSelectionOnCollapse
                    }
                    setLabelFormat={setLabelFormat}
                  />
                }
              >
                <HierarchyPane settings={settings} />
              </Pane>
            </ResizablePanel>
            <ResizableHandle withHandle />
            <ResizablePanel defaultSize={67} minSize={15}>
              <DependencyDiagram />
            </ResizablePanel>
          </ResizablePanelGroup>
        </ResizablePanel>
        <ResizableHandle withHandle />
        <ResizablePanel defaultSize={40} minSize={15}>
          <DependencyDetailsPane />
        </ResizablePanel>
      </ResizablePanelGroup>
    </SelectionProvider>
  );
}
