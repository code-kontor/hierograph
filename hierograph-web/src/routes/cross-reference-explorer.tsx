import { createFileRoute } from "@tanstack/react-router";

import { CrossReferenceExplorerPage } from "@/cross-reference-explorer/CrossReferenceExplorerPage";
import { validateCrossReferenceSearch } from "@/routing/searchCodec";

export const Route = createFileRoute("/cross-reference-explorer")({
  validateSearch: validateCrossReferenceSearch,
  component: CrossReferenceExplorerPage,
});
