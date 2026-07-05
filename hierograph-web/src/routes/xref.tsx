import { createFileRoute } from "@tanstack/react-router";

import { XrefPage } from "@/cross-reference";

export const Route = createFileRoute("/xref")({
  component: XrefPage,
});
