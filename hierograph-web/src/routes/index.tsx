import { createFileRoute } from "@tanstack/react-router";

import { parseEnum, parseIdList, parseSingleId } from "@/routing/searchCodec";
import { START_TABS, StartPage, type StartTab } from "@/start/StartPage";

// The start screen's own params: `tab`, the tab of the lower pane
// (`workspace`, `graph`, `server`, `jvm`), an unknown value dropped; and the
// tree's: `expanded_ids`, its open rows, and the selection, a row
// (`selected_id`) or a cycle (`selected_cycle_ids`, its members), and
// whether it is pinned (`selection_pinned`); and whether the list of what
// the view leaves out below the tree is open (`exclusions_open`).
type StartSearch = {
  tab?: StartTab;
  expanded_ids?: string[];
  selected_id?: string;
  selected_cycle_ids?: string[];
  selection_pinned?: boolean;
  exclusions_open?: boolean;
};

export const Route = createFileRoute("/")({
  validateSearch: (search: Record<string, unknown>): StartSearch => ({
    tab: parseEnum(search.tab, START_TABS),
    expanded_ids: parseIdList(search.expanded_ids),
    selected_id: parseSingleId(search.selected_id),
    selected_cycle_ids: parseIdList(search.selected_cycle_ids),
    selection_pinned:
      search.selection_pinned === true || search.selection_pinned === "true"
        ? true
        : undefined,
    exclusions_open:
      search.exclusions_open === true || search.exclusions_open === "true"
        ? true
        : undefined,
  }),
  component: StartPage,
});
