import { createFileRoute } from "@tanstack/react-router";

import { CrossReferenceView } from "@/cross-reference/CrossReferenceView";
import { DependencyDetailsPane } from "@/dependency-details/DependencyDetailsPane";
import { OneOneSplitLayout } from "@/design-system/layout/OneOneSplitLayout";
import { SelectionProvider } from "@/selection/SelectionContext";
import { useTreeSettings } from "@/tree/useTreeSettings";

export const Route = createFileRoute("/xref")({
  component: XrefView,
});

function XrefInner() {
  const {
    settings,
    setShowIndentGuides,
    setAutoExpandSingleChildren,
    setPreserveSelectionOnCollapse,
    setLabelFormat,
  } = useTreeSettings();

  return (
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
  );
}

function XrefView() {
  return (
    <SelectionProvider>
      <XrefInner />
    </SelectionProvider>
  );
}
