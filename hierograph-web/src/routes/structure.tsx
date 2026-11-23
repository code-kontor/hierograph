import { createFileRoute } from "@tanstack/react-router";

import { parseIdList, parseSingleId } from "@/routing/searchCodec";
import { StructurePage } from "@/structure/StructurePage";

// The structure map's own params: `expanded_ids`, the expanded containers;
// `selected_id`, the selected box; `selection_pinned`, whether it is pinned;
// `dependency_ids`, dependencies
// handed to the map as `<source id>-<target id>` (a source with several
// targets grouped as `<source>-<target>.<target>`), drawn and listed beside
// it; and `given_only`, drawing only those. Unknown ids are kept; the map
// ignores them, and lists the dependencies' unknown ids.
type StructureSearch = {
  expanded_ids?: string[];
  selected_id?: string;
  selection_pinned?: boolean;
  dependency_ids?: string[];
  given_only?: boolean;
};

export const Route = createFileRoute("/structure")({
  validateSearch: (search: Record<string, unknown>): StructureSearch => ({
    expanded_ids: parseIdList(search.expanded_ids),
    selected_id: parseSingleId(search.selected_id),
    selection_pinned:
      search.selection_pinned === true || search.selection_pinned === "true"
        ? true
        : undefined,
    dependency_ids: parseIdList(search.dependency_ids),
    given_only:
      search.given_only === true || search.given_only === "true"
        ? true
        : undefined,
  }),
  component: StructurePage,
});
