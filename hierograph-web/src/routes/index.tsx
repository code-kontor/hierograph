import { createFileRoute } from "@tanstack/react-router";

import { parseEnum } from "@/routing/searchCodec";
import { START_TABS, StartPage, type StartTab } from "@/start/StartPage";

// The start screen's own params: `tab`, the tab of the lower pane
// (`workspace`, `graph`, `server`, `jvm`); an unknown value is dropped.
type StartSearch = {
  tab?: StartTab;
};

export const Route = createFileRoute("/")({
  validateSearch: (search: Record<string, unknown>): StartSearch => ({
    tab: parseEnum(search.tab, START_TABS),
  }),
  component: StartPage,
});
