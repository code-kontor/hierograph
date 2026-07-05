import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { OneOneSplitLayout } from "@/design-system/layout/OneOneSplitLayout";
import { SelectionProvider } from "@/selection/SelectionContext";
import { useTreeSettings } from "@/tree/useTreeSettings";

import { CrossReferenceView } from "./CrossReferenceView";

export function XrefPage() {
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
          <CrossReferenceView
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
