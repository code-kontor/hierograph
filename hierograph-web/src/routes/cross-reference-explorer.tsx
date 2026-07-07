import { createFileRoute } from "@tanstack/react-router";

import { CrossReferenceExplorerPage } from "@/cross-reference-explorer/CrossReferenceExplorerPage";

export const Route = createFileRoute("/cross-reference-explorer")({
  component: CrossReferenceExplorerPage,
});
