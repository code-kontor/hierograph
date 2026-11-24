import { createFileRoute } from "@tanstack/react-router";

import { parseIdList, parseSingleId } from "@/routing/searchCodec";
import { StartPage } from "@/start/StartPage";

// The start screen's own params, the tree's: `expanded_ids`, its open rows,
// and the selection, a row (`selected_id`) or a cycle (`selected_cycle_ids`,
// its members), and whether it is pinned (`selection_pinned`).
type StartSearch = {
  expanded_ids?: string[];
  selected_id?: string;
  selected_cycle_ids?: string[];
  selection_pinned?: boolean;
};

export const Route = createFileRoute("/")({
  validateSearch: (search: Record<string, unknown>): StartSearch => ({
    expanded_ids: parseIdList(search.expanded_ids),
    selected_id: parseSingleId(search.selected_id),
    selected_cycle_ids: parseIdList(search.selected_cycle_ids),
    selection_pinned:
      search.selection_pinned === true || search.selection_pinned === "true"
        ? true
        : undefined,
  }),
  component: StartPage,
});
