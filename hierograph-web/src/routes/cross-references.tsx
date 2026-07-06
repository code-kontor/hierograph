import { createFileRoute } from "@tanstack/react-router";

import { CrossReferencesPage } from "@/cross-references/CrossReferencesPage";

export const Route = createFileRoute("/cross-references")({
  component: CrossReferencesPage,
});
