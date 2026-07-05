import { createFileRoute } from "@tanstack/react-router";

import { CrossReferenceView } from "@/cross-reference";
import { DependencyDetailsPane } from "@/dependency-details";
import { OneOneSplitLayout } from "@/design-system/layout/OneOneSplitLayout";
import { SelectionProvider } from "@/selection";
import { useTreeSettings } from "@/tree";

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
