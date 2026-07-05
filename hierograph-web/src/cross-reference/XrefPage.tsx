import { DependencyDetailsPane } from "@/dependency-details";
import { OneOneSplitLayout } from "@/design-system/layout/OneOneSplitLayout";
import { SelectionProvider } from "@/selection";
import { useTreeSettings } from "@/tree";

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
