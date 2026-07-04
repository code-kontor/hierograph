import { createFileRoute } from "@tanstack/react-router";

import { CrossReferenceView } from "@/components/cross-reference/CrossReferenceView";
import { DependencyDetailsPane } from "@/components/dependency-details/DependencyDetailsPane";
import { SelectionProvider } from "@/components/hierarchy/SelectionContext";
import { useTreeSettings } from "@/components/hierarchy/useTreeSettings";
import { OneOneSplitLayout } from "@/components/layout/OneOneSplitLayout";

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
