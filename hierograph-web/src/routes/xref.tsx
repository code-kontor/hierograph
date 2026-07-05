import { createFileRoute } from "@tanstack/react-router";

import { CrossReferenceView } from "@/components/cross-reference/CrossReferenceView";
import { DependencyDetailsPane } from "@/components/dependency-details/DependencyDetailsPane";
import { SelectionProvider } from "@/components/hierarchy/SelectionContext";
import { OneOneSplitLayout } from "@/components/layout/OneOneSplitLayout";
import { useTreeSettings } from "@/components/tree/useTreeSettings";

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
