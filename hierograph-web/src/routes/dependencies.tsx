import { createFileRoute } from "@tanstack/react-router";

import { DependenciesPage } from "@/dependencies/DependenciesPage";

export const Route = createFileRoute("/dependencies")({
  component: DependenciesPage,
});
