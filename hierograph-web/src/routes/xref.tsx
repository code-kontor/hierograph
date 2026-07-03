import { createFileRoute } from "@tanstack/react-router";

import { OneOneSplitLayout } from "@/components/layout/OneOneSplitLayout";
import { Pane } from "@/components/layout/Pane";

export const Route = createFileRoute("/xref")({
  component: XrefView,
});

function XrefView() {
  return (
    <OneOneSplitLayout
      top={
        <Pane title="Cross-Reference View">
          <p className="text-muted-foreground text-sm">
            TODO: Cross-Reference View
          </p>
        </Pane>
      }
      bottom={
        <Pane title="Dependencies Details">
          <p className="text-muted-foreground text-sm">
            TODO: Dependencies Details
          </p>
        </Pane>
      }
    />
  );
}
