import { createFileRoute } from "@tanstack/react-router";

import { DsmPage } from "@/dsm/DsmPage";
import { validateDsmSearch } from "@/routing/searchCodec";

export const Route = createFileRoute("/dsm")({
  validateSearch: validateDsmSearch,
  component: DsmPage,
});
