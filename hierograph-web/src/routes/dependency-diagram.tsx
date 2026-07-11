import { createFileRoute } from "@tanstack/react-router";

import { DependencyDiagramPage } from "@/dependency-diagram/DependencyDiagramPage";
import { validateDependencyDiagramSearch } from "@/routing/searchCodec";

export const Route = createFileRoute("/dependency-diagram")({
  validateSearch: validateDependencyDiagramSearch,
  component: DependencyDiagramPage,
});
