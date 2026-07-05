import { createFileRoute } from "@tanstack/react-router";

import { DependenciesPage } from "@/dependencies";

export const Route = createFileRoute("/dependencies")({
  component: DependenciesPage,
});
