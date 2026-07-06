// Drop descendants when an ancestor is also selected; dedupe; deterministic order.
// predecessorsById maps a node id to the set of its ancestor ids (Node.predecessors).
export function normalizeSubjectIds(
  ids: string[],
  predecessorsById: Map<string, Set<string>>,
): string[] {
  const unique = [...new Set(ids)];
  const kept = unique.filter((id) => {
    const ancestors = predecessorsById.get(id);
    if (!ancestors) return true;
    // Drop this id if another selected id is one of its ancestors.
    return !unique.some((other) => other !== id && ancestors.has(other));
  });
  return kept.sort();
}
