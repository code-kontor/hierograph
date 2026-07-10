import { DependencyDetailsPanel } from "@/dependency-details/DependencyDetailsPanel";
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
        bottom={
          <DependencyDetailsPanel
            emptyStateTitle="No selection"
            emptyStateDescription="Use the column inspect buttons or click a partner node to inspect dependencies."
          />
        }
      />
    </SelectionProvider>
  );
}
