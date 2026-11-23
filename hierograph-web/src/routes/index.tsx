import { createFileRoute } from "@tanstack/react-router";

import { parseEnum, parseIdList, parseSingleId } from "@/routing/searchCodec";
import { START_TABS, StartPage, type StartTab } from "@/start/StartPage";

// The start screen's own params: `tab`, the tab of the lower pane
// (`workspace`, `graph`, `server`, `jvm`), an unknown value dropped; and the
// tree's: `expanded_ids`, its open rows, and the pinned arcs, a row's
// (`pinned_id`) or a cycle's (`pinned_cycle_ids`, its members).
type StartSearch = {
  tab?: StartTab;
  expanded_ids?: string[];
  pinned_id?: string;
  pinned_cycle_ids?: string[];
};

export const Route = createFileRoute("/")({
  validateSearch: (search: Record<string, unknown>): StartSearch => ({
    tab: parseEnum(search.tab, START_TABS),
    expanded_ids: parseIdList(search.expanded_ids),
    pinned_id: parseSingleId(search.pinned_id),
    pinned_cycle_ids: parseIdList(search.pinned_cycle_ids),
  }),
  component: StartPage,
});
