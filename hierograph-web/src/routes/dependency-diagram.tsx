import { createFileRoute } from "@tanstack/react-router";

import { DependencyDiagramPage } from "@/dependency-diagram/DependencyDiagramPage";

export const Route = createFileRoute("/dependency-diagram")({
  component: DependencyDiagramPage,
});
