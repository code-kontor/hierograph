import { createFileRoute } from "@tanstack/react-router";

import { parseEnum, parseIdList, parseSingleId } from "@/routing/searchCodec";
import {
  DEPENDENCY_LIST_TABS,
  DIRECTION_MODES,
  StartPage,
} from "@/start/StartPage";

// The start screen's own params: the tree's, `expanded_ids`, its open rows,
// and the selection, one row or a group of rows taken together
// (`selected_ids`; a link's `selected_id`, one row, is read as it) or a
// cycle (`selected_cycle_ids`, its members); and the nodes
// picked in the dependency trees: one that uses the selection (`used_by_id`),
// a part of it (`selection_part_id`), one it uses (`uses_id`); and the open
// open rows of each dependency tree (`used_by_expanded_ids`,
// `selection_expanded_ids`, `uses_expanded_ids`), and which of the Selection
// tree's tops, all open without it, are closed (`selection_collapsed_ids`);
// and the open
// tab of the dependency list (`dependency_list`: `incoming`, `outgoing`), an
// unknown value dropped; which directions the dependencies show
// (`dependency_directions`: `incoming`, `outgoing`; both without it); and whether the hierarchy is collapsed to a rail
// (`hierarchy_collapsed`, on a wide screen).
type StartSearch = {
  expanded_ids?: string[];
  selected_ids?: string[];
  selected_cycle_ids?: string[];
  used_by_id?: string;
  selection_part_id?: string;
  uses_id?: string;
  used_by_expanded_ids?: string[];
  selection_expanded_ids?: string[];
  uses_expanded_ids?: string[];
  selection_collapsed_ids?: string[];
  hierarchy_collapsed?: boolean;
  dependency_list?: (typeof DEPENDENCY_LIST_TABS)[number];
  dependency_directions?: (typeof DIRECTION_MODES)[number];
};

export const Route = createFileRoute("/")({
  validateSearch: (search: Record<string, unknown>): StartSearch => ({
    expanded_ids: parseIdList(search.expanded_ids),
    selected_ids:
      parseIdList(search.selected_ids) ?? parseIdList(search.selected_id),
    selected_cycle_ids: parseIdList(search.selected_cycle_ids),
    used_by_id: parseSingleId(search.used_by_id),
    selection_part_id: parseSingleId(search.selection_part_id),
    uses_id: parseSingleId(search.uses_id),
    used_by_expanded_ids: parseIdList(search.used_by_expanded_ids),
    selection_expanded_ids: parseIdList(search.selection_expanded_ids),
    uses_expanded_ids: parseIdList(search.uses_expanded_ids),
    selection_collapsed_ids: parseIdList(search.selection_collapsed_ids),
    hierarchy_collapsed:
      search.hierarchy_collapsed === true ||
      search.hierarchy_collapsed === "true"
        ? true
        : undefined,
    dependency_list: parseEnum(search.dependency_list, DEPENDENCY_LIST_TABS),
    dependency_directions: parseEnum(
      search.dependency_directions,
      DIRECTION_MODES,
    ),
  }),
  component: StartPage,
});
