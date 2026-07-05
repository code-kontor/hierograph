import { createFileRoute } from "@tanstack/react-router";

import { XrefPage } from "@/cross-reference/XrefPage";

export const Route = createFileRoute("/xref")({
  component: XrefPage,
});
