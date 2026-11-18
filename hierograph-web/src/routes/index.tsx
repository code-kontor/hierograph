import { createFileRoute } from "@tanstack/react-router";

import { StartPage } from "@/start/StartPage";

export const Route = createFileRoute("/")({
  component: StartPage,
});
