import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { OneOneSplitLayout } from "@/design-system/layout/OneOneSplitLayout";
import { SelectionProvider } from "@/selection/SelectionContext";
import { useTreeSettings } from "@/tree/useTreeSettings";

import { CrossReferenceExplorerView } from "./CrossReferenceExplorerView";

export function CrossReferenceExplorerPage() {
  const {
    settings,
    setShowIndentGuides,
    setAutoExpandSingleChildren,
    setPreserveSelectionOnCollapse,
    setLabelFormat,
  } = useTreeSettings();

  return (
    <SelectionProvider>
      <OneOneSplitLayout
        top={
          <CrossReferenceExplorerView
            settings={settings}
            setShowIndentGuides={setShowIndentGuides}
            setAutoExpandSingleChildren={setAutoExpandSingleChildren}
            setPreserveSelectionOnCollapse={setPreserveSelectionOnCollapse}
            setLabelFormat={setLabelFormat}
          />
        }
        bottom={<DependencyDetailsPane />}
      />
    </SelectionProvider>
  );
}
