import { createFileRoute } from "@tanstack/react-router";

import { parseIdList, parseSingleId } from "@/routing/searchCodec";
import { StructurePage } from "@/structure/StructurePage";

// The structure map's own params: `expanded_ids`, the expanded containers,
// and `pinned_id`, the box whose arrows are pinned. Unknown ids are kept; the map ignores
// them.
type StructureSearch = {
  expanded_ids?: string[];
  pinned_id?: string;
};

export const Route = createFileRoute("/structure")({
  validateSearch: (search: Record<string, unknown>): StructureSearch => ({
    expanded_ids: parseIdList(search.expanded_ids),
    pinned_id: parseSingleId(search.pinned_id),
  }),
  component: StructurePage,
});
