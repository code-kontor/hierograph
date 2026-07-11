import { createFileRoute } from "@tanstack/react-router";

import { DsmPage } from "@/dsm/DsmPage";

export const Route = createFileRoute("/dsm")({
  component: DsmPage,
});
